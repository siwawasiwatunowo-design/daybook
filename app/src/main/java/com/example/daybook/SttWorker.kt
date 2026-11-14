package com.example.daybook

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID

/**
 * Cloud STT Worker.
 * Communicates with `daybook-api` to transcribe audio using Groq Whisper Large-V3
 * and refine it with Google Gemini.
 *
 * Sequence:
 * 1. Checks internet & Firebase Authentication.
 * 2. Prepares WAV audio from recording chunks.
 * 3. Requests a presigned S3 upload URL from daybook-api (POST /api/recordings/upload-url).
 * 4. Uploads audio directly to Neon Object Storage via HTTP PUT.
 * 5. Dispatches transcription job to daybook-api (POST /api/recordings).
 * 6. Polls backend status until completed, then updates RecordingRepository.
 */
class SttWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val rec = RecordingRepository.get(id) ?: return Result.failure()

        setForeground(foregroundInfo("Menghubungkan ke server transkripsi…"))

        // Check Firebase user login
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            RecordingRepository.update(id) {
                it.copy(
                    status = "FAILED",
                    message = "Harap login ke akun Daybook terlebih dahulu untuk mentranskrip."
                )
            }
            return Result.failure()
        }

        try {
            // Collect and prepare audio files
            val chunkFiles = rec.chunks.sortedBy { it.index }
                .map { RecordingRepository.chunkFile(id, it.fileName) }
                .filter { it.exists() && it.length() > WavIO.HEADER_SIZE }

            if (chunkFiles.isEmpty()) {
                RecordingRepository.update(id) {
                    it.copy(status = "FAILED", message = "File audio rekaman tidak ditemukan.")
                }
                return Result.failure()
            }

            // If there's 1 chunk use it directly, otherwise stitch all chunks into one WAV
            val audioToUpload = if (chunkFiles.size == 1) {
                chunkFiles.first()
            } else {
                val combined = File(RecordingRepository.dir(id), "stitched_recording.wav")
                WavIO.stitchWavFiles(chunkFiles, combined)
            }

            val clientRecordingId = UUID.randomUUID().toString()
            val durationSec = (rec.chunks.sumOf { it.durationMs } / 1000).toInt().coerceAtLeast(1)

            // Step 1: Request upload URL
            RecordingRepository.update(id) {
                it.copy(status = "PROCESSING", message = "Meminta upload URL...")
            }
            setForeground(foregroundInfo("Menyiapkan upload audio…"))
            val uploadInfo = DaybookApiClient.createUploadUrl(applicationContext, clientRecordingId)

            // Step 2: Upload audio bytes to presigned Neon S3 URL
            RecordingRepository.update(id) {
                it.copy(status = "PROCESSING", message = "Mengunggah audio ke cloud storage...")
            }
            setForeground(foregroundInfo("Mengunggah audio ke storage…"))
            DaybookApiClient.uploadAudio(uploadInfo.uploadUrl, audioToUpload)

            // Step 3: Trigger processing job on backend
            RecordingRepository.update(id) {
                it.copy(status = "PROCESSING", message = "Memproses transkripsi (Groq Whisper Large-V3)...")
            }
            setForeground(foregroundInfo("Mentranskrip via Groq Whisper Large-V3…"))
            val jobId = DaybookApiClient.submitJob(
                context = applicationContext,
                audioObjectName = uploadInfo.audioObjectName,
                durationSeconds = durationSec,
                activityCode = "GENERAL_MEETING",
                language = "id"
            )

            // Step 4: Poll backend status until complete
            var attempts = 0
            val maxAttempts = 120 // ~6 minutes maximum wait time
            var completedJob: BackendJobStatus? = null

            while (attempts < maxAttempts) {
                delay(3000)
                attempts++

                val status = DaybookApiClient.getJobStatus(applicationContext, jobId)
                if (status.status.equals("Completed", ignoreCase = true)) {
                    completedJob = status
                    break
                } else if (status.status.equals("Failed", ignoreCase = true)) {
                    val err = status.errorMessage ?: "Transkripsi di server gagal."
                    RecordingRepository.update(id) {
                        it.copy(status = "FAILED", message = err)
                    }
                    return Result.failure()
                }

                val completedChunks = status.chunks.count { it.status.equals("Completed", ignoreCase = true) }
                val totalChunks = status.chunks.size
                val progressMsg = if (totalChunks > 0) {
                    "Mentranskrip bagian $completedChunks/$totalChunks..."
                } else {
                    "Memproses di server..."
                }
                RecordingRepository.update(id) { it.copy(message = progressMsg) }
                setForeground(foregroundInfo(progressMsg))
            }

            if (completedJob == null) {
                RecordingRepository.update(id) {
                    it.copy(status = "FAILED", message = "Waktu proses di server habis (timeout).")
                }
                return Result.failure()
            }

            // Step 5: Save high-quality results back into local RecordingRepository
            val fullRefined = completedJob.refinedTranscript.ifBlank { completedJob.rawTranscript }
            val momJson = completedJob.momJson

            if (completedJob.chunks.isNotEmpty()) {
                completedJob.chunks.forEach { backendChunk ->
                    val chunkText = backendChunk.refinedTranscript.ifBlank { backendChunk.rawTranscript }
                    val chunkIndex = backendChunk.sequence + 1
                    RecordingRepository.updateChunk(id, chunkIndex) { localChunk ->
                        localChunk.copy(
                            status = "TRANSCRIBED",
                            transcript = chunkText,
                            segments = backendChunk.segments
                        )
                    }
                }
            } else {
                // If chunks array was empty, update all local chunks with the main transcript
                val firstChunk = rec.chunks.firstOrNull()
                if (firstChunk != null) {
                    RecordingRepository.updateChunk(id, firstChunk.index) {
                        it.copy(
                            status = "TRANSCRIBED",
                            transcript = fullRefined
                        )
                    }
                }
            }

            RecordingRepository.update(id) { r ->
                r.copy(
                    status = "DONE",
                    mom = momJson,
                    message = "Transkripsi selesai (Groq Whisper Large-V3)"
                )
            }

            return Result.success()

        } catch (e: Exception) {
            val errorMsg = e.message ?: "Terjadi kesalahan jaringan atau server."
            RecordingRepository.update(id) {
                it.copy(
                    status = "FAILED",
                    message = "Transkripsi gagal: $errorMsg"
                )
            }
            return Result.failure()
        }
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        val channelId = CHANNEL_ID
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(channelId) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(channelId, "Transkripsi Cloud", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Daybook — Transkripsi Cloud")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(NOTIFY_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFY_ID, notif)
        }
    }

    companion object {
        fun enqueue(context: Context, recordingId: String) {
            val req = OneTimeWorkRequestBuilder<SttWorker>()
                .setInputData(workDataOf(KEY_ID to recordingId))
                .build()
            WorkManager.getInstance(context.applicationContext)
                .beginUniqueWork(UNIQUE_PREFIX + recordingId, ExistingWorkPolicy.KEEP, req)
                .enqueue()
        }

        const val KEY_ID = "id"
        const val UNIQUE_PREFIX = "stt_"
        private const val CHANNEL_ID = "daybook_stt"
        private const val NOTIFY_ID = 2
    }
}
