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
    val chunks: List<BackendChunk>,
    val speakingCoachStatus: String = "NotRequested",
    val speakingCoachResult: String = "",
    val speakingCoachError: String? = null,
    val processingStage: String = "AudioUploaded",
    val processingStep: Int = 1,
    val processingTotalSteps: Int = 7,
    val processingDetail: String = ""
)

data class SpeakingCoachEvaluationRecord(
    val id: String,
    val version: Int,
    val feedback: String,
    val status: String,
    val result: String,
    val error: String,
    val attemptCount: Int,
    val createdAt: String,
    val completedAt: String
)

data class PracticeWeekProgress(
    val weekStart: String,
    val sessions: Int,
    val attempts: Int,
    val practiceSeconds: Int,
    val evaluatedAttempts: Int
)

data class PracticeEvaluationSummary(
    val evaluationId: String,
    val recordingJobId: String,
    val title: String,
    val attemptNumber: Int,
    val version: Int,
    val result: String,
    val createdAt: String
)

data class PracticeFocus(
    val issueCode: String, val dimension: String, val title: String, val explanation: String,
    val evidence: String, val improvementAdvice: String, val occurrenceCount: Int,
    val comparableAttempts: Int, val positiveEvidenceAttempts: Int, val status: String, val taskType: String
)
data class PracticeNextPractice(
    val title: String, val instructions: String, val successCriteria: List<String>, val taskType: String
)
data class PracticeImprovementData(
    val hasEvidence: Boolean, val message: String, val currentFocus: PracticeFocus?,
    val nextPractice: PracticeNextPractice?
)

