package com.example.daybook

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.ai.client.generativeai.GenerativeModel
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ─── Gemini API Key ─────────────────────────────────────────────────────────
// ⚠️  Replace this with your own key from aistudio.google.com
//     After rotating your key, paste the new value here and rebuild.
private const val GEMINI_API_KEY = "AQ.Ab8RN6JYrDhX_0nAkaLP-brB46RRt_aQ7alU26VfusaC_40QzA"

// ─── Data Model ─────────────────────────────────────────────────────────────

data class Entry(
    @DocumentId val id: String = "",
    val type: String = "",          // block | note | dream | bucket
    val title: String = "",
    val body: String = "",          // HTML for notes, plain text for others
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

// Save status for auto-save indicator
enum class SaveStatus { IDLE, SAVING, SAVED, ERROR }

// ─── ViewModel ──────────────────────────────────────────────────────────────

class VM : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    val user = MutableStateFlow<FirebaseUser?>(auth.currentUser)

    // Auto-save state
    val saveStatus = MutableStateFlow(SaveStatus.IDLE)

    // AI review state
    val aiResult = MutableStateFlow<String?>(null)
    val aiLoading = MutableStateFlow(false)

    private var autoSaveJob: Job? = null

    init {
        auth.addAuthStateListener { user.value = it.currentUser }
    }

    private fun col() = FirebaseFirestore.getInstance()
        .collection("users")
        .document(auth.currentUser!!.uid)
        .collection("entries")

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

    /** Auto-save with 2-second debounce. Shows SAVING → SAVED indicator. */
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

    fun del(e: Entry) {
        col().document(e.id).delete()
    }

    fun signIn(em: String, pw: String, fail: (String) -> Unit) {
        auth.signInWithEmailAndPassword(em, pw)
            .addOnFailureListener { fail(it.message ?: "Sign in failed") }
    }

    fun signUp(em: String, pw: String, fail: (String) -> Unit) {
        auth.createUserWithEmailAndPassword(em, pw)
            .addOnFailureListener { fail(it.message ?: "Sign up failed") }
    }

    fun signOut() = auth.signOut()

    fun forgotPassword(em: String, onDone: (String?) -> Unit) {
        if (em.isBlank()) { onDone("Please enter your email first"); return }
        auth.sendPasswordResetEmail(em.trim())
            .addOnSuccessListener { onDone(null) }
            .addOnFailureListener { onDone(it.message ?: "Failed to send reset email") }
    }

    /** Ask Gemini to review the note text. Result is placed in [aiResult]. */
    fun reviewWithAI(noteTitle: String, noteBodyHtml: String) {
        if (aiLoading.value) return
        aiLoading.value = true
        aiResult.value = null
        viewModelScope.launch {
            try {
                val model = GenerativeModel(
                    modelName = "gemini-1.5-flash",
                    apiKey = GEMINI_API_KEY
                )
                // Strip HTML tags for cleaner prompt
                val plainBody = noteBodyHtml
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()

                val prompt = """
                    Kamu adalah asisten pribadi yang membantu mereview catatan.
                    
                    Judul catatan: "$noteTitle"
                    
                    Isi catatan:
                    "$plainBody"
                    
                    Tolong berikan:
                    1. **Ringkasan singkat** (2-3 kalimat) isi catatan ini
                    2. **Poin-poin penting** yang perlu diingat
                    3. **Saran perbaikan** — apakah ada yang bisa diperjelas, ditambah, atau dikurangi?
                    4. **Tindak lanjut** — action item konkret jika ada
                    
                    Gunakan bahasa yang sama dengan catatan (Indonesia atau Inggris).
                    Jawab dengan format yang rapi dan mudah dibaca.
                """.trimIndent()

                val response = model.generateContent(prompt)
                aiResult.value = response.text ?: "Tidak ada respons dari AI."
            } catch (e: Exception) {
                aiResult.value = "Error: ${e.message}\n\nPastikan API key sudah benar dan koneksi internet tersedia."
            } finally {
                aiLoading.value = false
            }
        }
    }

    fun clearAiResult() {
        aiResult.value = null
    }
}

// ─── Constants ──────────────────────────────────────────────────────────────

