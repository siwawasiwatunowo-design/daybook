package com.example.daybook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun PracticeProgressScreen(
    onBack: () -> Unit,
    onOpenRecording: (String) -> Unit,
    onStartPractice: (String, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<PracticeProgressData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        loading = true
        error = null
        try {
            progress = withContext(Dispatchers.IO) {
                DaybookApiClient.getPracticeProgress(context)
            }
        } catch (e: Exception) {
            error = e.message ?: "Progress belum dapat dimuat."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Kembali") }
            Column(Modifier.weight(1f)) {
                Text("Progress", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Perkembangan Speaking Practice", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { scope.launch { refresh() } }, enabled = !loading) {
                Icon(Icons.Default.Refresh, "Refresh")
            }
        }

        when {
            loading && progress == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null && progress == null -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Progress belum bisa dimuat", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { scope.launch { refresh() } }) { Text("Coba lagi") }
            }
            progress != null -> {
                val data = progress!!
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text("Ringkasan latihan", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text(
                            if (data.lastPracticeAt.isBlank()) "Belum ada aktivitas latihan."
                            else "Terakhir latihan: ${formatProgressDate(data.lastPracticeAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    item {
                        val improvement = data.improvement
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
                        )) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Fokus perbaikan saat ini", style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold)
                                if (improvement.currentFocus == null) {
                                    Text(improvement.message.ifBlank { "Belum ada evaluasi terstruktur yang cukup untuk menentukan fokus." },
                                        style = MaterialTheme.typography.bodyMedium)
                                } else {
                                    val focus = improvement.currentFocus
                                    Text(focus.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("${progressDimensionLabel(focus.dimension)} • ${progressStatusLabel(focus.status)}",
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(focus.explanation, style = MaterialTheme.typography.bodySmall)
                                    if (focus.evidence.isNotBlank()) Text("Bukti: “${focus.evidence}”",
                                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                    Text("Muncul pada ${focus.occurrenceCount} latihan sejenis dari ${focus.comparableAttempts} percobaan yang dapat dibandingkan.",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(focus.improvementAdvice, style = MaterialTheme.typography.bodySmall)
                                    Text(improvement.message, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (data.improvement.patterns.isNotEmpty()) item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Pola komunikasi yang teramati", style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Pola ini dihitung dari temuan evaluasi pada latihan yang berbeda. Ini adalah petunjuk untuk refleksi, bukan label permanen tentang kemampuanmu.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                data.improvement.patterns.forEachIndexed { index, pattern ->
                                    if (index > 0) HorizontalDivider()
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(pattern.title, style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium)
                                            Text(
                                                "${progressDimensionLabel(pattern.dimension)} • ${pattern.taskType.replace('_', ' ')}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            "${pattern.occurrences}×",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (data.improvement.nextPractice != null) item {
                        val exercise = data.improvement.nextPractice!!
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Latihan berikutnya", style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold)
                                Text(exercise.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(exercise.instructions, style = MaterialTheme.typography.bodyMedium)
                                if (exercise.successCriteria.isNotEmpty()) {
                                    Text("Yang perlu diperhatikan", style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold)
                                    exercise.successCriteria.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                                }
                                Button(onClick = { onStartPractice(exercise.title, exercise.instructions) },
                                    modifier = Modifier.fillMaxWidth()) { Text("Gunakan latihan ini") }
                                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                                    Text("Lewati dan pilih latihan lain")
                                }
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProgressMetricCard("Sesi", data.totalSessions.toString(), Modifier.weight(1f))
                            ProgressMetricCard("Percobaan", data.totalAttempts.toString(), Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProgressMetricCard("Waktu latihan", formatClock(data.totalPracticeSeconds * 1000), Modifier.weight(1f))
                            ProgressMetricCard("Evaluasi selesai", "${data.evaluatedAttempts}/${data.totalAttempts}", Modifier.weight(1f))
                        }
                    }
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Aktivitas 8 minggu terakhir", style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold)
                                if (data.weeks.all { it.attempts == 0 }) {
                                    Text("Aktivitas mingguan akan muncul setelah kamu menyelesaikan latihan.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    val maxAttempts = data.weeks.maxOf { it.attempts }.coerceAtLeast(1)
                                    Row(
                                        Modifier.fillMaxWidth().height(126.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.Bottom
                                    ) {
                                        data.weeks.forEach { week ->
                                            Column(
                                                Modifier.weight(1f).fillMaxHeight(),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Bottom
                                            ) {
                                                Text(week.attempts.toString(), style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Spacer(Modifier.height(4.dp))
                                                Box(
                                                    Modifier.fillMaxWidth(0.68f)
                                                        .height((72f * week.attempts / maxAttempts).coerceAtLeast(if (week.attempts > 0) 6f else 2f).dp)
                                                        .background(
                                                            if (week.attempts > 0) MaterialTheme.colorScheme.primary
                                                            else MaterialTheme.colorScheme.surfaceVariant,
                                                            RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)
                                                        )
                                                )
                                                Spacer(Modifier.height(6.dp))
                                                Text(week.weekStart.takeLast(2), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                    Text("Angka di atas batang = jumlah percobaan. Label bawah = tanggal mulai minggu (UTC).",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Enam area komunikasi", style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold)
                                Text("Setiap evaluasi meninjau enam dimensi ini. Daybook tidak mengubah feedback kualitatif menjadi skor angka yang belum didukung data.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val dimensions = listOf(
                                    "Structure" to "Struktur",
                                    "Clarity" to "Kejelasan",
                                    "Context" to "Konteks",
                                    "Reasoning" to "Penalaran",
                                    "Conciseness" to "Ringkas dan padat",
                                    "Conclusion" to "Kesimpulan"
                                )
                                dimensions.chunked(2).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        row.forEach { (english, local) ->
                                            Surface(
                                                modifier = Modifier.weight(1f),
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Column(Modifier.padding(10.dp)) {
                                                    Text(english, style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.SemiBold)
                                                    Text(local, style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                        }
                                        if (row.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Evaluasi terbaru", Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (data.pendingEvaluations > 0) {
                                Text("${data.pendingEvaluations} diproses",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (data.latestEvaluations.isEmpty()) item {
                        Text("Belum ada evaluasi selesai. Selesaikan satu latihan untuk melihat feedback di sini.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(data.latestEvaluations, key = { it.evaluationId }) { evaluation ->
                        Card(
                            Modifier.fillMaxWidth().clickable { onOpenRecording(evaluation.recordingJobId) }
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(evaluation.title.removePrefix("Practice — "),
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text("Attempt ${evaluation.attemptNumber} • Evaluasi v${evaluation.version} • ${formatProgressDate(evaluation.createdAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(evaluation.result.replace(Regex("[#*_\\n]+"), " ").take(220) +
                                    if (evaluation.result.length > 220) "…" else "",
                                    style = MaterialTheme.typography.bodySmall, maxLines = 4,
                                    overflow = TextOverflow.Ellipsis)
                                Text("Buka hasil evaluasi  →", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (error != null) item {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressMetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatProgressDate(value: String): String = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale("id", "ID")))
}.getOrElse { value.take(10) }


private fun progressDimensionLabel(value: String): String = when (value) {
    "Structure" -> "Struktur"
    "Clarity" -> "Kejelasan"
    "Context" -> "Konteks"
    "Reasoning" -> "Penalaran"
    "Conciseness" -> "Ringkas dan padat"
    "Conclusion" -> "Kesimpulan"
    else -> value
}

private fun progressStatusLabel(value: String): String = when (value) {
    "Initial Observation" -> "Observasi awal"
    "Recurring Pattern" -> "Pola berulang"
    "Early Positive Signal" -> "Sinyal positif awal"
    else -> value
}
