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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ─── Data Model ─────────────────────────────────────────────────────────────

data class Entry(
    @DocumentId val id: String = "",
    val type: String = "",          // block | note | dream | bucket
    val title: String = "",
    val body: String = "",
    val date: String = "",
    val start: String = "09:00",
    val end: String = "10:00",
    val tag: String = "",           // note type, dream type, or bucket category
    val status: String = "Dream",   // bucket only
    val year: Int = 0,              // bucket only
    val sleep: Int = 3,             // dream only
    val done: Boolean = false,      // timebox only
    val upd: Long = System.currentTimeMillis()
)

// ─── ViewModel ──────────────────────────────────────────────────────────────

class VM : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    val user = MutableStateFlow<FirebaseUser?>(auth.currentUser)

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

    /** Sends a password reset email. Calls onDone(null) on success, onDone(errorMsg) on failure. */
    fun forgotPassword(em: String, onDone: (String?) -> Unit) {
        if (em.isBlank()) { onDone("Please enter your email first"); return }
        auth.sendPasswordResetEmail(em.trim())
            .addOnSuccessListener { onDone(null) }
            .addOnFailureListener { onDone(it.message ?: "Failed to send reset email") }
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

// ─── App root ───────────────────────────────────────────────────────────────

@Composable
fun App(vm: VM = viewModel()) {
    val user by vm.user.collectAsState()
    key(user?.uid) {
        if (user == null) Login(vm) else Home(vm)
    }
}

// ─── Home / Navigation ──────────────────────────────────────────────────────

@Composable
fun Home(vm: VM) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp),
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

// ─── Section Screen ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(t: String, vm: VM) {
    val all by vm.list(t).collectAsState(initial = emptyList())
    var edit by remember { mutableStateOf<Entry?>(null) }
    var day by remember { mutableStateOf(LocalDate.now()) }
    var q by remember { mutableStateOf("") }

    val rows = when (t) {
        "block" -> all
            .filter { it.date == day.toString() }
            .sortedBy { it.start }
        else -> all
            .filter { (it.title + it.body + it.tag).contains(q, ignoreCase = true) }
            .let { r ->
                when (t) {
                    "dream"  -> r.sortedByDescending { it.date }
                    "bucket" -> r.sortedBy { s ->
                        STATUS.indexOf(s.status).let { i -> if (i == 1) 0 else if (i == 0) 1 else 2 }
                    }
                    else     -> r
                }
            }
    }

    Box(Modifier.fillMaxSize()) {
        Column {
            // ── Timebox day navigator ────────────────────────────────────
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
                Text(
                    "$done of ${rows.size} done",
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                OutlinedTextField(
                    q, { q = it },
                    Modifier.fillMaxWidth().padding(12.dp, 8.dp),
                    label = { Text("Search") },
                    singleLine = true
                )
            }

            if (rows.isEmpty()) {
                Text("Nothing here yet. Tap + to add.", Modifier.padding(16.dp))
            }

            // ── List with swipe-to-delete ────────────────────────────────
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                items(rows, key = { it.id }) { e ->
                    SwipeToDeleteItem(
                        onDelete = { vm.del(e) }
                    ) {
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
                                    "note"   -> e.tag + if (e.body.isNotBlank()) "\n${e.body}" else ""
                                    "dream"  -> "${e.date}, ${e.tag}, sleep ${e.sleep}/5" +
                                            if (e.body.isNotBlank()) "\n${e.body}" else ""
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

        // FAB
        FloatingActionButton(
            onClick = {
                edit = Entry(
                    type  = t,
                    title = "",
                    tag   = TAGS[t]?.first() ?: "",
                    date  = (if (t == "block") day else LocalDate.now()).toString()
                )
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Default.Add, "Add") }
    }

    edit?.let { e ->
        Editor(
            e,
            onSave   = { vm.save(it); edit = null },
            onDelete = { vm.del(e);   edit = null },
            onClose  = { edit = null }
        )
    }
}

// ─── Swipe-to-Delete wrapper ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDeleteItem(
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else false
        }
    )

    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val color by animateColorAsState(
                targetValue = if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart)
                    MaterialTheme.colorScheme.errorContainer
                else Color.Transparent,
                label = "swipe_bg"
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true
    ) {
        // White/surface background so the item doesn't show as transparent during swipe
        Surface(color = MaterialTheme.colorScheme.surface) {
            content()
        }
    }
}

// ─── Chip row helper ─────────────────────────────────────────────────────────

@Composable
fun Chips(options: List<String>, selected: String, onPick: (String) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { FilterChip(selected == it, { onPick(it) }, { Text(it) }) }
    }
}

