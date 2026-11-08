package com.example.daybook

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object BackendSyncPreferences {
    private const val PREFS = "daybook_prefs"
    private const val KEY_AUTO_UPLOAD = "backend_auto_upload_enabled"
    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_UPLOAD, false)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_UPLOAD, enabled).apply()
    }
}

class BackendUploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES).readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES).build()

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        var rec = RecordingRepository.get(id) ?: return Result.failure()
        if (!rec.backendOptIn) return Result.success()
        val merged = File(RecordingRepository.dir(id), "backend-upload.wav")
        try {
            setForeground(foregroundInfo("Menyiapkan upload rekaman…"))
            var objectName = rec.backendAudioObjectName
            if (!rec.backendUploadComplete) {
                RecordingRepository.update(id) { it.copy(backendStatus = "UPLOADING", backendMessage = "") }
                WavIO.mergeWavChunks(rec.chunks.sortedBy { it.index }.map {
                    RecordingRepository.chunkFile(id, it.fileName)
                }, merged)
                val info = postJson("/api/recordings/upload-url",
                    JSONObject().put("clientRecordingId", id).put("contentType", "audio/wav"))
                val uploadUrl = info.getString("uploadUrl")
                objectName = info.getString("audioObjectName")
                RecordingRepository.update(id) { it.copy(backendAudioObjectName = objectName, backendStatus = "UPLOADING") }
                val put = Request.Builder().url(uploadUrl).put(merged.asRequestBody("audio/wav".toMediaType()))
                    .header("Content-Type", "audio/wav").build()
                http.newCall(put).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IllegalStateException("Upload Neon gagal (HTTP ${response.code}): ${body.take(500)}")
                }
                RecordingRepository.update(id) { it.copy(backendUploadComplete = true, backendStatus = "QUEUED") }
                rec = RecordingRepository.get(id) ?: rec
            }
            rec = RecordingRepository.get(id) ?: rec
            var jobId = rec.backendJobId
            if (jobId.isBlank()) {
                val create = JSONObject().put("activityCode", "GENERAL_MEETING").put("audioObjectName", objectName)
                    .put("durationSeconds", (rec.chunks.sumOf { it.durationMs } / 1000L).toInt())
                    .put("languageOverride", "id").put("clientRecordingId", id)
                val accepted = postJson("/api/recordings", create)
                jobId = accepted.getString("id")
                val savedId = jobId
                RecordingRepository.update(id) { it.copy(backendJobId = savedId, backendStatus = accepted.optString("status", "QUEUED")) }
            }
            for (attempt in 0 until 72) {
                if (isStopped) return Result.retry()
                val job = getJson("/api/recordings/$jobId")
                val status = job.optString("status", "Processing")
                val raw = job.optString("rawTranscript", "")
                val refined = job.optString("refinedTranscript", "")
                val mom = job.optString("momJson", "")
                val message = job.optString("errorMessage", "")
                RecordingRepository.update(id) { it.copy(backendStatus = status.uppercase(), backendMessage = message) }
                when (status.lowercase()) {
                    "completed", "needsreview" -> {
                        if (raw.isBlank() && refined.isBlank()) throw IllegalStateException("Backend selesai tetapi transkrip kosong.")
                        RecordingRepository.update(id) {
                            it.copy(backendStatus = status.uppercase(), backendRawTranscript = raw,
                                backendRefinedTranscript = refined, backendMomJson = mom, backendMessage = message)
                        }
                        return Result.success()
                    }
                    "failed" -> throw IllegalStateException(message.ifBlank { "Backend gagal memproses rekaman." })
                }
                setForeground(foregroundInfo("Backend memproses rekaman…"))
                delay(5_000)
            }
            RecordingRepository.update(id) { it.copy(backendStatus = "PROCESSING", backendMessage = "Masih diproses; akan dilanjutkan otomatis.") }
            return Result.retry()
        } catch (e: Exception) {
            val message = e.message ?: "Kesalahan upload/backend."
            if (runAttemptCount >= MAX_RETRIES) {
                RecordingRepository.update(id) { it.copy(backendStatus = "FAILED", backendMessage = message) }
                SttWorker.enqueue(applicationContext, id)
                return Result.failure()
            }
            RecordingRepository.update(id) { it.copy(backendStatus = "RETRYING", backendMessage = message) }
            return Result.retry()
        } finally {
            try { merged.delete() } catch (_: Exception) {}
            try { File(merged.parentFile, merged.name + ".tmp").delete() } catch (_: Exception) {}
        }
    }

    private fun token(forceRefresh: Boolean = false): String {
        val user = FirebaseAuth.getInstance().currentUser ?: throw IllegalStateException("Login Firebase diperlukan untuk mengirim rekaman.")
        return Tasks.await(user.getIdToken(forceRefresh)).token ?: throw IllegalStateException("Firebase tidak memberikan ID token.")
    }
    private fun executeAuthorized(build: (String) -> Request): String {
        var response = http.newCall(build(token(false))).execute()
        if (response.code == 401) {
            response.close()
            response = http.newCall(build(token(true))).execute()
        }
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IllegalStateException("Backend HTTP ${it.code}: ${body.take(700)}")
            return body
        }
    }
    private fun postJson(path: String, json: JSONObject): JSONObject {
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        return JSONObject(executeAuthorized { bearer ->
            Request.Builder().url(apiUrl(path)).header("Authorization", "Bearer $bearer").post(body).build()
        })
    }
    private fun getJson(path: String) = JSONObject(executeAuthorized { bearer ->
        Request.Builder().url(apiUrl(path)).header("Authorization", "Bearer $bearer").get().build()
    })
    private fun apiUrl(path: String) = BuildConfig.DAYBOOK_API_BASE_URL.trimEnd('/') + path
    private fun foregroundInfo(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Upload rekaman", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID).setContentTitle("Daybook — backend")
            .setContentText(text).setSmallIcon(R.mipmap.ic_launcher).setOngoing(true).setOnlyAlertOnce(true).build()
        return if (Build.VERSION.SDK_INT >= 34) ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, notification)
    }
    companion object {
        private const val KEY_ID = "recording_id"
        private const val UNIQUE_PREFIX = "backend_upload_"
        private const val CHANNEL_ID = "daybook_backend"
        private const val NOTIFICATION_ID = 3
        private const val MAX_RETRIES = 6
        fun enqueue(context: Context, recordingId: String) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = OneTimeWorkRequestBuilder<BackendUploadWorker>().setInputData(workDataOf(KEY_ID to recordingId))
                .setConstraints(constraints).setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(UNIQUE_PREFIX + recordingId, ExistingWorkPolicy.KEEP, request)
        }
    }
}
