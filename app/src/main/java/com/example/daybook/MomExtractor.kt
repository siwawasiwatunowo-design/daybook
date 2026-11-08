package com.example.daybook

import org.json.JSONArray
import org.json.JSONObject

/** One time-range topic in the conversation flow (§8). */
data class AlurItem(val range: String, val topik: String)

/**
 * Structured Minutes-of-Meeting, focused on conversation flow rather than a
 * formal notulen. Used by both the rule-based fallback and the Gemini path,
 * so the result screen renders the same way regardless of source. (§8)
 */
data class MoM(
    val alur: List<AlurItem> = emptyList(),       // topik per segmen waktu
    val keputusan: List<String> = emptyList(),    // + alasan jika terdeteksi
    val actionItems: List<String> = emptyList(),
    val perluKonfirmasi: List<String> = emptyList(), // hal yang tidak jelas
    val sekitarTanda: List<String> = emptyList()  // penjelasan alur di sekitar tiap ⭐
) {
    fun toJson(): String {
        val alurArr = JSONArray()
        alur.forEach { alurArr.put(JSONObject().put("range", it.range).put("topik", it.topik)) }
        return JSONObject()
            .put("alur", alurArr)
            .put("keputusan", JSONArray(keputusan))
            .put("actionItems", JSONArray(actionItems))
            .put("perluKonfirmasi", JSONArray(perluKonfirmasi))
            .put("sekitarTanda", JSONArray(sekitarTanda))
            .toString()
    }

    /** Markdown rendering (used by Save as Note + export). */
    fun toMarkdown(): String = buildString {
        append("**Alur obrolan**\n")
        if (alur.isEmpty()) append("- (belum terdeteksi)\n")
        else alur.forEach { append("- **${it.range}** — ${it.topik}\n") }
        append("\n**Keputusan**\n")
        if (keputusan.isEmpty()) append("- perlu konfirmasi\n")
        else keputusan.forEach { append("- $it\n") }
        append("\n**Action item**\n")
        if (actionItems.isEmpty()) append("- tidak disebutkan\n")
        else actionItems.forEach { append("- $it\n") }
        append("\n**Perlu konfirmasi**\n")
        if (perluKonfirmasi.isEmpty()) append("- -\n")
        else perluKonfirmasi.forEach { append("- $it\n") }
        if (sekitarTanda.isNotEmpty()) {
            append("\n**Sekitar penanda**\n")
            sekitarTanda.forEach { append("- $it\n") }
        }
        append("\n_Dibuat dari transkrip otomatis, bisa ada salah._\n")
    }

    val isEmpty: Boolean get() =
        alur.isEmpty() && keputusan.isEmpty() && actionItems.isEmpty() &&
            perluKonfirmasi.isEmpty() && sekitarTanda.isEmpty()

    companion object {
        /** Parse JSON (from Gemini or our own toJson). Strips ```json fences. */
        fun fromJson(s: String): MoM? = try {
            val clean = s.trim().removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val o = JSONObject(clean)
            val alur = mutableListOf<AlurItem>()
            o.optJSONArray("alur")?.let { a ->
                for (i in 0 until a.length()) {
                    val oo = a.getJSONObject(i)
                    alur.add(AlurItem(oo.optString("range"), oo.optString("topik")))
                }
            }
            fun arr(k: String): List<String> =
                o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            MoM(alur, arr("keputusan"), arr("actionItems"), arr("perluKonfirmasi"), arr("sekitarTanda"))
        } catch (_: Exception) { null }
    }
}

/**
 * Rule-based MoM extractor (local, offline, no model) — the offline fallback
 * when Gemini is unavailable/declined (§8). Deliberately conservative: it never
 * invents participants, decisions, PIC, or deadlines. When something is unclear
 * it says "perlu konfirmasi". Quality is POC-grade.
 */
object MomExtractor {

    private val DECISION_CUES = listOf(
        "putus", "sepakat", "setuju", "disepakati", "keputusan",
        "disetujui", "menyetujui", "diputuskan"
    )
    private val ACTION_CUES = listOf(
        "akan", "harus", "perlu", "menindaklanjuti", "tindak lanjut",
        "bertanggung jawab", "tanggung jawab", "mengurus", "mengerjakan",
        "menyiapkan", "mengirim", "membuat", "melanjutkan", "mengumpulkan"
    )

    /** Build a MoM from timed segments + marks. Times are global (ms from start). */
    fun extract(segments: List<TimedSegment>, marks: List<Mark>, title: String): MoM {
        if (segments.isEmpty()) return MoM()

        val alur = buildAlur(segments)
        val keputusan = mutableListOf<String>()
        val actions = mutableListOf<String>()
        val perluKonfirmasi = mutableListOf<String>()

        segments.forEach { seg ->
            val low = seg.text.lowercase()
            when {
                DECISION_CUES.any { low.contains(it) } -> keputusan.add(seg.text)
                ACTION_CUES.any { low.contains(it) } -> actions.add(seg.text)
                seg.text.contains("?") -> perluKonfirmasi.add(seg.text)
            }
        }

        val sekitarTanda = mutableListOf<String>()
        marks.forEach { m ->
            val nearby = segments.filter { seg ->
                val mid = (seg.startMs + seg.endMs) / 2
                kotlin.math.abs(mid - m.offsetMs) <= 30_000
            }.joinToString(" ") { it.text }
            if (nearby.isNotBlank()) {
                val label = if (m.type == "BINGUNG") "bingung" else "penting"
                val mm = m.offsetMs / 60000
                val ss = (m.offsetMs % 60000) / 1000
                sekitarTanda.add("[${"%02d:%02d".format(mm, ss)} • $label] $nearby")
            }
        }

        val safeTitle = title.ifBlank { "Meeting" }
        // Title is not stored in MoM fields; the UI shows the recording title.
        @Suppress("UNUSED_VARIABLE") val t = safeTitle
        return MoM(alur, keputusan, actions, perluKonfirmasi, sekitarTanda)
    }

    /** Group segments into ~5-minute windows; label each with the first utterance. */
    private fun buildAlur(segments: List<TimedSegment>): List<AlurItem> {
        if (segments.isEmpty()) return emptyList()
        val windowMs = 5L * 60 * 1000
        val out = mutableListOf<AlurItem>()
        var winStart = 0L
        var current = mutableListOf<TimedSegment>()
        fun flush() {
            if (current.isEmpty()) return
            val end = current.maxOf { it.endMs }
            val topik = current.take(3).joinToString(" ") { it.text }
                .replace("\n", " ").trim().let { if (it.length > 100) it.take(100) + "…" else it }
            out.add(AlurItem(fmtRange(winStart, end), if (topik.isBlank()) "(tidak terdeteksi)" else topik))
            current = mutableListOf()
        }
        segments.forEach { seg ->
            if (seg.startMs >= winStart + windowMs) {
                flush()
                winStart = (seg.startMs / windowMs) * windowMs
            }
            current.add(seg)
        }
        flush()
        return out
    }

    private fun fmtRange(startMs: Long, endMs: Long): String =
        "${fmt(startMs)}–${fmt(endMs)}"

    private fun fmt(ms: Long): String = "%02d:%02d".format(ms / 60000, (ms % 60000) / 1000)
}