// ─── Editor dialog ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Editor(e: Entry, onSave: (Entry) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    var x by remember { mutableStateOf(e) }

    // Time picker dialog state
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker   by remember { mutableStateOf(false) }

    // Date picker dialog state (dreams)
    var showDatePicker  by remember { mutableStateOf(false) }

    // Parse HH:mm to hour/minute for the picker
    fun parseHour(t: String)   = t.split(":").firstOrNull()?.toIntOrNull() ?: 9
    fun parseMinute(t: String) = t.split(":").lastOrNull()?.toIntOrNull() ?: 0
    fun fmtTime(h: Int, m: Int) = "%02d:%02d".format(h, m)

    val valid = x.title.isNotBlank() &&
        (x.type != "block" || (x.end > x.start))

    // ── Start time picker ────────────────────────────────────────────────
    if (showStartPicker) {
        val tps = rememberTimePickerState(
            initialHour   = parseHour(x.start),
            initialMinute = parseMinute(x.start),
            is24Hour      = true
        )
        TimePickerDialog(
            title     = "Start time",
            onConfirm = { x = x.copy(start = fmtTime(tps.hour, tps.minute)); showStartPicker = false },
            onDismiss = { showStartPicker = false }
        ) { TimePicker(state = tps) }
    }

    // ── End time picker ──────────────────────────────────────────────────
    if (showEndPicker) {
        val tpe = rememberTimePickerState(
            initialHour   = parseHour(x.end),
            initialMinute = parseMinute(x.end),
            is24Hour      = true
        )
        TimePickerDialog(
            title     = "End time",
            onConfirm = { x = x.copy(end = fmtTime(tpe.hour, tpe.minute)); showEndPicker = false },
            onDismiss = { showEndPicker = false }
        ) { TimePicker(state = tpe) }
    }

    // ── Date picker (dreams) ─────────────────────────────────────────────
    if (showDatePicker) {
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = runCatching {
                LocalDate.parse(x.date).toEpochDay() * 86_400_000L
            }.getOrDefault(System.currentTimeMillis())
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { ms ->
                        val d = java.time.Instant.ofEpochMilli(ms)
                            .atZone(java.time.ZoneOffset.UTC)
                            .toLocalDate()
                        x = x.copy(date = d.toString())
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton({ showDatePicker = false }) { Text("Cancel") }
            }
        ) { DatePicker(state = dpState) }
    }

    // ── Main editor AlertDialog ──────────────────────────────────────────
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (e.id.isEmpty()) "New entry" else "Edit entry") },
        confirmButton = {
            TextButton(
                onClick  = { onSave(x.copy(upd = System.currentTimeMillis())) },
                enabled  = valid
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (e.id.isNotEmpty())
                    TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onClose)  { Text("Cancel") }
            }
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Title
                OutlinedTextField(
                    x.title, { x = x.copy(title = it) },
                    Modifier.fillMaxWidth(),
                    label = { Text("Title") }
                )

                // Timebox: time pickers
                if (x.type == "block") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            x.start, {},
                            Modifier
                                .weight(1f)
                                .clickable { showStartPicker = true },
                            label    = { Text("Start") },
                            enabled  = false,
                            singleLine = true
                        )
                        OutlinedTextField(
                            x.end, {},
                            Modifier
                                .weight(1f)
                                .clickable { showEndPicker = true },
                            label    = { Text("End") },
                            enabled  = false,
                            singleLine = true
                        )
                    }
                    if (x.end <= x.start)
                        Text(
                            "End time must be after start",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                }

                // Dream: date picker
                if (x.type == "dream") {
                    OutlinedTextField(
                        x.date, {},
                        Modifier.fillMaxWidth().clickable { showDatePicker = true },
                        label   = { Text("Date") },
                        enabled = false
                    )
                }

                // Body
                if (x.type == "note" || x.type == "dream")
                    OutlinedTextField(
                        x.body, { x = x.copy(body = it) },
                        Modifier.fillMaxWidth(),
                        label    = { Text("Details") },
                        minLines = 4
                    )

                // Tag chips
                TAGS[x.type]?.let { Chips(it, x.tag) { s -> x = x.copy(tag = s) } }

                // Dream sleep rating
                if (x.type == "dream")
                    Chips((1..5).map { "Sleep $it" }, "Sleep ${x.sleep}") { s ->
                        x = x.copy(sleep = s.last().digitToInt())
                    }

                // Bucket status + year
                if (x.type == "bucket") {
                    Chips(STATUS, x.status) { s -> x = x.copy(status = s) }
                    OutlinedTextField(
                        if (x.year > 0) x.year.toString() else "",
                        { s -> x = x.copy(year = s.filter(Char::isDigit).take(4).toIntOrNull() ?: 0) },
                        Modifier.fillMaxWidth(),
                        label = { Text("Target year (optional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
        }
    )
}

// ─── Time Picker Dialog wrapper ──────────────────────────────────────────────

@Composable
fun TimePickerDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text(title) },
        text    = { content() },
        confirmButton = { TextButton(onConfirm) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

// ─── Login screen ────────────────────────────────────────────────────────────

@Composable
fun Login(vm: VM) {
    var em  by remember { mutableStateOf("") }
    var pw  by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }   // success message (e.g. reset email sent)

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp),
        Arrangement.Center
    ) {
        Text("Daybook", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            em, { em = it; err = ""; msg = "" },
            Modifier.fillMaxWidth(),
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            pw, { pw = it; err = ""; msg = "" },
            Modifier.fillMaxWidth(),
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        Spacer(Modifier.height(12.dp))

        // Sign in / Create account buttons
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                err = ""; msg = ""
                vm.signIn(em.trim(), pw) { err = it }
            }) { Text("Sign in") }

            TextButton({
                err = ""; msg = ""
                vm.signUp(em.trim(), pw) { err = it }
            }) { Text("Create account") }
        }

        // Forgot password link
        TextButton(
            onClick = {
                err = ""; msg = ""
                vm.forgotPassword(em) { result ->
                    if (result == null)
                        msg = "Reset email sent! Check your inbox."
                    else
                        err = result
                }
            },
            contentPadding = PaddingValues(0.dp)
        ) {
            Text(
                "Forgot password?",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Error message
        if (err.isNotEmpty())
            Text(
                err,
                color    = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
                style    = MaterialTheme.typography.bodySmall
            )

        // Success message
        if (msg.isNotEmpty())
            Text(
                msg,
                color    = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
                style    = MaterialTheme.typography.bodySmall
            )
    }
}
