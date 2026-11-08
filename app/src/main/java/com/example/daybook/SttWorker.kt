package com.example.daybook

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import org.vosk.Model

/**
 * Sequential STT worker (§7). One worker per recording (beginUniqueWork KEEP),
 * Vosk model loaded ONCE and reused across chunks. Chunks are transcribed in
 * order; a failed chunk is marked FAILED and the worker continues with the
 * next (audio is never lost). setForeground keeps the system from killing it
 * after ~10 minutes.
 *
 * After all chunks: recording status becomes DONE (or PARTIAL/FAILED), and MoM
 * generation is offered on demand from the result screen (consent-gated, §8).
 */
class SttWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val rec = RecordingRepository.get(id) ?: return Result.failure()

        setForeground(foregroundInfo("Mentranskrip…"))
        val modelDir = try {
            LocalTranscriber.ensureModel(applicationContext)
        } catch (e: Exception) {
            // Model download failed (no internet on first use). Retry later.
            RecordingRepository.update(id) { it.copy(status = "FAILED", message = "Gagal unduh model STT: ${e.message}") }
            return Result.retry()
        }

        val model = Model(modelDir.absolutePath)
        try {
            var ok = false
            var fail = false
            rec.chunks.forEach { chunk ->
                if (chunk.status == "TRANSCRIBED") return@forEach
                RecordingRepository.setChunkStatus(id, chunk.index, "TRANSCRIBING")
                setForeground(foregroundInfo("Mentranskrip chunk ${chunk.index}/${rec.chunks.size}"))
                try {
                    val wav = RecordingRepository.chunkFile(id, chunk.fileName)
                    val segs = LocalTranscriber.transcribeTimed(model, wav)
                    val text = segs.joinToString(" ") { it.text }
                    RecordingRepository.updateChunk(id, chunk.index) {
                        it.copy(segments = segs, transcript = text, status = "TRANSCRIBED")
                    }
                    ok = true
                } catch (e: Exception) {
                    RecordingRepository.setChunkStatus(id, chunk.index, "FAILED")
                    fail = true
                }
            }
            val status = when {
                fail && !ok -> "FAILED"
                fail -> "PARTIAL"
                else -> "DONE"
            }
            RecordingRepository.update(id) { it.copy(status = status) }
            return Result.success()
        } finally {
            model.close()
        }
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        val channelId = CHANNEL_ID
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Transkripsi", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Daybook — transkripsi")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        // Android 14+: declare the dataSync foreground service type explicitly.
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(NOTIFY_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFY_ID, notif)
        }
    }

    companion object {
        /** One unique, sequential chain per recording (KEEP: a second request is ignored). */
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
