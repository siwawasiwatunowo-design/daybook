package com.example.daybook

import android.content.Intent
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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
                    clipboard.setText(AnnotatedString(plainText(title, segs, fallback)))
                    Toast.makeText(context, "Transkrip disalin", Toast.LENGTH_SHORT).show()
                },
                enabled = hasText
            ) { Text("Salin") }
            OutlinedButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, title)
                        putExtra(Intent.EXTRA_TEXT, plainText(title, segs, fallback))
                    }
                    context.startActivity(Intent.createChooser(send, "Bagikan transkrip"))
                },
                enabled = hasText
            ) { Text("Bagikan") }
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
            "Transkrip otomatis (Whisper, offline) — bisa ada salah dengar, terutama nama dan istilah teknis. " +
                "Ketuk satu baris untuk memutar audio dari momen itu.",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()

        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
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
                                body = noteHtml(title, segs, fallback),
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

/** Plain text for copy/share: title, blank line, then "[mm:ss] text" per segment. */
private fun plainText(title: String, segs: List<TimedSegment>, fallback: String): String = buildString {
    append(title).append("\n\n")
    if (segs.isEmpty()) append(fallback)
    else segs.forEach { append('[').append(formatClock(it.startMs)).append("] ").append(it.text).append('\n') }
}.trim()

/** Note body is HTML (the Notes editor loads it with setHtml). Escape text, use <br> for newlines. */
private fun noteHtml(title: String, segs: List<TimedSegment>, fallback: String): String =
    TextUtils.htmlEncode(plainText(title, segs, fallback)).replace("\n", "<br>")
