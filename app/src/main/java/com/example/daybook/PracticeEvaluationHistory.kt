package com.example.daybook

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PracticeEvaluationHistoryPanel(context: Context, backendJobId: String) {
    if (backendJobId.isBlank()) return

    var evaluations by remember(backendJobId) { mutableStateOf<List<SpeakingCoachEvaluationRecord>>(emptyList()) }
    var feedback by remember(backendJobId) { mutableStateOf("") }
    var loading by remember(backendJobId) { mutableStateOf(true) }
    var requesting by remember(backendJobId) { mutableStateOf(false) }
    var error by remember(backendJobId) { mutableStateOf<String?>(null) }
    var expandedVersion by remember(backendJobId) { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        try {
            evaluations = withContext(Dispatchers.IO) {
                DaybookApiClient.getSpeakingCoachEvaluations(context, backendJobId)
            }
            error = null
        } catch (e: Exception) {
            error = e.message ?: "Riwayat evaluasi belum dapat dimuat."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(backendJobId, evaluations.lastOrNull()?.status) {
        refresh()
        while (evaluations.any { it.status.equals("Queued", true) || it.status.equals("Processing", true) || it.status.equals("RetryPending", true) }) {
            delay(2500)
            refresh()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Riwayat evaluasi Speaking Coach", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Setiap evaluasi disimpan sebagai versi tersendiri. Gunakan feedback untuk meminta Daybook meninjau ulang penilaiannya, bukan sekadar mengulang evaluasi yang sama.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (loading) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text("Memuat riwayat evaluasi…")
                }
            }

            evaluations.sortedByDescending { it.version }.forEach { item ->
                HorizontalDivider()
                Text(
                    "Versi ${item.version} • ${item.status}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                if (item.feedback.isNotBlank()) {
                    Text("Feedback saat evaluasi: ${item.feedback}", style = MaterialTheme.typography.bodySmall)
                }
                if (item.error.isNotBlank()) {
                    Text(item.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (item.result.isNotBlank()) {
                    TextButton(onClick = {
                        expandedVersion = if (expandedVersion == item.version) null else item.version
                    }) {
                        Text(if (expandedVersion == item.version) "Tutup hasil versi ${item.version}" else "Lihat hasil versi ${item.version}")
                    }
                    if (expandedVersion == item.version) {
                        Text(
                            item.result,
                            modifier = Modifier.fillMaxWidth().height(220.dp).verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (evaluations.isEmpty() && !loading) {
                Text("Belum ada riwayat evaluasi untuk latihan ini.", style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()
            Text("Minta evaluasi ulang", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = feedback,
                onValueChange = { if (it.length <= 2000) feedback = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text("Apa yang perlu ditinjau ulang? (wajib)") },
                placeholder = { Text("Contoh: Saya sengaja belum menyampaikan kesimpulan karena ini baru pembukaan. Tolong nilai sesuai tujuan latihan.") },
                supportingText = { Text("${feedback.length}/2000") },
                enabled = !requesting
            )
            Button(
                onClick = {
                    if (feedback.isBlank()) {
                        error = "Isi feedback terlebih dahulu agar evaluasi ulang memiliki konteks."
                    } else {
                        requesting = true
                        error = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    DaybookApiClient.requestSpeakingCoachEvaluation(context, backendJobId, feedback.trim())
                                }
                                feedback = ""
                                refresh()
                            } catch (e: Exception) {
                                error = e.message ?: "Gagal meminta evaluasi ulang."
                            } finally {
                                requesting = false
                            }
                        }
                    }
                },
                enabled = !requesting && feedback.isNotBlank() && evaluations.none {
                    it.status.equals("Queued", true) || it.status.equals("Processing", true) || it.status.equals("RetryPending", true)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                if (requesting) CircularProgressIndicator()
                else Text("Kirim feedback & evaluasi ulang")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
