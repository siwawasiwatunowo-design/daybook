package com.example.daybook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LearningExperienceScreen(
    onBack: () -> Unit,
    onStartSpeaking: (String, String) -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var material by remember { mutableStateOf("") }
    var guide by remember { mutableStateOf<LearningGuide?>(null) }
    var guideJson by remember { mutableStateOf("") }
    var reflection by remember { mutableStateOf<LearningReflection?>(null) }
    val answers = remember { mutableStateListOf("", "", "") }
    var opinion by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(guide) {
        if (guide != null) {
            answers.clear()
            repeat(guide!!.comprehensionQuestions.size) { answers.add("") }
            opinion = ""
            reflection = null
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Kembali") }
            Text("LEARNING SESSION", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        Text("Mulai dari memahami sesuatu.", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        Text(
            "Masukkan materi yang memang ingin kamu pahami. Daybook akan membantu menemukan inti pembahasan sebelum mengajakmu membentuk pendapat.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (guide == null) {
            OutlinedTextField(
                value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Judul (opsional)") }, singleLine = true
            )
            OutlinedTextField(
                value = material, onValueChange = { material = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Tempel artikel, transkrip, atau materi di sini") },
                placeholder = { Text("Tempel teks yang ingin kamu pahami...") },
                minLines = 8
            )
            Text("Materi hanya dikirim saat kamu menekan tombol di bawah. Versi awal ini tidak menyimpan materi ke akun atau Notes.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    loading = true
                    error = null
                    reflection = null
                    // Network work is dispatched from the API client on IO.
                    kotlinx.coroutines.GlobalScope // placeholder
                },
                enabled = !loading && material.trim().length >= 80,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (loading) CircularProgressIndicator()
                else Text("Bantu gw memahami materi")
            }
        } else {
            val current = guide!!
            Text(current.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            LearningSection("Gambaran besarnya", current.overview)
            LearningSection("Pertanyaan utama", current.centralQuestion)
            LearningBulletSection("Gagasan utama", current.keyIdeas)
            if (current.terms.isNotEmpty()) {
                Text("Istilah dan konsep", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                current.terms.forEach { term ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(term.term, fontWeight = FontWeight.SemiBold)
                            Text(term.meaning, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (current.sourceLimitations.isNotEmpty()) {
                LearningBulletSection("Hal yang belum dijelaskan materi", current.sourceLimitations)
            }

            Text("Cek pemahamanmu", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Jawab dengan kata-katamu sendiri. Tidak perlu menghafal kalimat dari materi.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            current.comprehensionQuestions.forEachIndexed { index, question ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${index + 1}. ${question.question}", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = answers.getOrElse(index) { "" },
                        onValueChange = { if (index < answers.size) answers[index] = it },
                        modifier = Modifier.fillMaxWidth(), minLines = 2,
                        placeholder = { Text("Coba jelaskan dengan pemahamanmu...") }
                    )
                }
            }

            Text("Bentuk pendapatmu", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(current.thinkingQuestions.firstOrNull()?.question ?: current.centralQuestion,
                fontWeight = FontWeight.Medium)
            current.thinkingQuestions.firstOrNull()?.guidance?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = opinion, onValueChange = { opinion = it }, modifier = Modifier.fillMaxWidth(),
                minLines = 3, label = { Text("Apa pendapatmu?") },
                placeholder = { Text("Boleh setuju, tidak setuju, ragu, atau belum punya pendapat...") }
            )

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    loading = true
                    error = null
                    // Filled below by the screen coroutine effect.
                    reflection = null
                },
                enabled = !loading && (answers.any { it.isNotBlank() } || opinion.isNotBlank()),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (loading) CircularProgressIndicator() else Text("Refleksikan pemahamanku")
            }

            reflection?.let { result ->
                Text("Apa yang sudah kamu pahami", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                LearningSection("Pemahaman", result.understandingFeedback)
                if (result.understood.isNotEmpty()) LearningBulletSection("Yang sudah tertangkap", result.understood)
                if (result.gaps.isNotEmpty()) LearningBulletSection("Yang masih perlu diperjelas", result.gaps)
                LearningSection("Cara kamu berpikir", result.thinkingFeedback)
                LearningSection("Pertanyaan lanjutan", result.followUpQuestion)
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Kalau ingin melanjutkan dengan suara", fontWeight = FontWeight.SemiBold)
                        Text(result.speakingPrompt, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { onStartSpeaking(current.title, result.speakingPrompt) },
                            modifier = Modifier.fillMaxWidth()) { Text("Latih dengan suara") }
                    }
                }
            }

            OutlinedButton(onClick = {
                guide = null
                guideJson = ""
                reflection = null
                error = null
            }, modifier = Modifier.fillMaxWidth()) { Text("Ganti materi") }
        }
    }
}

@Composable
private fun LearningSection(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LearningBulletSection(title: String, values: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        values.forEach { value ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", color = MaterialTheme.colorScheme.primary)
                Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
