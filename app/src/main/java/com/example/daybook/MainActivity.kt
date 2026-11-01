package com.example.daybook

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

// One Firestore collection for all four sections; "type" says which section an entry belongs to.
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

class VM : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    val user = MutableStateFlow<FirebaseUser?>(auth.currentUser)
    init { auth.addAuthStateListener { user.value = it.currentUser } }

    private fun col() = FirebaseFirestore.getInstance().collection("users")
        .document(auth.currentUser!!.uid).collection("entries")

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
    fun del(e: Entry) { col().document(e.id).delete() }
    fun signIn(em: String, pw: String, fail: (String) -> Unit) {
        auth.signInWithEmailAndPassword(em, pw).addOnFailureListener { fail(it.message ?: "Failed") }
    }
    fun signUp(em: String, pw: String, fail: (String) -> Unit) {
        auth.createUserWithEmailAndPassword(em, pw).addOnFailureListener { fail(it.message ?: "Failed") }
    }
    fun signOut() = auth.signOut()
}

val TABS = listOf(
    Triple("block", "Timebox", "🗓"), Triple("note", "Notes", "📝"),
    Triple("dream", "Dreams", "🌙"), Triple("bucket", "Bucket list", "⭐")
)
val TAGS = mapOf(
    "note" to listOf("Journal", "Learning", "Thoughts", "Ideas"),
    "dream" to listOf("Normal", "Recurring", "Nightmare", "Lucid"),
    "bucket" to listOf("Travel", "Growth", "Career", "Health", "Family", "Finance", "Adventure", "Other")
)
val STATUS = listOf("Dream", "Planned", "Done")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val scheme = if (Build.VERSION.SDK_INT >= 31)
                (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx))
            else (if (dark) darkColorScheme() else lightColorScheme())
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App(vm: VM = viewModel()) {
    val user by vm.user.collectAsState()
    key(user?.uid) { if (user == null) Login(vm) else Home(vm) }
}

@Composable
fun Home(vm: VM) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp), Arrangement.End) {
            TextButton({ vm.signOut() }) { Text("Sign out") }
        }
    }, bottomBar = {
        NavigationBar {
            TABS.forEachIndexed { i, (_, label, icon) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i },
                    icon = { Text(icon) }, label = { Text(label) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            key(tab) { Screen(TABS[tab].first, vm) }
        }
    }
}

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
                "dream" -> r.sortedByDescending { it.date }
                "bucket" -> r.sortedBy { STATUS.indexOf(it.status).let { i -> if (i == 1) 0 else if (i == 0) 1 else 2 } }
                else -> r
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
                val done = rows.count { it.done }
                Text("$done of ${rows.size} done", Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(12.dp, 8.dp),
                    label = { Text("Search") }, singleLine = true)
            }
            if (rows.isEmpty()) Text("Nothing here yet. Tap + to add.", Modifier.padding(16.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                items(rows, key = { it.id }) { e ->
                    ListItem(
                        modifier = Modifier.clickable { edit = e },
                        headlineContent = {
                            Text(e.title, textDecoration =
                                if (e.done || e.status == "Done" && t == "bucket") TextDecoration.LineThrough else null)
                        },
                        supportingContent = {
                            val line = when (t) {
                                "block" -> "${e.start} - ${e.end}"
                                "note" -> e.tag + if (e.body.isNotBlank()) "\n${e.body}" else ""
                                "dream" -> "${e.date}, ${e.tag}, sleep ${e.sleep}/5" +
                                    if (e.body.isNotBlank()) "\n${e.body}" else ""
                                else -> e.tag + if (e.year > 0) ", ${e.year}" else ""
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
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(
            onClick = {
                edit = Entry(type = t, title = "", tag = TAGS[t]?.first() ?: "",
                    date = (if (t == "block") day else LocalDate.now()).toString())
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Default.Add, "Add") }
    }

    edit?.let { e ->
        Editor(e, onSave = { vm.save(it); edit = null },
            onDelete = { vm.del(e); edit = null }, onClose = { edit = null })
    }
}

@Composable
fun Chips(options: List<String>, selected: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { FilterChip(selected == it, { onPick(it) }, { Text(it) }) }
    }
}

@Composable
fun Editor(e: Entry, onSave: (Entry) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    var x by remember { mutableStateOf(e) }
    val time = Regex("\\d{2}:\\d{2}")
    val valid = x.title.isNotBlank() &&
        (x.type != "block" || (time.matches(x.start) && time.matches(x.end) && x.end > x.start))
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (e.id == 0L) "New entry" else "Edit entry") },
        confirmButton = {
            TextButton(onClick = { onSave(x.copy(upd = System.currentTimeMillis())) }, enabled = valid) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (e.id != 0L) TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(x.title, { x = x.copy(title = it) }, Modifier.fillMaxWidth(), label = { Text("Title") })
                if (x.type == "block") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(x.start, { x = x.copy(start = it) }, Modifier.weight(1f), label = { Text("Start HH:mm") }, singleLine = true)
                    OutlinedTextField(x.end, { x = x.copy(end = it) }, Modifier.weight(1f), label = { Text("End HH:mm") }, singleLine = true)
                }
                if (x.type == "note" || x.type == "dream")
                    OutlinedTextField(x.body, { x = x.copy(body = it) }, Modifier.fillMaxWidth(), label = { Text("Details") }, minLines = 4)
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
fun Login(vm: VM) {
    var em by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), Arrangement.Center) {
        Text("Daybook", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(em, { em = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ err = ""; vm.signIn(em.trim(), pw) { err = it } }) { Text("Sign in") }
            TextButton({ err = ""; vm.signUp(em.trim(), pw) { err = it } }) { Text("Create account") }
        }
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
    }
}
