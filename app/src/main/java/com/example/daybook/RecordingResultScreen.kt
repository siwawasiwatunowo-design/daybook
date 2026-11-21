package com.example.daybook

import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Transcript result screen for one recording: timed segments, Copy, Share, and
 * "Simpan ke Notes". Transcripts are local-only (RecordingRepository) until the
 * user explicitly saves one to Notes, which stores the TEXT in Firestore like any
 * other note. Audio never leaves the phone.
 */
@Composable
fun RecordingResultScreen(recordingId: String, vm: VM, onClose: () -> Unit) {
    BackHandler(onBack = onClose)

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val recs by RecordingRepository.recordings.collectAsState()
    val rec = recs.firstOrNull { it.id == recordingId }

    // All attempts belonging to the same practice session, including legacy V1 sessions.
    val sessionKey = rec?.practiceSessionId?.ifBlank { rec.id }.orEmpty()
    val sessionAttempts = recs.filter {
        it.sessionType == "PRACTICE" &&
            (it.practiceSessionId.ifBlank { it.id }) == sessionKey
    }.sortedBy { it.attemptNumber }

    // Recording was deleted while this screen was open.
    LaunchedEffect(rec == null) { if (rec == null) onClose() }
    if (rec == null) return

    val segs = remember(rec) { RecordingRepository.combinedSegments(recordingId) }
    val fallback = remember(rec) { RecordingRepository.fullTranscript(recordingId) }
    val hasText = segs.isNotEmpty() || fallback.isNotBlank()
    val title = rec.title.ifBlank { "Meeting" }
    val failed = rec.chunks.filter { it.status == "FAILED" }
    val pending = rec.chunks.filter { it.status != "TRANSCRIBED" && it.status != "FAILED" }

    var showSaveDialog by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var coaching by remember { mutableStateOf<String?>(null) }
    var coachingLoading by remember { mutableStateOf(false) }
    var coachingError by remember { mutableStateOf<String?>(null) }
    var comparison by remember { mutableStateOf<String?>(null) }
    var comparisonLoading by remember { mutableStateOf(false) }
    var comparisonError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Solo practice is evaluated automatically as soon as its transcript is ready.
    LaunchedEffect(rec.id, rec.status, hasText, rec.coaching) {
        if (rec.sessionType == "PRACTICE" && rec.status in setOf("DONE", "PARTIAL") && hasText && rec.coaching.isBlank() && !coachingLoading) {
            coachingLoading = true
            coachingError = null
            try {
                val speechText = if (segs.isEmpty()) fallback else segs.joinToString("\n") { it.text }
                val feedback = withContext(Dispatchers.IO) {
                    DaybookApiClient.coachSpeaking(context, speechText)
                }
                RecordingRepository.setCoaching(rec.id, feedback)
            } catch (e: Exception) {
                coachingError = e.message ?: "Evaluasi latihan belum berhasil."
            } finally {
                coachingLoading = false
            }
        }
    }

    // Audio playback: all chunks as one timeline, same clock as the transcript segments.
    val player = remember(rec.id, rec.chunks.size) {
        RecordingPlayer(
            rec.chunks.map { RecordingRepository.chunkFile(rec.id, it.fileName) },
            rec.chunks.map { it.durationMs }
        )
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player, player.playing) {
        while (player.playing) { player.tick(); delay(250) }
    }
    LaunchedEffect(player.error) {
        player.error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); player.clearError() }
    }
    var dragging by remember { mutableStateOf(false) }
    var sliderPos by remember { mutableFloatStateOf(0f) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Kembali") }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium)
                Text(formatClock(rec.chunks.sumOf { it.durationMs }) + " • transkrip",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    coachingError = null
                    coachingLoading = true
                    scope.launch {
                        try {
                            val speechText = if (segs.isEmpty()) fallback else segs.joinToString("\n") { it.text }
                            coaching = withContext(Dispatchers.IO) { DaybookApiClient.coachSpeaking(context, speechText) }
                        } catch (e: Exception) {
                            coachingError = e.message ?: "Gagal menjalankan Speaking Coach."
                        } finally { coachingLoading = false }
                    }
                },
                enabled = hasText && !coachingLoading
            ) {
                if (coachingLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Speaking Coach")
            }
        }

        if (rec.sessionType == "PRACTICE") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Attempt ${rec.attemptNumber} • ${sessionAttempts.size} percobaan",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (sessionAttempts.size >= 2) {
                    OutlinedButton(
                        enabled = !comparisonLoading,
                        onClick = {
                            val pair = sessionAttempts.takeLast(2)
                            val earlier = RecordingRepository.fullTranscript(pair.first().id)
                            val later = RecordingRepository.fullTranscript(pair.last().id)
                            if (earlier.isBlank() || later.isBlank()) {
                                comparisonError = "Kedua percobaan harus memiliki transkrip sebelum dibandingkan."
                            } else {
                                comparisonLoading = true
                                comparisonError = null
                                scope.launch {
                                    try {
                                        comparison = withContext(Dispatchers.IO) {
                                            DaybookApiClient.compareSpeakingAttempts(
                                                context, earlier, later,
                                                pair.last().practicePrompt.ifBlank { pair.last().title }
                                            )
                                        }
                                    } catch (e: Exception) {
                                        comparisonError = e.message ?: "Perbandingan belum berhasil."
                                    } finally {
                                        comparisonLoading = false
                                    }
                                }
                            }
                        }
                    ) {
                        if (comparisonLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("Bandingkan 2 terbaru")
                    }
                }
                OutlinedButton(
                    enabled = rec.status in setOf("DONE", "PARTIAL") && hasText &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                    onClick = {
                        val newId = System.currentTimeMillis().toString()
                        val intent = Intent(context, RecordingService::class.java).apply {
                            action = RecordingService.ACTION_START
                            putExtra(RecordingService.EXTRA_ID, newId)
                            putExtra(RecordingService.EXTRA_TITLE, rec.title)
                            putExtra(RecordingService.EXTRA_SESSION_TYPE, "PRACTICE")
                            putExtra(RecordingService.EXTRA_PRACTICE_PROMPT, rec.practicePrompt)
                            putExtra(RecordingService.EXTRA_PRACTICE_SESSION_ID, sessionKey)
                            putExtra(RecordingService.EXTRA_ATTEMPT_NUMBER, (sessionAttempts.maxOfOrNull { it.attemptNumber } ?: rec.attemptNumber) + 1)
                            putExtra(RecordingService.EXTRA_PRACTICE_MODE, rec.practiceMode)
                            putExtra(RecordingService.EXTRA_SOURCE_NOTE_ID, rec.sourceNoteId)
                        }
                        onClose()
                        context.startForegroundService(intent)
                    }
                ) { Text("Ulangi latihan") }
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(plainText(title, segs, fallback, rec.mom)))
                    Toast.makeText(context, "Transkrip disalin", Toast.LENGTH_SHORT).show()
                },
                enabled = hasText
            ) { Text("Salin") }
            OutlinedButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, title)
                        putExtra(Intent.EXTRA_TEXT, plainText(title, segs, fallback, rec.mom))
                    }
                    context.startActivity(Intent.createChooser(send, "Bagikan transkrip"))
                },
                enabled = hasText
            ) { Text("Bagikan") }
            OutlinedButton(
                onClick = {
                    try {
                        PublicRecordingExporter.shareRecordingAudio(context, rec)
                    } catch (ex: Exception) {
                        Toast.makeText(context, ex.message ?: "Gagal menyiapkan file WAV", Toast.LENGTH_LONG).show()
                    }
                },
                enabled = rec.chunks.isNotEmpty()
            ) { Text("WAV") }
            Button(onClick = { showSaveDialog = true }, enabled = hasText && !saved) {
                Text(if (saved) "Tersimpan ✓" else "Simpan ke Notes")
            }
        }

        if (player.totalMs > 0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { player.toggle() }) {
                    Icon(
                        if (player.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (player.playing) "Jeda" else "Putar"
                    )
                }
                Slider(
                    value = if (dragging) sliderPos else player.positionMs.toFloat().coerceIn(0f, player.totalMs.toFloat()),
                    onValueChange = { dragging = true; sliderPos = it },
                    onValueChangeFinished = { player.seek(sliderPos.toLong()); dragging = false },
                    valueRange = 0f..player.totalMs.toFloat(),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${formatClock(if (dragging) sliderPos.toLong() else player.positionMs)} / ${formatClock(player.totalMs)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Text(
            "Transkrip cloud (Groq Whisper Large-V3). " +
                "Ketuk satu baris untuk memutar audio dari momen itu.",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()

        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (rec.sessionType == "PRACTICE") item {
                Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("SOLO PRACTICE", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        if (rec.practicePrompt.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(rec.practicePrompt, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Evaluasi Speaking Coach", fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        when {
                            rec.coaching.isNotBlank() -> Text(rec.coaching, style = MaterialTheme.typography.bodyMedium)
                            coachingLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("Mengevaluasi struktur, kejelasan, konteks, reasoning, keringkasan, dan kesimpulan…",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            coachingError != null -> Text(coachingError!!, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                            else -> Text("Evaluasi akan muncul setelah transkrip tersedia.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (rec.mom.isNotBlank()) item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("📋 Notulen & Ringkasan (Gemini MoM)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(formatMomText(rec.mom), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (failed.isNotEmpty()) item {
                Text("Bagian ${failed.joinToString { it.index.toString() }} gagal ditranskrip.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }
            if (pending.isNotEmpty()) item {
                Text("Bagian ${pending.joinToString { it.index.toString() }} belum selesai diproses.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }
            if (!hasText) item {
                Text("Belum ada teks. Bisa jadi tidak ada ucapan yang terdeteksi, atau transkripsi belum selesai.",
                    Modifier.padding(vertical = 16.dp))
            } else if (segs.isEmpty()) item {
                Text(fallback)
            } else items(segs) { s ->
                val active = (player.playing || player.positionMs > 0L) &&
                    player.positionMs >= s.startMs && player.positionMs < s.endMs
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable(enabled = player.totalMs > 0) { player.playFrom(s.startMs) }
                        .padding(vertical = 6.dp)
                ) {
                    Text("[${formatClock(s.startMs)}]", Modifier.width(72.dp),
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    Text(s.text, Modifier.weight(1f))
                }
            }
        }
    }


    coaching?.let { feedback ->
        AlertDialog(
            onDismissRequest = { coaching = null },
            title = { Text("Speaking Coach") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    Text("Evaluasi berbasis transkrip. Untuk meeting multi-speaker, atribusi ucapan ke diri lo belum tentu akurat.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Text(feedback, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.save(Entry(type = "note", title = "Speaking Coach - $title",
                        body = TextUtils.htmlEncode("Speaking Coach\n\n$feedback").replace("\n", "<br>"),
                        date = Instant.ofEpochMilli(rec.createdAt).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                        tag = "Learning"))
                    Toast.makeText(context, "Evaluasi disimpan ke Notes", Toast.LENGTH_SHORT).show()
                    coaching = null
                }) { Text("Simpan ke Notes") }
            },
            dismissButton = { TextButton(onClick = { coaching = null }) { Text("Tutup") } }
        )
    }
    coachingError?.let { message ->
        AlertDialog(onDismissRequest = { coachingError = null }, title = { Text("Speaking Coach") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { coachingError = null }) { Text("OK") } })
    }

    comparison?.let { report ->
        AlertDialog(
            onDismissRequest = { comparison = null },
            title = { Text("Perbandingan Speaking Practice") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    Text("Membandingkan dua percobaan terbaru dalam topik latihan yang sama.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Text(report, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { comparison = null }) { Text("Tutup") } }
        )
    }
    comparisonError?.let { message ->
        AlertDialog(
            onDismissRequest = { comparisonError = null },
            title = { Text("Perbandingan Speaking Practice") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { comparisonError = null }) { Text("OK") } }
        )
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Simpan ke Notes?") },
            text = {
                Text("Teks transkrip akan disimpan sebagai catatan baru di tab Notes dan ikut tersinkron " +
                    "ke akun Firebase kamu. Audio tetap hanya di HP ini.")
            },
            confirmButton = {
                Button({
                    showSaveDialog = false
                    try {
                        vm.save(
                            Entry(
                                type = "note",
                                title = "Transkrip - $title",
                                body = noteHtml(title, segs, fallback, rec.mom),
                                date = Instant.ofEpochMilli(rec.createdAt)
                                    .atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                                tag = "Journal"
                            )
                        )
                        saved = true
                        Toast.makeText(context, "Tersimpan di Notes", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Gagal menyimpan: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Simpan") }
            },
            dismissButton = { TextButton({ showSaveDialog = false }) { Text("Batal") } }
        )
    }
}

private fun formatMomText(jsonStr: String): String {
    try {
        val clean = jsonStr.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = JSONObject(clean)
        val sb = StringBuilder()
        if (obj.has("overview")) {
            sb.append(obj.optString("overview")).append("\n\n")
        }
        if (obj.has("decisions")) {
            val dec = obj.optJSONArray("decisions")
            if (dec != null && dec.length() > 0) {
                sb.append("• Keputusan:\n")
                for (i in 0 until dec.length()) sb.append("  - ").append(dec.getString(i)).append("\n")
                sb.append("\n")
            }
        }
        if (obj.has("actionItems")) {
            val act = obj.optJSONArray("actionItems")
            if (act != null && act.length() > 0) {
                sb.append("• Tindak Lanjut / Action Items:\n")
                for (i in 0 until act.length()) {
                    val item = act.optJSONObject(i)
                    if (item != null) {
                        val task = item.optString("task")
                        val owner = item.optString("owner")
                        val deadline = item.optString("deadline")
                        sb.append("  - $task")
                        if (owner.isNotBlank() && owner != "null") sb.append(" (PIC: $owner)")
                        if (deadline.isNotBlank() && deadline != "null") sb.append(" [Deadline: $deadline]")
                        sb.append("\n")
                    } else {
                        sb.append("  - ").append(act.getString(i)).append("\n")
                    }
                }
                sb.append("\n")
            }
        }
        if (obj.has("discussionPoints")) {
            val disc = obj.optJSONArray("discussionPoints")
            if (disc != null && disc.length() > 0) {
                sb.append("• Poin Diskusi:\n")
                for (i in 0 until disc.length()) sb.append("  - ").append(disc.getString(i)).append("\n")
            }
        }
        val res = sb.toString().trim()
        if (res.isNotBlank()) return res
    } catch (_: Exception) {}
    return jsonStr
}

/** Plain text for copy/share: title, MoM summary (if any), blank line, then "[mm:ss] text" per segment. */
private fun plainText(title: String, segs: List<TimedSegment>, fallback: String, momJson: String = ""): String = buildString {
    append(title).append("\n\n")
    if (momJson.isNotBlank()) {
        append("=== NOTULEN & RINGKASAN (MoM) ===\n")
        append(formatMomText(momJson)).append("\n\n")
        append("=== TRANSKRIP LENGKAP ===\n")
    }
    if (segs.isEmpty()) append(fallback)
    else segs.forEach { append('[').append(formatClock(it.startMs)).append("] ").append(it.text).append('\n') }
}.trim()

/** Note body is HTML (the Notes editor loads it with setHtml). Escape text, use <br> for newlines. */
private fun noteHtml(title: String, segs: List<TimedSegment>, fallback: String, momJson: String = ""): String =
    TextUtils.htmlEncode(plainText(title, segs, fallback, momJson)).replace("\n", "<br>")
