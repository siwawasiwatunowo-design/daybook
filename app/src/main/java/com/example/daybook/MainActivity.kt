package com.example.daybook

import android.Manifest
import android.content.Context
import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.GoogleAuthProvider
import android.text.Html
import android.content.Intent
import android.widget.Toast
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
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FirebaseFirestore
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
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
    val aiAction = MutableStateFlow("improve")
    val aiLoading = MutableStateFlow(false)

    // Recording state
    val recordState = MutableStateFlow(RecordState.IDLE)
    val recordSeconds = MutableStateFlow(0)
    /** Holds the transcribed + summarized MOM from a recording */
    val recordingResult = MutableStateFlow<String?>(null)

    private var autoSaveJob: Job? = null
    private var pendingAutoSave: Entry? = null
    private var autoSaveVersion: Long = 0
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
        // Keep only the newest editor snapshot, but serialize Firestore writes.
        // A previous write must finish before the next snapshot is written so an
        // older in-flight request cannot overwrite newer user edits.
        pendingAutoSave = e
        autoSaveVersion += 1
        saveStatus.value = SaveStatus.SAVING
        if (autoSaveJob?.isActive == true) return

        autoSaveJob = viewModelScope.launch {
            try {
                while (pendingAutoSave != null) {
                    val versionAtDebounceStart = autoSaveVersion
                    delay(2000)
                    if (versionAtDebounceStart != autoSaveVersion) continue

                    val snapshot = pendingAutoSave ?: break
                    pendingAutoSave = null
                    val id = snapshot.id.ifEmpty { col().document().id }
                    withContext(Dispatchers.IO) {
                        Tasks.await(
                            col().document(id).set(
                                snapshot.copy(id = id, upd = System.currentTimeMillis())
                            )
                        )
                    }

                    if (versionAtDebounceStart == autoSaveVersion && pendingAutoSave == null) {
                        saveStatus.value = SaveStatus.SAVED
                        delay(2000)
                        if (versionAtDebounceStart == autoSaveVersion && pendingAutoSave == null) {
                            saveStatus.value = SaveStatus.IDLE
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the latest snapshot available so the next edit can retry it.
                if (pendingAutoSave == null) pendingAutoSave = e
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
    fun signInWithGoogle(activity: Activity, fail: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val googleOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(activity.getString(R.string.default_web_client_id))
                    .setAutoSelectEnabled(false)
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleOption)
                    .build()
                val result = CredentialManager.create(activity).getCredential(
                    context = activity,
                    request = request
                )
                val credential = GoogleIdTokenCredential.createFrom(result.credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(credential.idToken, null)
                auth.signInWithCredential(firebaseCredential)
                    .addOnFailureListener { fail(it.message ?: "Google sign-in gagal.") }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    fail(e.localizedMessage ?: "Tidak dapat masuk dengan Google.")
                }
            }
        }
    }

    fun signOut() = auth.signOut()
    fun forgotPassword(em: String, onDone: (String?) -> Unit) {
        if (em.isBlank()) { onDone("Please enter your email first"); return }
        auth.sendPasswordResetEmail(em.trim())
            .addOnSuccessListener { onDone(null) }
            .addOnFailureListener { onDone(it.message ?: "Failed to send reset email") }
    }

    fun reviewWithAI(context: Context, noteTitle: String, noteBodyHtml: String, action: String = "improve", instruction: String? = null) {
        if (aiLoading.value) return
        aiLoading.value = true
        aiResult.value = null
        viewModelScope.launch {
            try {
                val plainText = Html.fromHtml(noteBodyHtml, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                val result = withContext(Dispatchers.IO) {
                    DaybookApiClient.assistNote(context, noteTitle, plainText, action, instruction)
                }
                aiAction.value = action
                aiResult.value = result
            } catch (ex: Exception) {
                aiResult.value = "AI Writing Assistant belum berhasil. Catatan tetap tersimpan.\\n\\n${ex.message ?: "Periksa koneksi dan backend Daybook."}"
            } finally {
                aiLoading.value = false
            }
        }
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
    Triple("note", "Notes", "📝"),
    Triple("practice", "Practice", "🎯"),
    Triple("record", "Meeting", "🎙")
)
val TAGS = mapOf(
    "note" to listOf("Journal", "Learning", "Thoughts", "Ideas")
)
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
                when (t) {
                    "record" -> RecordingsScreen(vm)
                    "practice" -> PracticeModeScreen(vm)
                    else -> NotesScreen(vm)
                }
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

    // Refresh from durable storage each time this screen is entered.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { RecordingRepository.reload() }
    }
    val startError by RecordingService.startError.collectAsState()
    var expandedId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<Recording?>(null) }
    var deleteTarget by remember { mutableStateOf<Recording?>(null) }
    var showApiConfigDialog by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("daybook_prefs", Context.MODE_PRIVATE) }
    var tipDismissed by remember { mutableStateOf(prefs.getBoolean("battery_tip_dismissed", false)) }

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
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Server API: ${DaybookApiConfig.getBaseUrl(context)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { showApiConfigDialog = true }) {
                        Text("Ubah", style = MaterialTheme.typography.labelSmall)
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
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (r.chunks.any { it.status == "TRANSCRIBED" })
                                        TextButton({ onOpenResult(r.id) }) { Text("Lihat transkrip") }
                                    if (r.status != "RECORDING" && r.status != "PROCESSING")
                                        TextButton({ SttWorker.enqueue(context, r.id) }) { Text("Transkrip Cloud") }
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

    if (showApiConfigDialog) {
        var urlText by remember { mutableStateOf(DaybookApiConfig.getBaseUrl(context)) }
        AlertDialog(
            onDismissRequest = { showApiConfigDialog = false },
            title = { Text("URL Server Daybook API") },
            text = {
                Column {
                    Text(
                        "Masukkan base URL backend daybook-api.\n• Default (ngrok): https://tubeless-gutless-unlined.ngrok-free.dev\n• Lokal: http://10.0.2.2:8080 atau IP Wi-Fi PC",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        label = { Text("Base URL API") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button({
                    DaybookApiConfig.setBaseUrl(context, urlText)
                    showApiConfigDialog = false
                    Toast.makeText(context, "URL Server disimpan: $urlText", Toast.LENGTH_SHORT).show()
                }) { Text("Simpan") }
            },
            dismissButton = {
                TextButton({ showApiConfigDialog = false }) { Text("Batal") }
            }
        )
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
    "PROCESSING" -> "memproses cloud…"
    "INTERRUPTED" -> "terputus"
    "PARTIAL" -> "sebagian"
    "DONE" -> "selesai ✓"
    "FAILED" -> "gagal"
    else -> s
}

/** Starts the foreground recording service for a new session (title is automatic). */
private fun startRecording(
    context: Context,
    customTitle: String? = null,
    sessionType: String = "MEETING",
    practicePrompt: String = "",
    practiceSessionId: String = "",
    attemptNumber: Int = 1,
    practiceMode: String = "SOLO",
    sourceNoteId: String = ""
) {
    val id = System.currentTimeMillis().toString()
    val title = customTitle ?: ("Meeting " + SimpleDateFormat("d MMM HH:mm", Locale("id", "ID")).format(Date()))
    RecordingService.clearStartError()
    val intent = Intent(context, RecordingService::class.java).apply {
        action = RecordingService.ACTION_START
        putExtra(RecordingService.EXTRA_ID, id)
        putExtra(RecordingService.EXTRA_TITLE, title)
        putExtra(RecordingService.EXTRA_SESSION_TYPE, sessionType)
        putExtra(RecordingService.EXTRA_PRACTICE_PROMPT, practicePrompt)
        putExtra(RecordingService.EXTRA_PRACTICE_SESSION_ID, practiceSessionId.ifBlank { if (sessionType == "PRACTICE") id else "" })
        putExtra(RecordingService.EXTRA_ATTEMPT_NUMBER, attemptNumber.coerceAtLeast(1))
        putExtra(RecordingService.EXTRA_PRACTICE_MODE, practiceMode)
        putExtra(RecordingService.EXTRA_SOURCE_NOTE_ID, sourceNoteId)
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

@Composable
fun NotesScreen(vm: VM) {
    val all by vm.list("note").collectAsState(initial = emptyList())
    var edit by remember { mutableStateOf<Entry?>(null) }
    var query by remember { mutableStateOf("") }

    val rows = all.filter {
        (it.title + it.body + it.tag).contains(query, ignoreCase = true)
    }

    Box(Modifier.fillMaxSize()) {
        Column {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                label = { Text("Cari catatan") },
                singleLine = true
            )

            if (rows.isEmpty()) {
                Text(
                    if (query.isBlank()) "Belum ada catatan. Tap + untuk mulai menulis."
                    else "Tidak ada catatan yang cocok.",
                    Modifier.padding(16.dp)
                )
            }

            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                items(rows, key = { it.id }) { entry ->
                    SwipeToDeleteItem(onDelete = { vm.del(entry) }) {
                        ListItem(
                            modifier = Modifier.clickable { edit = entry },
                            headlineContent = { Text(entry.title.ifBlank { "Tanpa judul" }) },
                            supportingContent = {
                                val preview = Html.fromHtml(
                                    entry.body,
                                    Html.FROM_HTML_MODE_LEGACY
                                ).toString().replace(Regex("\\s+"), " ").trim().take(120)
                                Text(
                                    listOf(entry.tag, preview).filter { it.isNotBlank() }.joinToString(" • "),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                    HorizontalDivider()
                }
            }
        }

        FloatingActionButton(
            onClick = {
                edit = Entry(
                    type = "note",
                    title = "",
                    tag = TAGS["note"]?.first().orEmpty(),
                    date = LocalDate.now().toString()
                )
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Icon(Icons.Default.Add, "Tambah catatan")
        }
    }

    edit?.let { entry ->
        NoteEditor(
            entry = entry,
            vm = vm,
            onClose = { edit = null; vm.saveStatus.value = SaveStatus.IDLE }
        )
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
    var selectedTag by remember(entry.id) {
        mutableStateOf(entry.tag.ifEmpty { "Journal" })
    }

    LaunchedEffect(entry.id) {
        if (entry.body.isNotBlank()) richState.setHtml(entry.body)
    }

    fun snapshot() = entry.copy(
        id = currentEntryId, title = title, tag = selectedTag,
        body = richState.toHtml(), upd = System.currentTimeMillis()
    )

    LaunchedEffect(title, richState.toHtml(), selectedTag) {
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

                    // AI Writing Assistant
                    var aiMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { aiMenu = true },
                            enabled = !aiLoading && recordState == RecordState.IDLE &&
                                (title.isNotBlank() || richState.toHtml().isNotBlank())) {
                            if (aiLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.AutoAwesome, "AI Writing Assistant", tint = MaterialTheme.colorScheme.primary)
                        }
                        DropdownMenu(expanded = aiMenu, onDismissRequest = { aiMenu = false }) {
                            listOf(
                                "improve" to "Perjelas & rapikan",
                                "expand" to "Kembangkan ide",
                                "organize" to "Susun terstruktur",
                                "continue" to "Lanjutkan tulisan",
                                "actions" to "Buat action items",
                                "explain" to "Jelaskan lebih mudah"
                            ).forEach { (action, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    aiMenu = false
                                    vm.reviewWithAI(context, title, richState.toHtml(), action)
                                })
                            }
                        }
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
        AiReviewSheet(result = aiResult!!, onClose = { showAiSheet = false; vm.clearAiResult() }, onApply = { append ->
            val current = Html.fromHtml(richState.toHtml(), Html.FROM_HTML_MODE_LEGACY).toString().trim()
            val combined = if (append && current.isNotBlank()) "$current\n\n${aiResult!!}" else aiResult!!
            richState.setHtml(Html.escapeHtml(combined).replace("\n", "<br>"))
            showAiSheet = false
            vm.clearAiResult()
        })
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
fun AiReviewSheet(result: String, onClose: () -> Unit, onApply: (Boolean) -> Unit) {
    var append by remember { mutableStateOf(true) }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("AI Writing Assistant", style = MaterialTheme.typography.titleMedium)
                        Text("Hasil siap digunakan di catatan", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Tutup") }
            }
            HorizontalDivider()
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(result, style = MaterialTheme.typography.bodyMedium)
            }
            Text("Terapkan hasil", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = append, onClick = { append = true }, label = { Text("Tambahkan") })
                FilterChip(selected = !append, onClick = { append = false }, label = { Text("Ganti isi note") })
            }
            Button(onClick = { onApply(append) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Check, null)
                Spacer(Modifier.width(8.dp))
                Text("Terapkan ke Notes")
            }
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Nanti") }
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

// ─── Login ───────────────────────────────────────────────────────────────────

@Composable
fun Login(vm: VM) {
    var em  by remember { mutableStateOf("") }
    var pw  by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var googleLoading by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity

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
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                err = ""; msg = ""
                if (activity == null) err = "Activity tidak tersedia."
                else {
                    googleLoading = true
                    vm.signInWithGoogle(activity) { message ->
                        googleLoading = false
                        err = message
                    }
                }
            },
            enabled = !googleLoading && activity != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (googleLoading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (googleLoading) "Menghubungkan..." else "Continue with Google")
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


// ─── Speaking Practice V1 ────────────────────────────────────────────────────

private data class PracticeTopic(val id: String, val title: String, val description: String, val prompt: String)

private val PRACTICE_TOPICS = listOf(
    PracticeTopic("problem", "Explain a Problem", "Latih menjelaskan masalah secara runtut.", "Jelaskan satu masalah yang pernah kamu hadapi: konteks, penyebab, dampak, dan solusi."),
    PracticeTopic("idea", "Present an Idea", "Latih menyampaikan ide agar mudah dipahami.", "Presentasikan satu ide: masalah yang ingin diselesaikan, solusi, manfaat, dan langkah berikutnya."),
    PracticeTopic("concept", "Explain a Concept", "Latih menjelaskan hal kompleks dengan sederhana.", "Pilih satu konsep yang kamu pahami. Jelaskan seolah-olah lawan bicaramu belum pernah mengenalnya."),
    PracticeTopic("free", "Free Speaking", "Bicara bebas tentang hal yang sedang kamu pikirkan.", "Ceritakan apa pun yang sedang kamu pikirkan. Usahakan tetap memiliki pembukaan, isi, dan penutup.")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeModeScreen(vm: VM) {
    val context = LocalContext.current
    val live by RecordingService.live.collectAsState()
    val recordings by RecordingRepository.recordings.collectAsState()
    var selected by rememberSaveable { mutableStateOf("problem") }
    var customTopic by rememberSaveable { mutableStateOf("") }
    var customPromptOverride by rememberSaveable { mutableStateOf("") }
    var openResultId by rememberSaveable { mutableStateOf<String?>(null) }
    var showProgress by rememberSaveable { mutableStateOf(false) }
    var showLearning by rememberSaveable { mutableStateOf(false) }
    val isPracticeRecording = live?.title?.startsWith("Practice —") == true
    var cloudHistoryLoading by remember { mutableStateOf(true) }
    var cloudHistoryError by remember { mutableStateOf<String?>(null) }
    val practiceHistory = recordings.filter { it.sessionType == "PRACTICE" }
    val practiceSessions = practiceHistory
        .groupBy { it.practiceSessionId.ifBlank { it.id } }
        .values
        .mapNotNull { attempts -> attempts.maxByOrNull { it.createdAt }?.let { latest -> latest to attempts.size } }
        .sortedByDescending { it.first.createdAt }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            val topic = if (selected == "custom") customTopic.trim()
                else PRACTICE_TOPICS.firstOrNull { it.id == selected }?.title ?: "Free Speaking"
            val prompt = if (selected == "custom") customPromptOverride.ifBlank {
                "Jelaskan topik ini dengan konteks, poin utama, alasan atau contoh, lalu simpulkan: $topic"
            } else PRACTICE_TOPICS.firstOrNull { it.id == selected }?.prompt.orEmpty()
            startRecording(context, "Practice — $topic", "PRACTICE", prompt)
            customPromptOverride = ""
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { RecordingRepository.reload() }
        try {
            val cloudRecords = withContext(Dispatchers.IO) { DaybookApiClient.getPracticeHistory(context) }
            cloudRecords.forEach { cloud ->
                val local = RecordingRepository.recordings.value.firstOrNull { it.backendJobId == cloud.backendJobId }
                if (local != null) {
                    RecordingRepository.upsert(cloud.copy(
                        id = local.id,
                        dirName = local.dirName,
                        title = local.title,
                        practiceSessionId = local.practiceSessionId,
                        attemptNumber = local.attemptNumber,
                        practicePrompt = local.practicePrompt.ifBlank { cloud.practicePrompt },
                        practiceMode = local.practiceMode,
                        sourceNoteId = local.sourceNoteId,
                        chunks = if (local.chunks.isNotEmpty()) local.chunks.map { localChunk ->
                            val remoteChunk = cloud.chunks.firstOrNull { it.index == localChunk.index }
                            if (remoteChunk == null) localChunk else localChunk.copy(
                                transcript = remoteChunk.transcript.ifBlank { localChunk.transcript },
                                segments = if (localChunk.segments.isNotEmpty()) localChunk.segments else remoteChunk.segments,
                                status = if (remoteChunk.transcript.isNotBlank()) "TRANSCRIBED" else localChunk.status
                            )
                        } else cloud.chunks,
                        cloudOnly = local.chunks.isEmpty(),
                        cloudDurationMs = cloud.cloudDurationMs
                    ))
                } else {
                    RecordingRepository.upsert(cloud)
                }
            }
            cloudHistoryError = null
        } catch (e: Exception) {
            cloudHistoryError = e.message ?: "Histori cloud belum dapat dimuat."
        } finally {
            cloudHistoryLoading = false
        }
    }

    if (isPracticeRecording && live != null) {
        val current = live!!
        var confirmStop by remember { mutableStateOf(false) }
        LaunchedEffect(confirmStop) { if (confirmStop) { delay(3000); confirmStop = false } }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("SOLO PRACTICE", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(20.dp))
            Text(current.title.removePrefix("Practice — "), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text("Sampaikan pemikiranmu dengan kata-katamu sendiri. Tidak perlu sempurna.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))
            Text(formatClock(current.elapsedMs), style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold)
            Text("● Merekam latihan", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { current.level }, Modifier.fillMaxWidth().height(8.dp))
            current.warning?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
            Spacer(Modifier.weight(1f))
            Text("Target latihan: 2–5 menit", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { if (confirmStop) stopRecording(context) else confirmStop = true },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text(if (confirmStop) "Tap lagi untuk selesai" else "Selesai latihan") }
        }
        return
    }

    openResultId?.let { id ->
        RecordingResultScreen(id, vm) { openResultId = null }
        return
    }

    if (showLearning) {
        LearningExperienceScreen(
            onBack = { showLearning = false },
            onStartSpeaking = { title, instructions ->
                selected = "custom"
                customTopic = title
                customPromptOverride = instructions
                showLearning = false
            }
        )
        return
    }

    if (showProgress) {
        PracticeProgressScreen(
            onBack = { showProgress = false },
            onOpenRecording = { backendJobId ->
                val recording = RecordingRepository.recordings.value.firstOrNull {
                    it.backendJobId == backendJobId
                }
                if (recording != null) {
                    showProgress = false
                    openResultId = recording.id
                }
            },
            onStartPractice = { title, instructions ->
                selected = "custom"
                customTopic = title
                customPromptOverride = instructions
                showProgress = false
            }
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Practice & Thinking", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Mulai dari konteks yang ingin kamu pahami. Bentuk pendapat, lalu berlatih bicara jika memang relevan.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Button(onClick = { showLearning = true }) { Text("Belajar") }
                    TextButton(onClick = { showProgress = true }) { Text("Progress") }
                }
            }
        }
        item { Text("Pilih latihan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        items(PRACTICE_TOPICS, key = { it.id }) { topic ->
            val active = selected == topic.id
            Card(
                modifier = Modifier.fillMaxWidth().clickable { selected = topic.id },
                border = if (active) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                colors = CardDefaults.cardColors(
                    containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
                )
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(topic.title, fontWeight = FontWeight.SemiBold)
                        Text(topic.description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    RadioButton(selected = active, onClick = { selected = topic.id })
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Topik sendiri", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = customTopic, onValueChange = { customTopic = it; customPromptOverride = "" },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Contoh: Jelaskan solusi untuk masalah keterlambatan proses...") },
                        minLines = 2,
                        enabled = true
                    )
                    TextButton(onClick = { selected = "custom" }) {
                        Text(if (selected == "custom") "Topik kustom dipilih ✓" else "Gunakan topik ini")
                    }
                }
            }
        }
        item {
            val prompt = if (selected == "custom") customTopic.trim()
                else PRACTICE_TOPICS.firstOrNull { it.id == selected }?.prompt.orEmpty()
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Tantanganmu", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(prompt.ifBlank { "Isi topik kustom terlebih dahulu." })
                    Spacer(Modifier.height(8.dp))
                    Text("Saran: bicara 2–5 menit. Susun konteks, poin utama, alasan atau contoh, lalu simpulkan.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Button(
                onClick = {
                    if (selected == "custom" && customTopic.isBlank()) {
                        Toast.makeText(context, "Isi topik latihan terlebih dahulu.", Toast.LENGTH_SHORT).show()
                    } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                        val topic = if (selected == "custom") customTopic.trim()
                            else PRACTICE_TOPICS.firstOrNull { it.id == selected }?.title ?: "Free Speaking"
                        val prompt = if (selected == "custom") customPromptOverride.ifBlank {
                            "Jelaskan topik ini dengan konteks, poin utama, alasan atau contoh, lalu simpulkan: $topic"
                        } else PRACTICE_TOPICS.firstOrNull { it.id == selected }?.prompt.orEmpty()
                        startRecording(context, "Practice — $topic", "PRACTICE", prompt)
                        customPromptOverride = ""
                    } else permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                },
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Mulai latihan")
            }
        }
        item {
            Text("Riwayat latihan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (practiceHistory.isEmpty()) item {
            Text(
                when {
                    cloudHistoryLoading -> "Memuat histori latihan dari akunmu…"
                    cloudHistoryError != null -> "Histori cloud belum dapat dimuat. Periksa koneksi lalu buka kembali tab Practice."
                    else -> "Belum ada latihan. Sesi pertamamu akan muncul di sini."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
            )
        }
        items(practiceSessions, key = { it.first.practiceSessionId.ifBlank { it.first.id } }) { (rec, attemptCount) ->
            ListItem(
                modifier = Modifier.clickable { openResultId = rec.id },
                headlineContent = { Text(rec.title.removePrefix("Practice — "), fontWeight = FontWeight.Medium) },
                supportingContent = {
                    Text("$attemptCount percobaan • ${formatClock(if (rec.cloudDurationMs > 0) rec.cloudDurationMs else rec.chunks.sumOf { it.durationMs })} • ${statusLabel(rec.status)}")
                },
                trailingContent = {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Lihat sesi latihan")
                }
            )
            HorizontalDivider()
        }
    }
}
