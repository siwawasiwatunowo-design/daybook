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
            val isSoloPractice = rec.sessionType == "PRACTICE" && rec.practiceMode == "SOLO"
            val durationSec = (rec.chunks.sumOf { it.durationMs } / 1000).toInt().coerceAtLeast(1)

            // If a backend job already exists, retry that job in place. This avoids
            // uploading audio or creating a second job when only server processing failed.
            val jobId = if (rec.backendJobId.isNotBlank()) {
                RecordingRepository.update(id) {
                    it.copy(status = "PROCESSING", message = "Meminta server melanjutkan pemrosesan…")
                }
                setForeground(foregroundInfo("Mencoba ulang pemrosesan di server…"))
                try {
                    DaybookApiClient.retryRecording(applicationContext, rec.backendJobId)
                } catch (retryError: Exception) {
                    // A local timeout can happen while the server is still working.
                    // If the existing job is already queued/processing/completed, just
                    // resume polling instead of showing a misleading retry failure.
                    val current = runCatching {
                        DaybookApiClient.getJobStatus(applicationContext, rec.backendJobId)
                    }.getOrNull()
                    if (current == null || current.status !in setOf("Queued", "Processing", "Completed", "NeedsReview")) {
                        throw retryError
                    }
                }
                rec.backendJobId
            } else {
                // No job was created yet (for example, the API rejected job creation).
                // The local WAV is still intact, so safely submit it again from the saved audio.
                val chunkFiles = rec.chunks.sortedBy { it.index }
                    .map { RecordingRepository.chunkFile(id, it.fileName) }
                    .filter { it.exists() && it.length() > WavIO.HEADER_SIZE }

                if (chunkFiles.isEmpty()) {
                    RecordingRepository.update(id) {
                        it.copy(status = "FAILED", message = "File audio lokal tidak ditemukan. Rekaman ini tidak bisa dikirim ulang dari perangkat.")
                    }
                    return Result.failure()
                }

                val audioToUpload = if (chunkFiles.size == 1) {
                    chunkFiles.first()
                } else {
                    val combined = File(RecordingRepository.dir(id), "stitched_recording.wav")
                    WavIO.stitchWavFiles(chunkFiles, combined)
                }

                // Stable client ID means a retry uses the same storage object path.
                val clientRecordingId = UUID.nameUUIDFromBytes("${user.uid}:$id".toByteArray()).toString()
                RecordingRepository.update(id) {
                    it.copy(status = "PROCESSING", message = "Meminta upload URL…")
                }
                setForeground(foregroundInfo("Menyiapkan upload audio…"))
                val uploadInfo = DaybookApiClient.createUploadUrl(applicationContext, clientRecordingId)

                RecordingRepository.update(id) {
                    it.copy(status = "PROCESSING", message = "Mengunggah audio tersimpan ke cloud…")
                }
                setForeground(foregroundInfo("Mengunggah audio ke storage…"))
                DaybookApiClient.uploadAudio(uploadInfo.uploadUrl, audioToUpload)

                RecordingRepository.update(id) {
                    it.copy(status = "PROCESSING", message = "Mengirim rekaman ke server…")
                }
                setForeground(foregroundInfo("Mengirim rekaman ke server…"))
                val createdJobId = DaybookApiClient.submitJob(
                    context = applicationContext,
                    audioObjectName = uploadInfo.audioObjectName,
                    durationSeconds = durationSec,
                    activityCode = "GENERAL_MEETING",
                    language = "id",
                    enableSpeakingCoach = isSoloPractice,
                    speakingCoachTopic = if (isSoloPractice) rec.practicePrompt else null,
                    practiceTitle = if (isSoloPractice) rec.title else null,
                    practiceSessionId = if (isSoloPractice) rec.practiceSessionId else null,
                    practiceAttemptNumber = rec.attemptNumber,
                    practicePrompt = if (isSoloPractice) rec.practicePrompt else null,
                    practiceMode = if (isSoloPractice) rec.practiceMode else null,
                    sourceNoteId = if (isSoloPractice) rec.sourceNoteId else null
                )
                if (isSoloPractice) {
                    RecordingRepository.update(id) {
                        it.copy(coachingStatus = "Queued", coachingError = "")
                    }
                }
                RecordingRepository.update(id) { it.copy(backendJobId = createdJobId) }
                createdJobId
            }

            // Step 4: Poll backend status until transcript processing is complete
            var attempts = 0
            val maxAttempts = if (isSoloPractice) 180 else 120 // Practice allows up to 9 minutes; regular recordings stay unchanged.
            var completedJob: BackendJobStatus? = null

            while (attempts < maxAttempts) {
                delay(3000)
                attempts++

                val status = DaybookApiClient.getJobStatus(applicationContext, jobId)
                RecordingRepository.update(id) { local ->
                    local.copy(
                        processingStage = status.processingStage,
                        processingStep = status.processingStep,
                        processingTotalSteps = status.processingTotalSteps,
                        processingDetail = status.processingDetail,
                        coachingStatus = status.speakingCoachStatus,
                        coachingError = status.speakingCoachError.orEmpty()
                    )
                }
                // NeedsReview is terminal: the transcript is saved, but quality checks
                // or optional AI refinement/MoM require review. Do not poll until timeout.
                if (status.status.equals("Completed", ignoreCase = true) ||
                    status.status.equals("NeedsReview", ignoreCase = true)) {
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
                    message = if (completedJob.status.equals("NeedsReview", ignoreCase = true))
                        "Transkrip selesai, tetapi perlu ditinjau. " + (completedJob.errorMessage ?: "")
                    else
                        "Transkripsi selesai (Groq Whisper Large-V3)",
                    coaching = completedJob.speakingCoachResult,
                    coachingStatus = completedJob.speakingCoachStatus,
                    coachingError = completedJob.speakingCoachError.orEmpty()
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