val TABS = listOf(
    Triple("block", "Timebox", "🗓"),
    Triple("note", "Notes", "📝"),
    Triple("dream", "Dreams", "🌙"),
    Triple("bucket", "Bucket list", "⭐")
)
val TAGS = mapOf(
    "note"   to listOf("Journal", "Learning", "Thoughts", "Ideas"),
    "dream"  to listOf("Normal", "Recurring", "Nightmare", "Lucid"),
    "bucket" to listOf("Travel", "Growth", "Career", "Health", "Family", "Finance", "Adventure", "Other")
)
val STATUS = listOf("Dream", "Planned", "Done")

// Text colors available in the rich editor toolbar
val TEXT_COLORS = listOf(
    Color.Unspecified to "Default",
    Color(0xFFE53935) to "Red",
    Color(0xFFFF6F00) to "Orange",
    Color(0xFF2E7D32) to "Green",
    Color(0xFF1565C0) to "Blue",
    Color(0xFF6A1B9A) to "Purple",
    Color(0xFF4E342E) to "Brown",
    Color(0xFF546E7A) to "Gray"
)

// ─── Activity ───────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val scheme = if (Build.VERSION.SDK_INT >= 31)
                (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx))
            else
                (if (dark) darkColorScheme() else lightColorScheme())
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App(vm: VM = viewModel()) {
    val user by vm.user.collectAsState()
    key(user?.uid) {
        if (user == null) Login(vm) else Home(vm)
    }
}

// ─── Home ────────────────────────────────────────────────────────────────────

