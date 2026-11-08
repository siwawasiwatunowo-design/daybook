package com.example.daybook

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FirebaseFirestore
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

// NOTE (Phase 1): The hardcoded Gemini API key that lived here was removed for
// security. Cloud AI (Gemini) is disabled until Phase 7, where it returns as an
// opt-in feature with a safe key source and explicit per-send consent. Any key
// that was exposed here or in built APKs must be revoked/rotated at Google.

// ─── Data Model ─────────────────────────────────────────────────────────────

data class Entry(
    @DocumentId val id: String = "",
    val type: String = "",
    val title: String = "",
    val body: String = "",
    val date: String = "",
    val start: String = "09:00",
    val end: String = "10:00",
    val tag: String = "",
    val status: String = "Dream",
    val year: Int = 0,
    val sleep: Int = 3,
    val done: Boolean = false,
    val upd: Long = System.currentTimeMillis()
)

enum class SaveStatus { IDLE, SAVING, SAVED, ERROR }

// Recording states
enum class RecordState { IDLE, RECORDING, PROCESSING }

// ─── ViewModel ──────────────────────────────────────────────────────────────

class VM : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    val user = MutableStateFlow<FirebaseUser?>(auth.currentUser)
    val saveStatus = MutableStateFlow(SaveStatus.IDLE)
    val aiResult = MutableStateFlow<String?>(null)
    val aiLoading = MutableStateFlow(false)

    // Recording state
    val recordState = MutableStateFlow(RecordState.IDLE)
    val recordSeconds = MutableStateFlow(0)
    /** Holds the transcribed + summarized MOM from a recording */
    val recordingResult = MutableStateFlow<String?>(null)

    private var autoSaveJob: Job? = null
    private var timerJob: Job? = null
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    init { auth.addAuthStateListener { user.value = it.currentUser } }

    private fun col() = FirebaseFirestore.getInstance()
        .collection("users").document(auth.currentUser!!.uid).collection("entries")

    fun list(t: String): Flow<List<Entry>> = callbackFlow {
        val reg = col().whereEqualTo("type", t).addSnapshotListener { s, _ ->
            if (s != null) trySend(s.toObjects(Entry::class.java).sortedByDescending { it.upd })
        }
        awaitClose { reg.remove() }
    }

    fun save(e: Entry) {
        val id = e.id.ifEmpty { col().document().id }
        col().document(id).set(e.copy(id = id))
    }

    fun autoSave(e: Entry) {
        autoSaveJob?.cancel()
        saveStatus.value = SaveStatus.SAVING
        autoSaveJob = viewModelScope.launch {
            delay(2000)
            try {
                val id = e.id.ifEmpty { col().document().id }
                col().document(id).set(e.copy(id = id, upd = System.currentTimeMillis()))
                saveStatus.value = SaveStatus.SAVED
                delay(2000)
                saveStatus.value = SaveStatus.IDLE
            } catch (ex: Exception) {
                saveStatus.value = SaveStatus.ERROR
            }
        }
    }

    fun del(e: Entry) { col().document(e.id).delete() }

    fun signIn(em: String, pw: String, fail: (String) -> Unit) {
        auth.signInWithEmailAndPassword(em, pw).addOnFailureListener { fail(it.message ?: "Sign in failed") }
    }
    fun signUp(em: String, pw: String, fail: (String) -> Unit) {
        auth.createUserWithEmailAndPassword(em, pw).addOnFailureListener { fail(it.message ?: "Sign up failed") }
    }
    fun signOut() = auth.signOut()
    fun forgotPassword(em: String, onDone: (String?) -> Unit) {
        if (em.isBlank()) { onDone("Please enter your email first"); return }
        auth.sendPasswordResetEmail(em.trim())
            .addOnSuccessListener { onDone(null) }
            .addOnFailureListener { onDone(it.message ?: "Failed to send reset email") }
    }

    fun reviewWithAI(noteTitle: String, noteBodyHtml: String) {
        // Phase 1: Gemini AI Review is disabled (local-first; API key removed for
        // security). It returns as an opt-in cloud feature in Phase 7. For now we
        // just surface a clear "disabled" notice via the existing AI Review sheet.
        aiResult.value = "AI Review sementara dinonaktifkan.\n\n" +
            "Fitur ini memakai Gemini (cloud) yang sedang dimatikan demi keamanan — " +
            "API key telah dihapus dari kode.\n\n" +
            "AI Review & pembuatan MoM akan kembali sebagai fitur opsional di Fase 7 " +
            "(dengan persetujuan eksplisit sebelum mengirim data). Sementara itu, " +
            "rekaman tetap aman tersimpan di perangkat."
    }

    fun clearAiResult() { aiResult.value = null }
    fun clearRecordingResult() { recordingResult.value = null }

    // ── Recording ────────────────────────────────────────────────────────────

    fun startRecording(context: android.content.Context) {
        if (recordState.value != RecordState.IDLE) return
        try {
            // Phase 1: persistent internal storage — never cacheDir. Recordings
            // survive app close and cache eviction, and are removed only by an
            // explicit user delete (added in Phase 2). History UI also comes in P2.
            val dir = File(context.filesDir, "recordings").apply { if (!exists()) mkdirs() }
            val file = File(dir, "meeting_${System.currentTimeMillis()}.m4a")
            audioFile = file

            @Suppress("DEPRECATION")
            val recorder = if (Build.VERSION.SDK_INT >= 31)
                MediaRecorder(context)
            else MediaRecorder()

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            recordState.value = RecordState.RECORDING
            recordSeconds.value = 0

            timerJob = viewModelScope.launch {
                while (true) {
                    delay(1000)
                    recordSeconds.value++
                }
            }
        } catch (e: Exception) {
            recordState.value = RecordState.IDLE
        }
    }

    fun stopRecording(meetingTitle: String) {
        // Phase 1: stop and finalize the recording. Audio is kept permanently in
        // filesDir/recordings (see startRecording). MoM generation (Gemini) is
        // disabled until Phase 7, so this no longer transcribes — it only saves.
        // meetingTitle is accepted for later phases (recording metadata) but not
        // used yet; proper recording metadata/history is Phase 2.
        if (recordState.value != RecordState.RECORDING) return
        timerJob?.cancel()
        try {
            mediaRecorder?.apply { stop(); release() }
            mediaRecorder = null
        } catch (e: Exception) { /* stop() may throw if nothing valid was captured */ }

        val file = audioFile
        audioFile = null
        recordState.value = RecordState.IDLE
        recordSeconds.value = 0

        // Finalize + validate: keep real recordings, drop empty stubs only.
        // A zero-length file means the recorder produced nothing usable (e.g.
        // stopped immediately) — it is not a recording. This is validation, not
        // auto-deletion of audio. Duration/playability validation is refined in
        // Phase 2 (playback) and Phase 4 (per-chunk checks).
        if (file != null && file.exists() && file.length() == 0L) {
            file.delete()
        }
    }

    // Phase 1: the Gemini REST helpers (uploadAudioToGemini, callGeminiText,
    // callGeminiWithAudio, deleteGeminiFile) were removed along with the
    // hardcoded API key. Gemini returns as an opt-in, safe-key feature in Phase 7.

    fun cancelRecording() {
        timerJob?.cancel()
        try { mediaRecorder?.apply { stop(); release() }; mediaRecorder = null } catch (e: Exception) { }
        audioFile?.delete()
        audioFile = null
        recordState.value = RecordState.IDLE
        recordSeconds.value = 0
    }

    override fun onCleared() {
        // Phase 1: NEVER delete recordings automatically. Previously this deleted
        // any in-flight audio when the ViewModel was cleared (app close, config
        // change, logout), silently destroying recordings. Now we only try to
        // finalize (stop+release) so the file is playable on graceful teardown;
        // the file always stays on disk. (Process-kill recovery is Phase 4.)
        super.onCleared()
        try {
            mediaRecorder?.apply { stop(); release() }
        } catch (e: Exception) {
            try { mediaRecorder?.release() } catch (_: Exception) { }
        }
        mediaRecorder = null
    }
}

