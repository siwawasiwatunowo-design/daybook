package com.example.daybook

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Chunk status values for a recording's chunk.
 */
// RECORDED -> TRANSCRIBING -> TRANSCRIBED | FAILED

/** Metadata for one audio chunk of a recording. */
data class Chunk(
    val index: Int,                 // 1-based, ordering
    val fileName: String,           // e.g. chunk_0001.wav
    val durationMs: Long = 0,
    val status: String = "RECORDED",// RECORDED | TRANSCRIBING | TRANSCRIBED | FAILED
    val transcript: String = ""
)

/** Metadata for one recording session. Audio files live under filesDir/recordings/{dirName}. */
data class Recording(
    val id: String,
    val title: String,
    val createdAt: Long,
    val dirName: String,
    val status: String,             // RECORDING | PROCESSING | DONE | FAILED
    val chunks: List<Chunk> = emptyList(),
    val mom: String = ""
)

/**
 * In-memory + JSON-persisted store of recordings, observed by the UI as a
 * StateFlow. The foreground service and the processing workers mutate this;
 * the Recordings tab observes it. Persisted to filesDir/recordings/index.json
 * so it survives process death. Audio blob bytes are never stored here — only
 * metadata + transcripts + MoM text.
 */
object RecordingRepository {
    private lateinit var appContext: Context
    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        load()
    }

    fun recordingsDir(): File =
        File(appContext.filesDir, "recordings").apply { if (!exists()) mkdirs() }

    fun dir(id: String): File = File(recordingsDir(), id).apply { mkdirs() }

    fun chunkFile(id: String, fileName: String): File = File(dir(id), fileName)

    private fun indexFile() = File(recordingsDir(), "index.json")

    @Synchronized
    fun get(id: String): Recording? = _recordings.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(rec: Recording) {
        val list = _recordings.value.toMutableList()
        val i = list.indexOfFirst { it.id == rec.id }
        if (i >= 0) list[i] = rec else list.add(0, rec)
        _recordings.value = list
        save()
    }

    @Synchronized
    fun update(id: String, block: (Recording) -> Recording) {
        val cur = _recordings.value.firstOrNull { it.id == id } ?: return
        upsert(block(cur))
    }

    @Synchronized
    fun remove(id: String) {
        _recordings.value = _recordings.value.filterNot { it.id == id }
        dir(id).deleteRecursively()
        save()
    }

    private fun save() {
        try {
            val arr = JSONArray()
            _recordings.value.forEach { r ->
                val chunks = JSONArray()
                r.chunks.forEach { c ->
                    chunks.put(JSONObject()
                        .put("index", c.index)
                        .put("fileName", c.fileName)
                        .put("durationMs", c.durationMs)
                        .put("status", c.status)
                        .put("transcript", c.transcript))
                }
                arr.put(JSONObject()
                    .put("id", r.id)
                    .put("title", r.title)
                    .put("createdAt", r.createdAt)
                    .put("dirName", r.dirName)
                    .put("status", r.status)
                    .put("chunks", chunks)
                    .put("mom", r.mom))
            }
            indexFile().writeText(arr.toString())
        } catch (_: Exception) { /* best effort */ }
    }

    private fun load() {
        try {
            val f = indexFile()
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            val list = mutableListOf<Recording>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val chunks = mutableListOf<Chunk>()
                val ca = o.optJSONArray("chunks") ?: JSONArray()
                for (j in 0 until ca.length()) {
                    val co = ca.getJSONObject(j)
                    chunks.add(Chunk(
                        co.optInt("index", j + 1),
                        co.optString("fileName"),
                        co.optLong("durationMs", 0),
                        co.optString("status", "RECORDED"),
                        co.optString("transcript", "")))
                }
                list.add(Recording(
                    o.optString("id"),
                    o.optString("title"),
                    o.optLong("createdAt", 0),
                    o.optString("dirName", o.optString("id")),
                    o.optString("status", "RECORDED"),
                    chunks,
                    o.optString("mom", "")))
            }
            _recordings.value = list
        } catch (_: Exception) { /* best effort */ }
    }
}
