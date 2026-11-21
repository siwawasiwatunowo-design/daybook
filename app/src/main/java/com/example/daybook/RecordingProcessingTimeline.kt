package com.example.daybook

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun RecordingProcessingTimeline(recording: Recording) {
    val steps = listOf(
        "Audio tersimpan" to "Rekaman selesai dan audio siap diproses",
        "Persiapan audio" to "Menyiapkan file audio dan membagi bagian rekaman",
        "Transkripsi" to "Mengubah audio menjadi teks",
        "Refinement" to "Memperbaiki transkrip tanpa mengubah sumber aslinya",
        "Analisis konten" to "Menyusun konteks dan ringkasan pembicaraan",
        "Speaking Coach" to "Mengevaluasi enam dimensi komunikasi",
        "Finalisasi" to "Menyimpan hasil dan menyiapkan tampilan"
    )
    val current = recording.processingStep.coerceIn(1, 7)
    val isAttention = recording.processingStage == "EvaluationNeedsAttention"
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Progress pemrosesan", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("$current/7 langkah", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            LinearProgressIndicator(progress = { current / 7f }, modifier = Modifier.fillMaxWidth())
            if (recording.processingDetail.isNotBlank()) Text(recording.processingDetail,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            steps.forEachIndexed { index, pair ->
                val title = pair.first
                val description = pair.second
                val step = index + 1
                val completed = step < current || (recording.processingStage == "Completed" && step <= current)
                val active = step == current && !completed && !isAttention
                val skipped = step == 6 && recording.coachingStatus == "NotRequested"
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(when {
                        skipped -> "—"
                        completed -> "✓"
                        active -> "●"
                        isAttention && step == 6 -> "!"
                        else -> "○"
                    }, color = when {
                        completed -> MaterialTheme.colorScheme.primary
                        isAttention && step == 6 -> MaterialTheme.colorScheme.error
                        active -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(26.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (active || completed) FontWeight.SemiBold else FontWeight.Normal)
                        Text((when {
                            skipped -> "Tidak diperlukan"
                            completed -> "Selesai"
                            isAttention && step == 6 -> "Perlu perhatian"
                            active -> "Sedang diproses"
                            else -> "Menunggu"
                        }) + " • " + description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun SpeakingCoachReviewPanel(context: Context, recording: Recording) {
    var history by remember(recording.backendJobId) { mutableStateOf<List<SpeakingCoachEvaluationRecord>>(emptyList()) }
    var historyError by remember { mutableStateOf<String?>(null) }
    var showFeedbackDialog by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var requestError by remember { mutableStateOf<String?>(null) }
    var expandedVersion by remember { mutableIntStateOf(-1) }

    suspend fun refreshHistory() {
        if (recording.backendJobId.isBlank()) return
        try {
            history = withContext(Dispatchers.IO) {
                DaybookApiClient.getSpeakingCoachEvaluations(context, recording.backendJobId)
            }
            historyError = null
        } catch (e: Exception) {
            historyError = e.message ?: "Riwayat evaluasi belum dapat dimuat."
        }
    }

    LaunchedEffect(recording.backendJobId) { refreshHistory() }
    LaunchedEffect(recording.backendJobId, recording.coachingStatus) {
        val jobId = recording.backendJobId
        if (jobId.isBlank()) return@LaunchedEffect
        while (recording.coachingStatus in setOf("Queued", "Processing", "RetryPending")) {
            try {
                val status = withContext(Dispatchers.IO) { DaybookApiClient.getJobStatus(context, jobId) }
                RecordingRepository.update(recording.id) { current ->
                    current.copy(
                        coaching = status.speakingCoachResult.ifBlank { current.coaching },
                        coachingStatus = status.speakingCoachStatus,
                        coachingError = status.speakingCoachError.orEmpty(),
                        processingStage = status.processingStage,
                        processingStep = status.processingStep,
                        processingTotalSteps = status.processingTotalSteps,
                        processingDetail = status.processingDetail
                    )
                }
                if (status.speakingCoachStatus !in setOf("Queued", "Processing", "RetryPending")) break
                delay(4000)
            } catch (_: Exception) { delay(6000) }
        }
        refreshHistory()
    }

    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("SOLO PRACTICE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (recording.practicePrompt.isNotBlank()) Text(recording.practicePrompt,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Evaluasi Speaking Coach", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            when (recording.coachingStatus) {
                "Queued", "Processing" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("AI sedang mengevaluasi transkrip…", style = MaterialTheme.typography.bodySmall)
                }
                "RetryPending" -> Text("Gemini sedang tidak tersedia. Daybook akan mencoba kembali otomatis tanpa mengulang transkripsi.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                "Failed" -> Text("Evaluasi belum berhasil: " + recording.coachingError.ifBlank { "Silakan coba evaluasi ulang." },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (recording.coaching.isNotBlank()) Text(recording.coaching, style = MaterialTheme.typography.bodyMedium)
            else if (recording.coachingStatus == "NotRequested") Text("Evaluasi akan tersedia setelah transkrip diproses.",
                style = MaterialTheme.typography.bodySmall)
            requestError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { feedback = ""; requestError = null; showFeedbackDialog = true },
                enabled = recording.backendJobId.isNotBlank() &&
                    recording.coachingStatus !in setOf("Queued", "Processing")) {
                Text(if (history.isEmpty()) "Minta evaluasi" else "Evaluate Again")
            }
            if (history.isNotEmpty()) {
                HorizontalDivider()
                Text("Riwayat evaluasi (" + history.size + ")", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                history.sortedByDescending { it.version }.forEach { item ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("Versi " + item.version, fontWeight = FontWeight.SemiBold)
                                Text(item.status, style = MaterialTheme.typography.labelSmall,
                                    color = if (item.status == "Completed") MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (item.feedback.isNotBlank()) Text("Feedback: " + item.feedback,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (item.status == "Failed") Text(item.error,
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            if (item.result.isNotBlank()) {
                                TextButton(onClick = {
                                    expandedVersion = if (expandedVersion == item.version) -1 else item.version
                                }) { Text(if (expandedVersion == item.version) "Tutup hasil" else "Lihat hasil") }
                                if (expandedVersion == item.version) Text(item.result, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            } else if (historyError != null) {
                Text(historyError!!, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showFeedbackDialog) {
        AlertDialog(
            onDismissRequest = { if (!submitting) showFeedbackDialog = false },
            title = { Text("Evaluasi ulang Speaking Coach") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Beri tahu bagian mana yang kurang tepat. Feedback opsional; Daybook tetap menggunakan transkrip asli.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = feedback, onValueChange = { if (it.length <= 2000) feedback = it },
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 5,
                        label = { Text("Feedback untuk AI (opsional)") },
                        placeholder = { Text("Contoh: Penilaian reasoning kurang sesuai karena saya sudah menjelaskan penyebabnya…") })
                    Text(feedback.length.toString() + "/2000", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(enabled = !submitting, onClick = {
                    submitting = true
                    requestError = null
                    val note = feedback.trim()
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        try {
                            val version = withContext(Dispatchers.IO) {
                                DaybookApiClient.requestSpeakingCoachEvaluation(context, recording.backendJobId, note)
                            }
                            RecordingRepository.update(recording.id) {
                                it.copy(coachingStatus = "Queued", coachingError = "",
                                    processingStage = "SpeakingEvaluation", processingStep = 6,
                                    processingTotalSteps = 7, processingDetail = "Menunggu evaluasi versi " + version)
                            }
                            showFeedbackDialog = false
                            feedback = ""
                            refreshHistory()
                        } catch (e: Exception) {
                            requestError = e.message ?: "Tidak dapat mengirim permintaan evaluasi ulang."
                        } finally { submitting = false }
                    }
                }) { if (submitting) CircularProgressIndicator(Modifier.size(16.dp)) else Text("Kirim") }
            },
            dismissButton = { TextButton(enabled = !submitting, onClick = { showFeedbackDialog = false }) { Text("Batal") } }
        )
    }
}
