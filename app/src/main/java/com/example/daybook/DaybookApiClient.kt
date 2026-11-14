package com.example.daybook

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class UploadUrlResponse(
    val uploadUrl: String,
    val audioObjectName: String,
    val expiresAt: String?,
    val contentType: String
)

data class BackendChunk(
    val sequence: Int,
    val status: String,
    val rawTranscript: String,
    val refinedTranscript: String,
    val segments: List<TimedSegment>
)

data class BackendJobStatus(
    val id: String,
    val status: String, // Queued, Processing, NeedsReview, Completed, Failed
    val rawTranscript: String,
    val refinedTranscript: String,
    val momJson: String,
    val errorMessage: String?,
    val chunks: List<BackendChunk>
)

object DaybookApiClient {
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val WAV_MEDIA = "audio/wav".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    private fun getAuthToken(): String {
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw IllegalStateException("Harap login ke akun Daybook terlebih dahulu.")
        val task = user.getIdToken(false)
        val result = Tasks.await(task, 15, TimeUnit.SECONDS)
        return result.token ?: throw IllegalStateException("Gagal mengambil token otentikasi Firebase.")
    }

    /**
     * Step 1: Request presigned S3 upload URL from daybook-api
     */
    fun createUploadUrl(context: Context, clientRecordingId: String): UploadUrlResponse {
        val baseUrl = DaybookApiConfig.getBaseUrl(context)
        val token = getAuthToken()

        val jsonBody = JSONObject().apply {
            put("clientRecordingId", clientRecordingId)
            put("contentType", "audio/wav")
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api/recordings/upload-url")
            .addHeader("Authorization", "Bearer $token")
            .post(jsonBody.toRequestBody(JSON_MEDIA))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IOException("Gagal membuat upload URL (HTTP ${response.code}): $body")
            }
            val json = JSONObject(body)
            return UploadUrlResponse(
                uploadUrl = json.getString("uploadUrl"),
                audioObjectName = json.getString("audioObjectName"),
                expiresAt = json.optString("expiresAt", null),
                contentType = json.optString("contentType", "audio/wav")
            )
        }
    }

    /**
     * Step 2: Upload audio file bytes directly to the presigned S3 URL
     */
    fun uploadAudio(uploadUrl: String, file: File) {
        if (!file.exists() || file.length() == 0L) {
            throw IOException("File audio tidak ditemukan atau kosong: ${file.absolutePath}")
        }

        val requestBody = file.asRequestBody(WAV_MEDIA)
        val request = Request.Builder()
            .url(uploadUrl)
            .put(requestBody)
            .addHeader("Content-Type", "audio/wav")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: ""
                throw IOException("Gagal mengunggah audio ke storage (HTTP ${response.code}): $body")
            }
        }
    }

    /**
     * Step 3: Trigger the processing job on daybook-api
     */
    fun submitJob(
        context: Context,
        audioObjectName: String,
        durationSeconds: Int,
        activityCode: String = "GENERAL_MEETING",
        language: String = "id"
    ): String {
        val baseUrl = DaybookApiConfig.getBaseUrl(context)
        val token = getAuthToken()

        val jsonBody = JSONObject().apply {
            put("activityCode", activityCode)
            put("audioObjectName", audioObjectName)
            put("durationSeconds", durationSeconds)
            put("languageOverride", language)
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api/recordings")
            .addHeader("Authorization", "Bearer $token")
            .post(jsonBody.toRequestBody(JSON_MEDIA))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (response.code != 202 && !response.isSuccessful) {
                throw IOException("Gagal mengirim job transkripsi (HTTP ${response.code}): $body")
            }
            val json = JSONObject(body)
            return json.optString("id", json.optString("Id", ""))
        }
    }

    /**
     * Step 4: Poll job status and retrieve final transcript & MoM
     */
    fun getJobStatus(context: Context, jobId: String): BackendJobStatus {
        val baseUrl = DaybookApiConfig.getBaseUrl(context)
        val token = getAuthToken()

        val request = Request.Builder()
            .url("$baseUrl/api/recordings/$jobId")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IOException("Gagal membaca status job (HTTP ${response.code}): $body")
            }
            val json = JSONObject(body)
            val chunksArr = json.optJSONArray("chunks") ?: json.optJSONArray("Chunks") ?: JSONArray()
            val chunkList = mutableListOf<BackendChunk>()

            for (i in 0 until chunksArr.length()) {
                val c = chunksArr.getJSONObject(i)
                val seq = c.optInt("sequence", c.optInt("Sequence", i))
                val st = c.optString("status", c.optString("Status", ""))
                val raw = c.optString("rawTranscript", c.optString("RawTranscript", ""))
                val refined = c.optString("refinedTranscript", c.optString("RefinedTranscript", ""))
                val segsJsonStr = c.optString("sttSegmentsJson", c.optString("SttSegmentsJson", ""))
                val segs = parseSegments(segsJsonStr)

                chunkList.add(BackendChunk(seq, st, raw, refined, segs))
            }

            return BackendJobStatus(
                id = json.optString("id", json.optString("Id", jobId)),
                status = json.optString("status", json.optString("Status", "")),
                rawTranscript = json.optString("rawTranscript", json.optString("RawTranscript", "")),
                refinedTranscript = json.optString("refinedTranscript", json.optString("RefinedTranscript", "")),
                momJson = json.optString("momJson", json.optString("MomJson", "")),
                errorMessage = json.optString("errorMessage", json.optString("ErrorMessage", null)),
                chunks = chunkList
            )
        }
    }

    private fun parseSegments(jsonString: String): List<TimedSegment> {
        if (jsonString.isBlank()) return emptyList()
        val list = mutableListOf<TimedSegment>()
        try {
            val arr = JSONArray(jsonString)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val text = obj.optString("text", obj.optString("Text", "")).trim()
                if (text.isEmpty()) continue

                val startSec = obj.optDouble("startSeconds", obj.optDouble("start", 0.0))
                val endSec = obj.optDouble("endSeconds", obj.optDouble("end", startSec + 1.0))

                val startMs = (startSec * 1000).toLong()
                val endMs = (endSec * 1000).toLong()
                list.add(TimedSegment(startMs, endMs, text))
            }
        } catch (_: Exception) {
            // best effort parsing
        }
        return list
    }
}