// ─── Constants ──────────────────────────────────────────────────────────────

val TABS = listOf(
    Triple("block", "Timebox", "🗓"), Triple("note", "Notes", "📝"),
    Triple("dream", "Dreams", "🌙"), Triple("bucket", "Bucket list", "⭐"),
    Triple("record", "Rekam", "🎙")
)
val TAGS = mapOf(
    "note"   to listOf("Journal", "Learning", "Thoughts", "Ideas"),
    "dream"  to listOf("Normal", "Recurring", "Nightmare", "Lucid"),
    "bucket" to listOf("Travel", "Growth", "Career", "Health", "Family", "Finance", "Adventure", "Other")
)
val STATUS = listOf("Dream", "Planned", "Done")
val TEXT_COLORS = listOf(
    Color.Unspecified to "Default", Color(0xFFE53935) to "Red",
    Color(0xFFFF6F00) to "Orange", Color(0xFF2E7D32) to "Green",
    Color(0xFF1565C0) to "Blue",   Color(0xFF6A1B9A) to "Purple",
    Color(0xFF4E342E) to "Brown",  Color(0xFF546E7A) to "Gray"
)

// ─── Activity ───────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RecordingRepository.init(applicationContext)
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val scheme = if (Build.VERSION.SDK_INT >= 31)
                (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx))
            else (if (dark) darkColorScheme() else lightColorScheme())
            MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize()) { App() } }
        }
    }
}

@Composable
fun App(vm: VM = viewModel()) {
    val user by vm.user.collectAsState()
    key(user?.uid) { if (user == null) Login(vm) else Home(vm) }
}

// ─── Home ────────────────────────────────────────────────────────────────────