data class PracticeProgressData(
    val totalSessions: Int,
    val totalAttempts: Int,
    val totalPracticeSeconds: Long,
    val evaluatedAttempts: Int,
    val pendingEvaluations: Int,
    val lastPracticeAt: String,
    val weeks: List<PracticeWeekProgress>,
    val latestEvaluations: List<PracticeEvaluationSummary>,
    val improvement: PracticeImprovementData
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


    fun reviewNote(context: Context, title: String, content: String): String {
        val baseUrl = DaybookApiConfig.getBaseUrl(context)
        val token = getAuthToken()
        val body = JSONObject().put("title", title).put("content", content).toString()
        val request = Request.Builder()
            .url("$baseUrl/api/notes/ai-review")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful)
                throw IOException("AI Review gagal (HTTP ${response.code}): $responseBody")
            return JSONObject(responseBody).optString("review")
                .ifBlank { throw IOException("AI Review mengembalikan hasil kosong.") }
        }
    }

    fun assistNote(context: Context, title: String, content: String, action: String, instruction: String? = null): String {
        val baseUrl = DaybookApiConfig.getBaseUrl(context)
        val token = getAuthToken()
        val body = JSONObject().put("title", title).put("content", content)
            .put("action", action).put("instruction", instruction).toString()
        val request = Request.Builder()
            .url("$baseUrl/api/notes/ai-assist")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("AI Assistant gagal (HTTP ${response.code}): $responseBody")
            return JSONObject(responseBody).optString("result")
                .ifBlank { throw IOException("AI Assistant mengembalikan hasil kosong.") }
        }
    }



    /** Account-scoped aggregate for the Speaking Practice progress dashboard. */
    fun getPracticeProgress(context: Context): PracticeProgressData {
        val baseUrl = DaybookApiConfig.getBaseUrl(context)
        val token = getAuthToken()
        val request = Request.Builder()
            .url("$baseUrl/api/practice/progress")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("ngrok-skip-browser-warning", "true")
            .get().build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful)
                throw IOException("Gagal mengambil progress Practice (HTTP ${response.code}): $body")
            val json = JSONObject(body)
            val weeksJson = json.optJSONArray("weeks") ?: JSONArray()
            val weeks = (0 until weeksJson.length()).map { i ->
                val item = weeksJson.getJSONObject(i)
                PracticeWeekProgress(
                    weekStart = item.optString("weekStart"),
                    sessions = item.optInt("sessions"),
                    attempts = item.optInt("attempts"),
                    practiceSeconds = item.optInt("practiceSeconds"),
                    evaluatedAttempts = item.optInt("evaluatedAttempts")
                )
            }
            val evalJson = json.optJSONArray("latestEvaluations") ?: JSONArray()
            val evaluations = (0 until evalJson.length()).map { i ->
                val item = evalJson.getJSONObject(i)
                PracticeEvaluationSummary(
                    evaluationId = item.optString("evaluationId"),
                    recordingJobId = item.optString("recordingJobId"),
                    title = item.optString("title"),
                    attemptNumber = item.optInt("attemptNumber", 1),
                    version = item.optInt("version", 1),
                    result = item.optString("result"),
                    createdAt = item.optString("createdAt")
                )
            }
            val improvementJson = json.optJSONObject("improvement")
            val focusJson = improvementJson?.optJSONObject("currentFocus")
            val focus = focusJson?.let { PracticeFocus(
                issueCode = it.optString("issueCode"), dimension = it.optString("dimension"),
                title = it.optString("title"), explanation = it.optString("explanation"),
                evidence = it.optString("evidence"), improvementAdvice = it.optString("improvementAdvice"),
                occurrenceCount = it.optInt("occurrenceCount"), comparableAttempts = it.optInt("comparableAttempts"),
                positiveEvidenceAttempts = it.optInt("positiveEvidenceAttempts"), status = it.optString("status"),
                taskType = it.optString("taskType")
            ) }
            val exerciseJson = improvementJson?.optJSONObject("nextPractice")
            val exercise = exerciseJson?.let { item ->
                val criteria = item.optJSONArray("successCriteria") ?: JSONArray()
                PracticeNextPractice(item.optString("title"), item.optString("instructions"),
                    (0 until criteria.length()).map { criteria.optString(it) }, item.optString("taskType"))
            }
            val improvement = PracticeImprovementData(
                hasEvidence = improvementJson?.optBoolean("hasEvidence") ?: false,
                message = improvementJson?.optString("message").orEmpty(),
                currentFocus = focus, nextPractice = exercise
            )
            return PracticeProgressData(
                totalSessions = json.optInt("totalSessions"),
                totalAttempts = json.optInt("totalAttempts"),
                totalPracticeSeconds = json.optLong("totalPracticeSeconds"),
                evaluatedAttempts = json.optInt("evaluatedAttempts"),
                pendingEvaluations = json.optInt("pendingEvaluations"),
                lastPracticeAt = json.optString("lastPracticeAt"),
                weeks = weeks,
                latestEvaluations = evaluations,
                improvement = improvement
            )
        }
    }

    fun compareSpeakingAttempts(context: Context, earlierTranscript: String, laterTranscript: String, topic: String = ""): String {
        val baseUrl = DaybookApiConfig.getBaseUrl(context)
        val token = getAuthToken()
        val body = JSONObject()
            .put("earlierTranscript", earlierTranscript)
            .put("laterTranscript", laterTranscript)
            .put("topic", topic)
            .toString()
        val request = Request.Builder().url("$baseUrl/api/speaking/compare")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(body.toRequestBody(JSON_MEDIA)).build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Perbandingan Speaking Coach gagal (HTTP ${response.code}): $responseBody")
            return JSONObject(responseBody).optString("comparison")
                .ifBlank { throw IOException("Speaking Coach tidak mengembalikan hasil perbandingan.") }
        }
    }

    fun coachSpeaking(context: Context, transcript: String): String {
        val baseUrl = DaybookApiConfig.getBaseUrl(context)
        val token = getAuthToken()
        val body = JSONObject().put("transcript", transcript).toString()
        val request = Request.Builder().url("$baseUrl/api/speaking/coach")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(body.toRequestBody(JSON_MEDIA)).build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Speaking Coach gagal (HTTP ${response.code}): $responseBody")
            return JSONObject(responseBody).optString("coaching")
                .ifBlank { throw IOException("Speaking Coach mengembalikan hasil kosong.") }
        }
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
            .addHeader("ngrok-skip-browser-warning", "true")
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
        language: String = "id",
        enableSpeakingCoach: Boolean = false,
        speakingCoachTopic: String? = null,
        practiceTitle: String? = null,
        practiceSessionId: String? = null,
        practiceAttemptNumber: Int = 1,
        practicePrompt: String? = null,
        practiceMode: String? = null,
        sourceNoteId: String? = null
    ): String {
        val baseUrl = DaybookApiConfig.getBaseUrl(context)
        val token = getAuthToken()

        val jsonBody = JSONObject().apply {
            put("activityCode", activityCode)
            put("audioObjectName", audioObjectName)
            put("durationSeconds", durationSeconds)
            put("languageOverride", language)
            put("enableSpeakingCoach", enableSpeakingCoach)
            if (enableSpeakingCoach && !speakingCoachTopic.isNullOrBlank()) {
                put("speakingCoachTopic", speakingCoachTopic)
                put("practiceTitle", practiceTitle.orEmpty())
                put("practiceSessionId", practiceSessionId.orEmpty())
                put("practiceAttemptNumber", practiceAttemptNumber.coerceAtLeast(1))
                put("practicePrompt", practicePrompt.orEmpty())
                put("practiceMode", practiceMode ?: "SOLO")
                put("sourceNoteId", sourceNoteId.orEmpty())
            }
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api/recordings")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("ngrok-skip-browser-warning", "true")
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


    /** Request the backend to retry an existing failed/needs-review recording job. */
    fun retryRecording(context: Context, recordingJobId: String) {
        val baseUrl = DaybookApiConfig.getBaseUrl(context)
        val token = getAuthToken()
        val request = Request.Builder()
            .url("$baseUrl/api/recordings/$recordingJobId/retry")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("ngrok-skip-browser-warning", "true")
            .post("{}".toRequestBody(JSON_MEDIA))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Gagal meminta pemrosesan ulang (HTTP ${response.code}): $body")
            }
        }
    }

    /**
     * Rehydrate cloud-backed Solo Practice history after reinstall or on a new device.
     * The endpoint is account-scoped; no local audio files are required to show results.
     */
    fun getPracticeHistory(context: Context): List<Recording> {
        val baseUrl = DaybookApiConfig.getBaseUrl(context)
        val token = getAuthToken()
        val request = Request.Builder()
            .url("$baseUrl/api/practice/history")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("ngrok-skip-browser-warning", "true")
            .get().build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Gagal mengambil histori Practice (HTTP " + response.code + "): " + body)
            val array = JSONArray(body)
            return (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val id = item.optString("id")
                val chunksArray = item.optJSONArray("chunks") ?: JSONArray()
                val chunks = (0 until chunksArray.length()).map { ci ->
                    val chunk = chunksArray.getJSONObject(ci)
                    val transcript = chunk.optString("refinedTranscript")
                        .ifBlank { chunk.optString("rawTranscript") }
                    Chunk(
                        index = chunk.optInt("sequence", ci) + 1,
                        fileName = "",
                        durationMs = 0L,
                        status = if (transcript.isNotBlank()) "TRANSCRIBED" else "FAILED",
                        transcript = transcript,
                        segments = parseSegments(chunk.optString("sttSegmentsJson"))
                    )
                }
                val createdAt = runCatching {
                    java.time.Instant.parse(item.optString("createdAt")).toEpochMilli()
                }.getOrDefault(System.currentTimeMillis())
                val apiStatus = item.optString("status")
                val localStatus = when (apiStatus.lowercase()) {
                    "completed" -> "DONE"
                    "needsreview" -> "PARTIAL"
                    "failed" -> "FAILED"
                    else -> "PROCESSING"
                }
                Recording(
                    id = id,
                    title = item.optString("title", "Practice — Speaking Practice"),
                    createdAt = createdAt,
                    dirName = id,
                    status = localStatus,
                    chunks = chunks,
                    mom = item.optString("momJson").takeUnless { it == "null" }.orEmpty(),
                    sessionType = "PRACTICE",
                    practicePrompt = item.optString("practicePrompt"),
                    coaching = item.optString("speakingCoachResult").takeUnless { it == "null" }.orEmpty(),
                    practiceSessionId = item.optString("practiceSessionId").ifBlank { id },
                    attemptNumber = item.optInt("attemptNumber", 1).coerceAtLeast(1),
                    practiceMode = item.optString("practiceMode", "SOLO"),
                    sourceNoteId = item.optString("sourceNoteId"),
                    coachingStatus = item.optString("speakingCoachStatus", "NotRequested"),
                    coachingError = item.optString("speakingCoachError").takeUnless { it == "null" }.orEmpty(),
                    backendJobId = id,
                    processingStage = item.optString("processingStage", "Completed"),
                    processingStep = item.optInt("processingStep", 7),
                    processingTotalSteps = item.optInt("processingTotalSteps", 7),
                    processingDetail = item.optString("processingDetail"),
                    cloudOnly = true,
                    cloudDurationMs = item.optInt("durationSeconds", 0).coerceAtLeast(0) * 1000L
                )
            }
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
            .addHeader("ngrok-skip-browser-warning", "true")
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
                chunks = chunkList,
                speakingCoachStatus = json.optString("speakingCoachStatus",
                    json.optString("SpeakingCoachStatus", "NotRequested")),
                speakingCoachResult = json.optString("speakingCoachResult",
                    json.optString("SpeakingCoachResult", "")),
                speakingCoachError = json.optString("speakingCoachError",
                    json.optString("SpeakingCoachError", null)),
                processingStage = json.optString("processingStage", json.optString("ProcessingStage", "AudioUploaded")),
                processingStep = json.optInt("processingStep", json.optInt("ProcessingStep", 1)),
                processingTotalSteps = json.optInt("processingTotalSteps", json.optInt("ProcessingTotalSteps", 7)),
                processingDetail = json.optString("processingDetail", json.optString("ProcessingDetail", ""))
            )
        }
    }

    fun requestSpeakingCoachEvaluation(context: Context, recordingJobId: String, feedback: String): Int {
        val baseUrl = DaybookApiConfig.getBaseUrl(context)
        val token = getAuthToken()
        val body = JSONObject().put("feedback", feedback).toString()
        val request = Request.Builder()
            .url("$baseUrl/api/recordings/$recordingJobId/speaking-coach/evaluations")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Evaluasi ulang gagal (HTTP ${response.code}): $responseBody")
            return JSONObject(responseBody).optInt("version", 0)
        }
    }

    fun getSpeakingCoachEvaluations(context: Context, recordingJobId: String): List<SpeakingCoachEvaluationRecord> {
        val baseUrl = DaybookApiConfig.getBaseUrl(context)
        val token = getAuthToken()
        val request = Request.Builder()
            .url("$baseUrl/api/recordings/$recordingJobId/speaking-coach/evaluations")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("ngrok-skip-browser-warning", "true")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Gagal mengambil riwayat evaluasi (HTTP ${response.code}): $responseBody")
            val array = JSONArray(responseBody)
            return (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                SpeakingCoachEvaluationRecord(
                    id = item.optString("id"),
                    version = item.optInt("version"),
                    feedback = item.optString("feedback").takeUnless { it == "null" }.orEmpty(),
                    status = item.optString("status"),
                    result = item.optString("result").takeUnless { it == "null" }.orEmpty(),
                    error = item.optString("error").takeUnless { it == "null" }.orEmpty(),
                    attemptCount = item.optInt("attemptCount"),
                    createdAt = item.optString("createdAt"),
                    completedAt = item.optString("completedAt")
                )
            }
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
