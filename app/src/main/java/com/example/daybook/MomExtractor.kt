package com.example.daybook

/**
 * Rule-based Minutes-of-Meeting extractor (local, offline, no model).
 *
 * This is deliberately conservative: it never invents participants, decisions,
 * PIC, or deadlines. When something is not clearly detectable from the
 * transcript it says "tidak disebutkan" / "perlu konfirmasi", per the brief.
 *
 * Quality is POC-grade (keyword heuristics in Bahasa Indonesia). The optional
 * cloud (Gemini) fallback can refine this when a key is configured.
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

    /** Returns a formatted MoM string, or "" if the transcript is empty. */
    fun extract(transcript: String, title: String): String {
        val t = transcript.trim()
        if (t.isBlank()) return ""

        val sentences = t.split(Regex("(?<=[.!?\\n])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val decisions = mutableListOf<String>()
        val actions = mutableListOf<String>()
        val summaryPoints = mutableListOf<String>()

        sentences.forEach { s ->
            val low = s.lowercase()
            when {
                DECISION_CUES.any { low.contains(it) } -> decisions.add(s)
                ACTION_CUES.any { low.contains(it) } -> actions.add(s)
                else -> summaryPoints.add(s)
            }
        }

        val safeTitle = title.ifBlank { "Meeting" }

        return buildString {
            append("**MINUTES OF MEETING**\n")
            append("**Judul:** ").append(safeTitle).append("\n\n")

            append("**RINGKASAN DISKUSI:**\n")
            if (summaryPoints.isEmpty()) {
                append("- (transkrip terlalu pendek untuk dirangkum otomatis)\n")
            } else {
                summaryPoints.take(10).forEach { append("- ").append(it).append("\n") }
            }

            append("\n**KEPUTUSAN YANG DIAMBIL:**\n")
            if (decisions.isEmpty()) append("- tidak disebutkan\n")
            else decisions.forEach { append("- ").append(it).append("\n") }

            append("\n**ACTION ITEMS:**\n")
            if (actions.isEmpty()) append("- tidak disebutkan\n")
            else actions.forEach { append("- ").append(it).append("\n") }

            append("\n_PIC & deadline: tidak disebutkan / perlu konfirmasi_\n")
            append("\n_Catatan: MoM lokal (otomatis, rule-based). Mohon dicek & diedit sesuai kebutuhan._\n")
        }
    }

    /** Whether the extracted MoM looks weak (nothing actionable detected). */
    fun looksWeak(mom: String): Boolean =
        mom.isBlank() || (mom.contains("tidak disebutkan") && !momContainsAction(mom))

    private fun momContainsAction(mom: String): Boolean {
        val idx = mom.indexOf("**ACTION ITEMS:**")
        if (idx < 0) return false
        val section = mom.substring(idx)
        return !section.contains("- tidak disebutkan")
    }
}