@Composable
fun Home(vm: VM) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp), Arrangement.End) {
                TextButton({ vm.signOut() }) { Text("Sign out") }
            }
        },
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, (_, label, icon) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i },
                        icon = { Text(icon) }, label = { Text(label) })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            key(tab) {
                val t = TABS[tab].first
                if (t == "record") RecordingsScreen(vm) else Screen(t, vm)
            }
        }
    }
}

// ─── Recordings Tab ───────────────────────────────────────────────────────────
//
// UX principles (see SYSTEM_FLOW.md §6.1): during a meeting there is no spare
// capacity to operate the app, so: one tap to start (title is automatic), a
// minimal screen with two big Tandai buttons, Stop far away and confirmed.

@Composable
fun RecordingsScreen(vm: VM) {
    val live by RecordingService.live.collectAsState()
    val cur = live
    var openResultId by rememberSaveable { mutableStateOf<String?>(null) }
    val resultId = openResultId
    if (cur != null) ActiveRecordingScreen(cur)
    else if (resultId != null) RecordingResultScreen(resultId, vm) { openResultId = null }
    else RecordingsList { openResultId = it }
}

/** Screen shown while a recording is running: minimal, big targets, nothing to type. */
@Composable
fun ActiveRecordingScreen(live: LiveRecording) {
    val context = LocalContext.current
    var confirmStop by remember { mutableStateOf(false) }
    LaunchedEffect(confirmStop) { if (confirmStop) { delay(3000); confirmStop = false } }

    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(live.title, style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Text("● Merekam", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
        Text(formatClock(live.elapsedMs), style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        // Live sound indicator: proves the mic is really capturing audio.
        LinearProgressIndicator(progress = { live.level }, modifier = Modifier.fillMaxWidth().height(10.dp))
        Text("Indikator suara", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))

        live.warning?.let { w ->
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(w, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { markRecording(context, RecordingService.MARK_BINGUNG) },
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) { Text("❓\nBingung", textAlign = TextAlign.Center, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            Button(
                onClick = { markRecording(context, RecordingService.MARK_PENTING) },
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) { Text("⭐\nPenting", textAlign = TextAlign.Center, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(8.dp))
        val lastMs = live.lastMarkMs
        Text(
            if (lastMs != null) "Ditandai ${markLabel(live.lastMarkType)} di ${formatClock(lastMs)} • total ${live.markCount}"
            else "Tap saat bingung atau ada hal penting. Tidak perlu mengetik.",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.weight(1f))
        // Small and far from Tandai; needs a second tap so it can't be hit by mistake.
        OutlinedButton(onClick = {
            if (confirmStop) stopRecording(context) else confirmStop = true
        }) { Text(if (confirmStop) "Tap lagi untuk berhenti" else "Stop") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsList(onOpenResult: (String) -> Unit) {
    val context = LocalContext.current
    val recs by RecordingRepository.recordings.collectAsState()
    val startError by RecordingService.startError.collectAsState()
    var expandedId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<Recording?>(null) }
    var deleteTarget by remember { mutableStateOf<Recording?>(null) }
    val prefs = remember { context.getSharedPreferences("daybook_prefs", Context.MODE_PRIVATE) }
    var tipDismissed by remember { mutableStateOf(prefs.getBoolean("battery_tip_dismissed", false)) }
    var autoUploadEnabled by remember { mutableStateOf(BackendSyncPreferences.isEnabled(context)) }

    // RECORD_AUDIO is required; POST_NOTIFICATIONS is requested too but NOT required
    // (if denied, recording still runs, only the notification is hidden).
    val perms = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) startRecording(context)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Proses otomatis dengan backend", fontWeight = FontWeight.Medium)
                                Text("Rekaman baru dikirim ke Neon, lalu diproses Groq + Gemini. Audio lokal tetap disimpan.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = autoUploadEnabled, onCheckedChange = { enabled ->
                                autoUploadEnabled = enabled
                                BackendSyncPreferences.setEnabled(context, enabled)
                            })
                        }
                        Text(
                            if (autoUploadEnabled) "Aktif: hanya rekaman yang dimulai setelah ini yang otomatis diunggah. Rekaman lama tidak ikut."
                            else "Nonaktif: rekaman hanya diproses lokal di perangkat.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (!tipDismissed) item {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Supaya rekaman tidak berhenti saat layar mati", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text("HP Xiaomi/POCO sering mematikan app di latar belakang. Di Info Aplikasi Daybook: " +
                            "Penghemat baterai → \"Tanpa batasan\", aktifkan \"Mulai otomatis\" (Autostart), " +
                            "dan kunci app di Recent Apps.", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton({
                                try {
                                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + context.packageName)))
                                } catch (_: Exception) {}
                            }) { Text("Buka pengaturan app") }
                            TextButton({
                                prefs.edit().putBoolean("battery_tip_dismissed", true).apply()
                                tipDismissed = true
                            }) { Text("Sudah diatur") }
                        }
                    }
                }
            }
            startError?.let { err ->
                item {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(err, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton({ RecordingService.clearStartError() }) { Text("Tutup") }
                        }
                    }
                }
            }
            if (recs.isEmpty()) item {
                Text("Belum ada rekaman. Tap 🎙 untuk mulai merekam.", Modifier.padding(16.dp))
            }
            items(recs, key = { it.id }) { r ->
                val expanded = expandedId == r.id
                val total = r.chunks.sumOf { it.durationMs }
                ListItem(
                    modifier = Modifier.clickable { expandedId = if (expanded) null else r.id },
                    headlineContent = { Text(r.title.ifBlank { "Meeting" }, fontWeight = FontWeight.Medium) },
                    supportingContent = {
                        Column {
                            Text(buildString {
                                append(formatClock(total)).append(" • ").append(statusLabel(r.status))
                                if (r.marks.isNotEmpty()) append(" • ⭐ ").append(r.marks.size)
                            }, style = MaterialTheme.typography.bodySmall)
                            if (r.backendOptIn) Text("Backend: " + r.backendStatus.lowercase().replace("_", " ") +
                                if (r.backendMessage.isNotBlank()) " • " + r.backendMessage else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
                            if (r.message.isNotBlank())
                                Text(r.message + if (r.status == "INTERRUPTED") ". Audio sebelumnya berhasil diselamatkan." else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            val transcribed = r.chunks.count { it.status == "TRANSCRIBED" }
                            if (r.chunks.isNotEmpty() && r.status != "RECORDING") {
                                when {
                                    r.status == "DONE" -> Text("Transkrip selesai • Lihat",
                                        modifier = Modifier.clickable { onOpenResult(r.id) },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary)
                                    r.status == "PARTIAL" -> Text("Transkrip sebagian: $transcribed/${r.chunks.size} bagian • Lihat",
                                        modifier = Modifier.clickable(enabled = transcribed > 0) { onOpenResult(r.id) },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error)
                                    transcribed > 0 || r.chunks.any { it.status == "TRANSCRIBING" } ->
                                        Text("Transkrip: $transcribed/${r.chunks.size} bagian",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary)
                                    else -> Text("Transkrip belum tersedia",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (expanded) {
                                Spacer(Modifier.height(4.dp))
                                if (r.marks.isNotEmpty()) {
                                    Text("Penanda", style = MaterialTheme.typography.labelMedium)
                                    r.marks.forEach { m ->
                                        Text("   ${markLabel(m.type)} • ${formatClock(m.offsetMs)}",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                                r.chunks.forEach { c ->
                                    Text("   • bagian ${c.index}: ${formatClock(c.durationMs)} • ${statusLabel(c.status)}",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                val size = remember(r.id, r.chunks.size) { RecordingRepository.diskBytes(r.id) }
                                Text("Ukuran audio: ${formatSize(size)}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row {
                                    if (r.chunks.any { it.status == "TRANSCRIBED" })
                                        TextButton({ onOpenResult(r.id) }) { Text("Lihat transkrip") }
                                    TextButton({ renameTarget = r }) { Text("Ganti judul") }
                                    TextButton({ deleteTarget = r }) {
                                        Text("Hapus", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    },
                    trailingContent = {
                        if (r.mom.isNotBlank()) Text("MoM ✓", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                )
                HorizontalDivider()
            }
        }

        FloatingActionButton(
            onClick = {
                // One tap: no title dialog. Title is automatic and can be renamed afterwards.
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) startRecording(context)
                else perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Default.Mic, "Rekam") }
    }

    renameTarget?.let { r ->
        var text by remember(r.id) { mutableStateOf(r.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Ganti judul") },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true) },
            confirmButton = {
                Button({ RecordingRepository.rename(r.id, text); renameTarget = null }) { Text("Simpan") }
            },
            dismissButton = { TextButton({ renameTarget = null }) { Text("Batal") } }
        )
    }
    deleteTarget?.let { r ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Hapus rekaman?") },
            text = { Text("\"${r.title}\" dan semua audionya (${formatSize(RecordingRepository.diskBytes(r.id))}) " +
                "akan dihapus dari HP ini. Tidak bisa dibatalkan.") },
            confirmButton = {
                Button({
                    val id = r.id
                    Thread { RecordingRepository.remove(id) }.start()
                    deleteTarget = null; expandedId = null
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Hapus") }
            },
            dismissButton = { TextButton({ deleteTarget = null }) { Text("Batal") } }
        )
    }
}

private fun markLabel(type: String?): String = when (type) {
    RecordingService.MARK_BINGUNG -> "❓ Bingung"
    RecordingService.MARK_PENTING -> "⭐ Penting"
    else -> "⭐ Penanda"
}

private fun statusLabel(s: String): String = when (s) {
    "RECORDING" -> "Merekam"
    "RECORDED" -> "tersimpan"
    "TRANSCRIBING" -> "mentranskrip…"
    "TRANSCRIBED" -> "transkrip ✓"
    "PROCESSING" -> "audio tersimpan"
    "INTERRUPTED" -> "terputus"
    "PARTIAL" -> "sebagian"
    "DONE" -> "selesai"
    "FAILED" -> "gagal"
    else -> s
}

/** Starts the foreground recording service for a new session (title is automatic). */
private fun startRecording(context: Context) {
    val id = java.util.UUID.randomUUID().toString()
    val title = "Meeting " + SimpleDateFormat("d MMM HH:mm", Locale("id", "ID")).format(Date())
    RecordingService.clearStartError()
    val intent = Intent(context, RecordingService::class.java).apply {
        action = RecordingService.ACTION_START
        putExtra(RecordingService.EXTRA_ID, id)
        putExtra(RecordingService.EXTRA_TITLE, title)
    }
    ContextCompat.startForegroundService(context, intent)
}

/**
 * Stop / mark are sent with plain startService() (the app is visible when the user
 * taps them). startForegroundService() here would obligate the service to call
 * startForeground() within ~5 s and crash if the service was already gone.
 */
private fun stopRecording(context: Context) = sendToService(context, RecordingService.ACTION_STOP, null)

private fun markRecording(context: Context, type: String) =
    sendToService(context, RecordingService.ACTION_MARK, type)

private fun sendToService(context: Context, action: String, markType: String?) {
    try {
        context.startService(Intent(context, RecordingService::class.java).apply {
            this.action = action
            if (markType != null) putExtra(RecordingService.EXTRA_MARK_TYPE, markType)
        })
    } catch (_: Exception) { /* app not in foreground: ignore */ }
}

// ─── Section Screen ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(t: String, vm: VM) {
    val all by vm.list(t).collectAsState(initial = emptyList())
    var edit by remember { mutableStateOf<Entry?>(null) }
    var day by remember { mutableStateOf(LocalDate.now()) }
    var q by remember { mutableStateOf("") }

    val rows = when (t) {
        "block" -> all.filter { it.date == day.toString() }.sortedBy { it.start }
        else -> all.filter { (it.title + it.body + it.tag).contains(q, ignoreCase = true) }.let { r ->
            when (t) {
                "dream"  -> r.sortedByDescending { it.date }
                "bucket" -> r.sortedBy { s -> STATUS.indexOf(s.status).let { i -> if (i == 1) 0 else if (i == 0) 1 else 2 } }
                else     -> r
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column {
            if (t == "block") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    TextButton({ day = day.minusDays(1) }) { Text("‹") }
                    TextButton({ day = LocalDate.now() }) {
                        Text(day.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")))
                    }
                    TextButton({ day = day.plusDays(1) }) { Text("›") }
                }
                Text("${rows.count { it.done }} of ${rows.size} done",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(12.dp, 8.dp),
                    label = { Text("Search") }, singleLine = true)
            }

            if (rows.isEmpty()) Text("Nothing here yet. Tap + to add.", Modifier.padding(16.dp))

            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                items(rows, key = { it.id }) { e ->
                    SwipeToDeleteItem(onDelete = { vm.del(e) }) {
                        ListItem(
                            modifier = Modifier.clickable { edit = e },
                            headlineContent = {
                                Text(e.title, textDecoration =
                                    if (e.done || (e.status == "Done" && t == "bucket")) TextDecoration.LineThrough else null)
                            },
                            supportingContent = {
                                val line = when (t) {
                                    "block"  -> "${e.start} – ${e.end}"
                                    "note"   -> e.tag + if (e.body.isNotBlank()) "\n${e.body.replace(Regex("<[^>]+>"), "").take(80)}" else ""
                                    "dream"  -> "${e.date}, ${e.tag}, sleep ${e.sleep}/5" + if (e.body.isNotBlank()) "\n${e.body.take(60)}" else ""
                                    else     -> e.tag + if (e.year > 0) ", ${e.year}" else ""
                                }
                                Text(line, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = {
                                if (t == "block") Checkbox(e.done, { vm.save(e.copy(done = it)) })
                                if (t == "bucket") TextButton({
                                    vm.save(e.copy(status = STATUS[(STATUS.indexOf(e.status) + 1) % 3]))
                                }) { Text(e.status) }
                            }
                        )
                    }
                    HorizontalDivider()
                }
            }
        }

        FloatingActionButton(
            onClick = { edit = Entry(type = t, title = "", tag = TAGS[t]?.first() ?: "",
                date = (if (t == "block") day else LocalDate.now()).toString()) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Default.Add, "Add") }
    }

    edit?.let { e ->
        if (e.type == "note") {
            NoteEditor(entry = e, vm = vm, onClose = { edit = null; vm.saveStatus.value = SaveStatus.IDLE })
        } else {
            Editor(e, onSave = { vm.save(it); edit = null },
                onDelete = { vm.del(e); edit = null }, onClose = { edit = null })
        }
    }
}

// ─── Swipe to Delete ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDeleteItem(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { if (it == SwipeToDismissBoxValue.EndToStart) { onDelete(); true } else false }
    )
    SwipeToDismissBox(state = state, backgroundContent = {
        val color by animateColorAsState(
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart)
                MaterialTheme.colorScheme.errorContainer else Color.Transparent, label = "bg")
        Box(Modifier.fillMaxSize().background(color).padding(end = 20.dp), Alignment.CenterEnd) {
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart)
                Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.onErrorContainer)
        }
    }, enableDismissFromStartToEnd = false) {
        Surface(color = MaterialTheme.colorScheme.surface) { content() }
    }
}

// ─── Rich Note Editor ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditor(entry: Entry, vm: VM, onClose: () -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(entry.title) }
    val richState = rememberRichTextState()
    val saveStatus by vm.saveStatus.collectAsState()
    val aiResult by vm.aiResult.collectAsState()
    val aiLoading by vm.aiLoading.collectAsState()
    val recordState by vm.recordState.collectAsState()
    val recordSeconds by vm.recordSeconds.collectAsState()
    val recordingResult by vm.recordingResult.collectAsState()
    var showColorPicker by remember { mutableStateOf(false) }
    var showAiSheet by remember { mutableStateOf(false) }
    var currentEntryId by remember { mutableStateOf(entry.id) }

    LaunchedEffect(entry.id) {
        if (entry.body.isNotBlank()) richState.setHtml(entry.body)
    }

    fun snapshot() = entry.copy(
        id = currentEntryId, title = title,
        body = richState.toHtml(), upd = System.currentTimeMillis()
    )

    LaunchedEffect(title, richState.toHtml()) {
        if (title.isNotBlank() || richState.toHtml().isNotBlank()) {
            if (currentEntryId.isEmpty()) {
                currentEntryId = FirebaseFirestore.getInstance()
                    .collection("users").document(FirebaseAuth.getInstance().currentUser!!.uid)
                    .collection("entries").document().id
            }
            vm.autoSave(snapshot())
        }
    }

    LaunchedEffect(aiResult) { if (aiResult != null) showAiSheet = true }

    // When recording result arrives → inject into note body
    LaunchedEffect(recordingResult) {
        val result = recordingResult ?: return@LaunchedEffect
        // Set as HTML body and use result as title if title is empty
        richState.setHtml(result.replace("\n", "<br>"))
        if (title.isBlank()) title = "MOM - ${LocalDate.now()}"
        vm.clearRecordingResult()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notes", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    // Save status
                    val statusText = when (saveStatus) {
                        SaveStatus.SAVING -> "Saving..."
                        SaveStatus.SAVED  -> "Saved ✓"
                        SaveStatus.ERROR  -> "Error ✗"
                        SaveStatus.IDLE   -> ""
                    }
                    if (statusText.isNotEmpty()) Text(statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (saveStatus) {
                            SaveStatus.SAVED -> MaterialTheme.colorScheme.primary
                            SaveStatus.ERROR -> MaterialTheme.colorScheme.error
                            else             -> MaterialTheme.colorScheme.onSurfaceVariant
                        }, modifier = Modifier.padding(end = 4.dp))

                    // Mic button removed: recording now lives only in the Rekam tab
                    // (single recording path, see SYSTEM_FLOW.md §2).

                    // AI Review button
                    IconButton(
                        onClick = { vm.reviewWithAI(title, richState.toHtml()) },
                        enabled = !aiLoading && recordState == RecordState.IDLE &&
                                (title.isNotBlank() || richState.toHtml().isNotBlank())
                    ) {
                        if (aiLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.AutoAwesome, "AI Review",
                            tint = MaterialTheme.colorScheme.primary)
                    }

                    // Delete
                    if (entry.id.isNotEmpty() || currentEntryId.isNotEmpty()) {
                        IconButton(onClick = {
                            vm.del(entry.copy(id = currentEntryId.ifEmpty { entry.id })); onClose()
                        }) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TAGS["note"]?.let { tags ->
                var selectedTag by remember { mutableStateOf(entry.tag.ifEmpty { "Journal" }) }
                Chips(tags, selectedTag) { selectedTag = it }
            }

            OutlinedTextField(title, { title = it },
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                label = { Text("Title") }, singleLine = true)

            FormattingToolbar(richState = richState,
                showColorPicker = showColorPicker,
                onToggleColorPicker = { showColorPicker = !showColorPicker })

            if (showColorPicker) ColorPickerRow(richState = richState)

            // Processing indicator
            if (recordState == RecordState.PROCESSING) {
                Row(Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Memproses rekaman dengan AI...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }

            RichTextEditor(
                state = richState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface),
                placeholder = { Text("Start writing...", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            )
        }
    }

    // ── AI Review Sheet ────────────────────────────────────────────────────
    if (showAiSheet && aiResult != null) {
        AiReviewSheet(result = aiResult!!, onClose = { showAiSheet = false; vm.clearAiResult() })
    }
}

// ─── Recording Dialog ────────────────────────────────────────────────────────

@Composable
fun RecordingDialog(
    recordState: RecordState,
    recordSeconds: Int,
    noteTitle: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit
) {
    val isRecording = recordState == RecordState.RECORDING

    // Pulsing animation for recording indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.25f, label = "pulse",
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse)
    )

    AlertDialog(
        onDismissRequest = { if (!isRecording) onCancel() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Mic, null,
                    tint = if (isRecording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary)
                Text(if (isRecording) "Sedang merekam..." else "Rekam Meeting")
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()) {
                if (isRecording) {
                    // Pulsing mic icon
                    Icon(Icons.Default.Mic, null,
                        modifier = Modifier.size(64.dp).scale(pulse),
                        tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    // Timer
                    val minutes = recordSeconds / 60
                    val seconds = recordSeconds % 60
                    Text("%02d:%02d".format(minutes, seconds),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Text("Rekam percakapan meeting kamu.\nRekaman disimpan permanen di perangkat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (noteTitle.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("📋 $noteTitle",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    Icon(Icons.Default.Mic, null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text("Rekam meeting kamu. Rekaman disimpan\n" +
                        "permanen di perangkat.\n\n" +
                        "Pembuatan MoM otomatis (transkripsi,\n" +
                        "ringkasan, keputusan, action items) akan\n" +
                        "datang di fase berikutnya — secara lokal,\n" +
                        "tanpa internet.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            if (isRecording) {
                Button(onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Default.Stop, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            } else {
                Button(onClick = onStart) {
                    Icon(Icons.Default.Mic, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Mulai Rekam")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Batal") }
        }
    )
}

// ─── Formatting Toolbar ──────────────────────────────────────────────────────

@Composable
fun FormattingToolbar(richState: RichTextState, showColorPicker: Boolean, onToggleColorPicker: () -> Unit) {
    val isBold      = richState.currentSpanStyle.fontWeight == FontWeight.Bold
    val isItalic    = richState.currentSpanStyle.fontStyle == FontStyle.Italic
    val isUnderline = richState.currentSpanStyle.textDecoration?.contains(TextDecoration.Underline) == true
    val isStrike    = richState.currentSpanStyle.textDecoration?.contains(TextDecoration.LineThrough) == true

    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            FormatButton(isBold,    { richState.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) })   { Text("B", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            FormatButton(isItalic,  { richState.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) })   { Text("I", fontStyle = FontStyle.Italic, fontSize = 14.sp) }
            FormatButton(isUnderline, { richState.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)) }) { Text("U", textDecoration = TextDecoration.Underline, fontSize = 14.sp) }
            FormatButton(isStrike,  { richState.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) }) { Text("S", textDecoration = TextDecoration.LineThrough, fontSize = 14.sp) }
            VerticalDivider(Modifier.height(24.dp).padding(horizontal = 2.dp))
            FormatButton(false, { richState.toggleSpanStyle(SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)) })     { Text("H1", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            FormatButton(false, { richState.toggleSpanStyle(SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)) }) { Text("H2", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
            VerticalDivider(Modifier.height(24.dp).padding(horizontal = 2.dp))
            FormatButton(false, { richState.toggleUnorderedList() }) { Icon(Icons.Default.FormatListBulleted, null, Modifier.size(18.dp)) }
            FormatButton(false, { richState.toggleOrderedList() })   { Icon(Icons.Default.FormatListNumbered, null, Modifier.size(18.dp)) }
            VerticalDivider(Modifier.height(24.dp).padding(horizontal = 2.dp))
            FormatButton(showColorPicker, onToggleColorPicker) { Icon(Icons.Default.FormatColorText, null, Modifier.size(18.dp)) }
            FormatButton(false, {
                richState.removeSpanStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
                    textDecoration = TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))))
            }) { Icon(Icons.Default.FormatClear, null, Modifier.size(18.dp)) }
        }
    }
}

@Composable
fun FormatButton(active: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.small,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = Modifier.size(36.dp).clickable(onClick = onClick)) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
fun ColorPickerRow(richState: RichTextState) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Color:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 4.dp))
        TEXT_COLORS.forEach { (color, _) ->
            val displayColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
            Box(Modifier.size(28.dp).background(displayColor, MaterialTheme.shapes.small)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                .clickable {
                    if (color == Color.Unspecified) richState.removeSpanStyle(SpanStyle(color = richState.currentSpanStyle.color))
                    else richState.toggleSpanStyle(SpanStyle(color = color))
                })
        }
    }
}

// ─── AI Review Sheet ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiReviewSheet(result: String, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("AI Review", style = MaterialTheme.typography.titleMedium)
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(result, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ─── Standard Editor ─────────────────────────────────────────────────────────

@Composable
fun Chips(options: List<String>, selected: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { FilterChip(selected == it, { onPick(it) }, { Text(it) }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Editor(e: Entry, onSave: (Entry) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    var x by remember { mutableStateOf(e) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker   by remember { mutableStateOf(false) }
    var showDatePicker  by remember { mutableStateOf(false) }

    fun parseHour(t: String)    = t.split(":").firstOrNull()?.toIntOrNull() ?: 9
    fun parseMinute(t: String)  = t.split(":").lastOrNull()?.toIntOrNull() ?: 0
    fun fmtTime(h: Int, m: Int) = "%02d:%02d".format(h, m)

    val valid = x.title.isNotBlank() && (x.type != "block" || x.end > x.start)

    if (showStartPicker) {
        val tps = rememberTimePickerState(parseHour(x.start), parseMinute(x.start), true)
        TimePickerDialog("Start time", { x = x.copy(start = fmtTime(tps.hour, tps.minute)); showStartPicker = false },
            { showStartPicker = false }) { TimePicker(tps) }
    }
    if (showEndPicker) {
        val tpe = rememberTimePickerState(parseHour(x.end), parseMinute(x.end), true)
        TimePickerDialog("End time", { x = x.copy(end = fmtTime(tpe.hour, tpe.minute)); showEndPicker = false },
            { showEndPicker = false }) { TimePicker(tpe) }
    }
    if (showDatePicker) {
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = runCatching {
                LocalDate.parse(x.date).toEpochDay() * 86_400_000L
            }.getOrDefault(System.currentTimeMillis()))
        DatePickerDialog(onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton({
                    dpState.selectedDateMillis?.let { ms ->
                        val d = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        x = x.copy(date = d.toString())
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ showDatePicker = false }) { Text("Cancel") } }
        ) { DatePicker(dpState) }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (e.id.isEmpty()) "New entry" else "Edit entry") },
        confirmButton = {
            TextButton({ onSave(x.copy(upd = System.currentTimeMillis())) }, enabled = valid) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (e.id.isNotEmpty()) TextButton(onDelete) { Text("Delete") }
                TextButton(onClose) { Text("Cancel") }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(x.title, { x = x.copy(title = it) }, Modifier.fillMaxWidth(), label = { Text("Title") })
                if (x.type == "block") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(x.start, {}, Modifier.weight(1f).clickable { showStartPicker = true },
                            label = { Text("Start") }, enabled = false, singleLine = true)
                        OutlinedTextField(x.end, {}, Modifier.weight(1f).clickable { showEndPicker = true },
                            label = { Text("End") }, enabled = false, singleLine = true)
                    }
                    if (x.end <= x.start) Text("End must be after start",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (x.type == "dream") {
                    OutlinedTextField(x.date, {}, Modifier.fillMaxWidth().clickable { showDatePicker = true },
                        label = { Text("Date") }, enabled = false)
                }
                if (x.type == "note" || x.type == "dream")
                    OutlinedTextField(x.body, { x = x.copy(body = it) }, Modifier.fillMaxWidth(),
                        label = { Text("Details") }, minLines = 4)
                TAGS[x.type]?.let { Chips(it, x.tag) { s -> x = x.copy(tag = s) } }
                if (x.type == "dream")
                    Chips((1..5).map { "Sleep $it" }, "Sleep ${x.sleep}") { s -> x = x.copy(sleep = s.last().digitToInt()) }
                if (x.type == "bucket") {
                    Chips(STATUS, x.status) { s -> x = x.copy(status = s) }
                    OutlinedTextField(if (x.year > 0) x.year.toString() else "",
                        { s -> x = x.copy(year = s.filter(Char::isDigit).take(4).toIntOrNull() ?: 0) },
                        Modifier.fillMaxWidth(), label = { Text("Target year (optional)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
        }
    )
}

@Composable
fun TimePickerDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { content() },
        confirmButton = { TextButton(onConfirm) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

// ─── Login ───────────────────────────────────────────────────────────────────

@Composable
fun Login(vm: VM) {
    var em  by remember { mutableStateOf("") }
    var pw  by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), Arrangement.Center) {
        Text("Daybook", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(em, { em = it; err = ""; msg = "" }, Modifier.fillMaxWidth(),
            label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pw, { pw = it; err = ""; msg = "" }, Modifier.fillMaxWidth(),
            label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ err = ""; msg = ""; vm.signIn(em.trim(), pw) { err = it } }) { Text("Sign in") }
            TextButton({ err = ""; msg = ""; vm.signUp(em.trim(), pw) { err = it } }) { Text("Create account") }
        }
        TextButton(onClick = { err = ""; msg = ""; vm.forgotPassword(em) {
            if (it == null) msg = "Reset email sent! Check your inbox." else err = it
        } }, contentPadding = PaddingValues(0.dp)) {
            Text("Forgot password?", style = MaterialTheme.typography.bodySmall)
        }
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
    }
}