@Composable
fun Home(vm: VM) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp),
                Arrangement.End
            ) {
                TextButton({ vm.signOut() }) { Text("Sign out") }
            }
        },
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, (_, label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(icon) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            key(tab) { Screen(TABS[tab].first, vm) }
        }
    }
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
        else -> all
            .filter { (it.title + it.body + it.tag).contains(q, ignoreCase = true) }
            .let { r ->
                when (t) {
                    "dream"  -> r.sortedByDescending { it.date }
                    "bucket" -> r.sortedBy { s ->
                        STATUS.indexOf(s.status).let { i -> if (i == 1) 0 else if (i == 0) 1 else 2 }
                    }
                    else -> r
                }
            }
    }

    Box(Modifier.fillMaxSize()) {
        Column {
            if (t == "block") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    Arrangement.SpaceBetween, Alignment.CenterVertically
                ) {
                    TextButton({ day = day.minusDays(1) }) { Text("‹") }
                    TextButton({ day = LocalDate.now() }) {
                        Text(day.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")))
                    }
                    TextButton({ day = day.plusDays(1) }) { Text("›") }
                }
                val done = rows.count { it.done }
                Text("$done of ${rows.size} done", Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedTextField(
                    q, { q = it },
                    Modifier.fillMaxWidth().padding(12.dp, 8.dp),
                    label = { Text("Search") },
                    singleLine = true
                )
            }

            if (rows.isEmpty()) Text("Nothing here yet. Tap + to add.", Modifier.padding(16.dp))

            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                items(rows, key = { it.id }) { e ->
                    SwipeToDeleteItem(onDelete = { vm.del(e) }) {
                        ListItem(
                            modifier = Modifier.clickable { edit = e },
                            headlineContent = {
                                Text(
                                    e.title,
                                    textDecoration = if (e.done || (e.status == "Done" && t == "bucket"))
                                        TextDecoration.LineThrough else null
                                )
                            },
                            supportingContent = {
                                val line = when (t) {
                                    "block"  -> "${e.start} – ${e.end}"
                                    "note"   -> e.tag + if (e.body.isNotBlank()) "\n${
                                        e.body.replace(Regex("<[^>]+>"), "").take(80)
                                    }" else ""
                                    "dream"  -> "${e.date}, ${e.tag}, sleep ${e.sleep}/5" +
                                            if (e.body.isNotBlank()) "\n${e.body.take(60)}" else ""
                                    else     -> e.tag + if (e.year > 0) ", ${e.year}" else ""
                                }
                                Text(line, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = {
                                if (t == "block")
                                    Checkbox(e.done, { vm.save(e.copy(done = it)) })
                                if (t == "bucket")
                                    TextButton({
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
            onClick = {
                edit = Entry(
                    type  = t, title = "",
                    tag   = TAGS[t]?.first() ?: "",
                    date  = (if (t == "block") day else LocalDate.now()).toString()
                )
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Default.Add, "Add") }
    }

    edit?.let { e ->
        if (e.type == "note") {
            // Notes use the rich editor screen
            NoteEditor(
                entry   = e,
                vm      = vm,
                onClose = { edit = null; vm.saveStatus.value = SaveStatus.IDLE }
            )
        } else {
            Editor(
                e,
                onSave   = { vm.save(it); edit = null },
                onDelete = { vm.del(e);   edit = null },
                onClose  = { edit = null }
            )
        }
    }
}

// ─── Swipe to Delete ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDeleteItem(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) { onDelete(); true } else false
        }
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val color by animateColorAsState(
                if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart)
                    MaterialTheme.colorScheme.errorContainer else Color.Transparent,
                label = "swipe_bg"
            )
            Box(
                Modifier.fillMaxSize().background(color).padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart)
                    Icon(Icons.Default.Delete, "Delete",
                        tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
        enableDismissFromStartToEnd = false
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) { content() }
    }
}

// ─── Rich Note Editor ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditor(entry: Entry, vm: VM, onClose: () -> Unit) {
    var title by remember { mutableStateOf(entry.title) }
    val richState = rememberRichTextState()
    val saveStatus by vm.saveStatus.collectAsState()
    val aiResult by vm.aiResult.collectAsState()
    val aiLoading by vm.aiLoading.collectAsState()
    var showColorPicker by remember { mutableStateOf(false) }
    var showAiSheet by remember { mutableStateOf(false) }
    var currentEntryId by remember { mutableStateOf(entry.id) }

    // Load existing HTML body into the rich editor
    LaunchedEffect(entry.id) {
        if (entry.body.isNotBlank()) {
            richState.setHtml(entry.body)
        }
    }

    // Helper to build current entry snapshot
    fun snapshot() = entry.copy(
        id    = currentEntryId,
        title = title,
        body  = richState.toHtml(),
        tag   = entry.tag,
        upd   = System.currentTimeMillis()
    )

    // Trigger auto-save whenever title or body changes
    LaunchedEffect(title, richState.toHtml()) {
        if (title.isNotBlank() || richState.toHtml().isNotBlank()) {
            val snap = snapshot()
            if (snap.id.isEmpty()) {
                // First save — generate ID so subsequent saves hit the same doc
                val newId = FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(FirebaseAuth.getInstance().currentUser!!.uid)
                    .collection("entries")
                    .document().id
                currentEntryId = newId
            }
            vm.autoSave(snapshot())
        }
    }

    // Show AI result sheet when result arrives
    LaunchedEffect(aiResult) {
        if (aiResult != null) showAiSheet = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notes", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Save status indicator
                    val statusText = when (saveStatus) {
                        SaveStatus.SAVING -> "Saving..."
                        SaveStatus.SAVED  -> "Saved ✓"
                        SaveStatus.ERROR  -> "Error ✗"
                        SaveStatus.IDLE   -> ""
                    }
                    if (statusText.isNotEmpty()) {
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (saveStatus) {
                                SaveStatus.SAVED -> MaterialTheme.colorScheme.primary
                                SaveStatus.ERROR -> MaterialTheme.colorScheme.error
                                else             -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }

                    // AI Review button
                    IconButton(
                        onClick = {
                            vm.reviewWithAI(title, richState.toHtml())
                        },
                        enabled = !aiLoading && (title.isNotBlank() || richState.toHtml().isNotBlank())
                    ) {
                        if (aiLoading) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.AutoAwesome, "AI Review",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    // Delete button (only if saved entry)
                    if (entry.id.isNotEmpty() || currentEntryId.isNotEmpty()) {
                        IconButton(onClick = {
                            vm.del(entry.copy(id = currentEntryId.ifEmpty { entry.id }))
                            onClose()
                        }) {
                            Icon(Icons.Default.Delete, "Delete",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            // Tag chips
            TAGS["note"]?.let { tags ->
                var selectedTag by remember { mutableStateOf(entry.tag.ifEmpty { "Journal" }) }
                Chips(tags, selectedTag) { selectedTag = it }
            }

            // Title field
            OutlinedTextField(
                title, { title = it },
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                label = { Text("Title") },
                singleLine = true
            )

            // ── Formatting Toolbar ──────────────────────────────────────
            FormattingToolbar(
                richState      = richState,
                showColorPicker = showColorPicker,
                onToggleColorPicker = { showColorPicker = !showColorPicker }
            )

            // Color picker row (shown/hidden)
            if (showColorPicker) {
                ColorPickerRow(richState = richState)
            }

            // ── Rich Text Editor ──────────────────────────────────────
            RichTextEditor(
                state    = richState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                placeholder = { Text("Start writing...", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            )
        }
    }

    // ── AI Review Bottom Sheet ──────────────────────────────────────────────
    if (showAiSheet && aiResult != null) {
        AiReviewSheet(
            result  = aiResult!!,
            onClose = { showAiSheet = false; vm.clearAiResult() }
        )
    }
}

// ─── Formatting Toolbar ──────────────────────────────────────────────────────

@Composable
fun FormattingToolbar(
    richState: RichTextState,
    showColorPicker: Boolean,
    onToggleColorPicker: () -> Unit
) {
    val isBold      = richState.currentSpanStyle.fontWeight == FontWeight.Bold
    val isItalic    = richState.currentSpanStyle.fontStyle == FontStyle.Italic
    val isUnderline = richState.currentSpanStyle.textDecoration?.contains(TextDecoration.Underline) == true
    val isStrike    = richState.currentSpanStyle.textDecoration?.contains(TextDecoration.LineThrough) == true

    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Bold
            FormatButton(active = isBold, onClick = {
                richState.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
            }) { Text("B", fontWeight = FontWeight.Bold, fontSize = 14.sp) }

            // Italic
            FormatButton(active = isItalic, onClick = {
                richState.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
            }) { Text("I", fontStyle = FontStyle.Italic, fontSize = 14.sp) }

            // Underline
            FormatButton(active = isUnderline, onClick = {
                richState.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline))
            }) { Text("U", textDecoration = TextDecoration.Underline, fontSize = 14.sp) }

            // Strikethrough
            FormatButton(active = isStrike, onClick = {
                richState.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
            }) { Text("S", textDecoration = TextDecoration.LineThrough, fontSize = 14.sp) }

            VerticalDivider(Modifier.height(24.dp).padding(horizontal = 2.dp))

            // Heading 1
            FormatButton(active = false, onClick = {
                richState.toggleSpanStyle(SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold))
            }) { Text("H1", fontWeight = FontWeight.Bold, fontSize = 12.sp) }

            // Heading 2
            FormatButton(active = false, onClick = {
                richState.toggleSpanStyle(SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
            }) { Text("H2", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }

            VerticalDivider(Modifier.height(24.dp).padding(horizontal = 2.dp))

            // Unordered list
            FormatButton(active = false, onClick = {
                richState.toggleUnorderedList()
            }) { Icon(Icons.Default.FormatListBulleted, "Bullet list", Modifier.size(18.dp)) }

            // Ordered list
            FormatButton(active = false, onClick = {
                richState.toggleOrderedList()
            }) { Icon(Icons.Default.FormatListNumbered, "Numbered list", Modifier.size(18.dp)) }

            VerticalDivider(Modifier.height(24.dp).padding(horizontal = 2.dp))

            // Color picker toggle
            FormatButton(
                active = showColorPicker,
                onClick = onToggleColorPicker
            ) {
                Icon(Icons.Default.FormatColorText, "Text color", Modifier.size(18.dp))
            }

            // Clear formatting
            FormatButton(active = false, onClick = {
                richState.removeSpanStyle(
                    SpanStyle(
                        fontWeight     = FontWeight.Bold,
                        fontStyle      = FontStyle.Italic,
                        textDecoration = TextDecoration.combine(
                            listOf(TextDecoration.Underline, TextDecoration.LineThrough)
                        )
                    )
                )
            }) {
                Icon(Icons.Default.FormatClear, "Clear formatting", Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun FormatButton(active: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Surface(
        shape    = MaterialTheme.shapes.small,
        color    = bg,
        modifier = Modifier
            .size(36.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

// ─── Color Picker Row ────────────────────────────────────────────────────────

@Composable
fun ColorPickerRow(richState: RichTextState) {
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Color:", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(end = 4.dp))
        TEXT_COLORS.forEach { (color, label) ->
            val displayColor = if (color == Color.Unspecified)
                MaterialTheme.colorScheme.onSurface else color
            Box(
                Modifier
                    .size(28.dp)
                    .background(displayColor, MaterialTheme.shapes.small)
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                    .clickable {
                        if (color == Color.Unspecified) {
                            richState.removeSpanStyle(SpanStyle(color = richState.currentSpanStyle.color))
                        } else {
                            richState.toggleSpanStyle(SpanStyle(color = color))
                        }
                    }
            )
        }
    }
}

// ─── AI Review Bottom Sheet ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiReviewSheet(result: String, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                Arrangement.SpaceBetween,
                Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("AI Review", style = MaterialTheme.typography.titleMedium)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, "Close")
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // Scrollable result text
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(result, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ─── Standard Editor (Timebox / Dream / Bucket) ──────────────────────────────

@Composable
fun Chips(options: List<String>, selected: String, onPick: (String) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
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

    fun parseHour(t: String)   = t.split(":").firstOrNull()?.toIntOrNull() ?: 9
    fun parseMinute(t: String) = t.split(":").lastOrNull()?.toIntOrNull() ?: 0
    fun fmtTime(h: Int, m: Int) = "%02d:%02d".format(h, m)

    val valid = x.title.isNotBlank() && (x.type != "block" || x.end > x.start)

    if (showStartPicker) {
        val tps = rememberTimePickerState(parseHour(x.start), parseMinute(x.start), true)
        TimePickerDialog("Start time",
            { x = x.copy(start = fmtTime(tps.hour, tps.minute)); showStartPicker = false },
            { showStartPicker = false }) { TimePicker(tps) }
    }
    if (showEndPicker) {
        val tpe = rememberTimePickerState(parseHour(x.end), parseMinute(x.end), true)
        TimePickerDialog("End time",
            { x = x.copy(end = fmtTime(tpe.hour, tpe.minute)); showEndPicker = false },
            { showEndPicker = false }) { TimePicker(tpe) }
    }
    if (showDatePicker) {
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = runCatching {
                LocalDate.parse(x.date).toEpochDay() * 86_400_000L
            }.getOrDefault(System.currentTimeMillis())
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton({
                    dpState.selectedDateMillis?.let { ms ->
                        val d = java.time.Instant.ofEpochMilli(ms)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
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
                    if (x.end <= x.start)
                        Text("End must be after start", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
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
                    Chips((1..5).map { "Sleep $it" }, "Sleep ${x.sleep}") { s ->
                        x = x.copy(sleep = s.last().digitToInt())
                    }
                if (x.type == "bucket") {
                    Chips(STATUS, x.status) { s -> x = x.copy(status = s) }
                    OutlinedTextField(
                        if (x.year > 0) x.year.toString() else "",
                        { s -> x = x.copy(year = s.filter(Char::isDigit).take(4).toIntOrNull() ?: 0) },
                        Modifier.fillMaxWidth(),
                        label = { Text("Target year (optional)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
        }
    )
}

@Composable
fun TimePickerDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(title) },
        text  = { content() },
        confirmButton = { TextButton(onConfirm) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

// ─── Login ───────────────────────────────────────────────────────────────────

@Composable
fun Login(vm: VM) {
    var em  by remember { mutableStateOf("") }
    var pw  by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
        Arrangement.Center
    ) {
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
        TextButton({ err = ""; msg = ""; vm.forgotPassword(em) { if (it == null) msg = "Reset email sent! Check your inbox." else err = it } },
            contentPadding = PaddingValues(0.dp)) {
            Text("Forgot password?", style = MaterialTheme.typography.bodySmall)
        }
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
    }
}
