package com.example.daybook

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gemini text-only client (§9). The user's OWN API key is stored locally with
 * EncryptedSharedPreferences — it is NEVER hardcoded in the APK. Only the timed
 * transcript TEXT (+ marks) is sent to Google, never audio. The caller must
 * show a consent + preview screen before invoking generateMom.
 *
 * On any failure (no key, network error, parse error) it returns null so the
 * caller falls back to the rule-based MomExtractor.
 */
object GeminiClient {
    private const val PREFS = "daybook_secure"
    private const val KEY_GEMINI = "gemini_key"
    private const val MODEL = "gemini-2.5-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    private fun prefs(context: Context) = try {
        val mk = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, PREFS, mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) { null }

    fun getKey(context: Context): String? =
        prefs(context)?.getString(KEY_GEMINI, null)?.takeIf { it.isNotBlank() }

    fun setKey(context: Context, key: String) {
        prefs(context)?.edit()?.putString(KEY_GEMINI, key.trim())?.apply()
    }

    fun clearKey(context: Context) {
        prefs(context)?.edit()?.remove(KEY_GEMINI)?.apply()
    }

    fun hasKey(context: Context): Boolean = !getKey(context).isNullOrBlank()

    /**
     * Generate a flow-based MoM from the transcript text. Returns parsed MoM,
     * or null on failure (caller falls back to rule-based). Must be called off
     * the main thread.
     */
    fun generateMom(
        context: Context,
        transcript: String,
        marks: List<Mark>,
        title: String
    ): MoM? {
        val key = getKey(context) ?: return null
        if (transcript.isBlank()) return null
        val prompt = buildPrompt(transcript, marks, title)

        val body = JSONObject()
            .put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            .toString()

        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder()
            .url("$ENDPOINT?key=$key")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val root = JSONObject(resp.body?.string().orEmpty())
                val text = root.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                    ?.optString("text", "") ?: ""
                if (text.isBlank()) null else MoM.fromJson(text)
            }
        } catch (_: Exception) { null }
    }

    private fun buildPrompt(transcript: String, marks: List<Mark>, title: String): String {
        val marksDesc = if (marks.isEmpty()) "(tidak ada tanda)"
        else marks.joinToString("\n") {
            val mm = it.offsetMs / 60000; val ss = (it.offsetMs % 60000) / 1000
            "- ${"%02d:%02d".format(mm, ss)} (${it.type})"
        }
        return """
Kamu asisten meeting. Dari transkrip otomatis (bahasa Indonesia, tanpa nama pembicara, bisa salah dengar) buat notulen berbasis ALUR.

Judul: $title
Tanda user: 
$marksDesc

Transkrip berwaktu:
$transcript

Aturan:
- JANGAN mengarang. Kalau tidak jelas tulis "perlu konfirmasi".
- JANGAN menebak siapa yang bicara (tidak ada pemisahan pembicara).
- Untuk setiap tanda user, jelaskan alur obrolan di sekitarnya di sekitarTanda.

Output JSON dengan struktur persis:
{"alur":[{"range":"MM:SS–MM:SS","topik":"topik segmen waktu itu"}],"keputusan":["..."],"actionItems":["..."],"perluKonfirmasi":["..."],"sekitarTanda":["..."]}
Hanya JSON, tanpa penjelasan tambahan.
        """.trimIndent()
    }
}
