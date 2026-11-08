package com.example.daybook

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * A user-placed mark during a recording (§6.3). Offset is from recording start,
 * derived from total sample count so it lines up with audio + transcript.
 */
data class Mark(
    val offsetMs: Long,            // from recording start
    val type: String,              // BINGUNG | PENTING
    val note: String = ""          // optional, filled in after the meeting
)

/**
 * One timed transcript segment (§7). Times are milliseconds; for per-chunk
 * storage they are relative to the chunk start, [combinedSegments] makes them global.
 */
data class TimedSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/** Metadata for one audio chunk of a recording. */
data class Chunk(
    val index: Int,                 // 1-based, ordering
    val fileName: String,           // chunk_NNNN.wav (raw .pcm is deleted after wrap)
    val durationMs: Long = 0,
    val status: String = "RECORDED",// RECORDED | TRANSCRIBING | TRANSCRIBED | FAILED
    val transcript: String = "",
    val segments: List<TimedSegment> = emptyList()
)

/** Metadata for one recording session. Audio files live under filesDir/recordings/{dirName}. */
data class Recording(
    val id: String,
    val title: String,
    val createdAt: Long,
    val dirName: String,
    val status: String,             // RECORDING | PROCESSING | DONE | FAILED | INTERRUPTED | PARTIAL
    val chunks: List<Chunk> = emptyList(),
    val mom: String = "",           // JSON string of MoM (see MomExtractor.MoM)
    val marks: List<Mark> = emptyList(),
    val message: String = ""        // human note, e.g. "Rekaman terputus di menit N"
)

/** "62:15" or "1:02:15". */
fun formatClock(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/** "19.3 MB" / "1.4 GB". */
fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%d KB".format(bytes / 1024)
}

/**
 * In-memory + JSON-persisted store of recordings, observed by the UI as a
 * StateFlow. The foreground service and the STT worker mutate this; the
 * Recordings tab observes it. Persisted atomically (temp + rename) to
 * filesDir/recordings/index.json so it survives process death. Audio blob
 * bytes are never stored here — only metadata + transcripts + MoM text.
 */
object RecordingRepository {
    private lateinit var appContext: Context
    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    /** The recording currently in progress (null when idle). UI uses this. */
    private val _activeId = MutableStateFlow<String?>(null)
    val activeRecordingId: StateFlow<String?> = _activeId.asStateFlow()

    /** Live mic level (0..1) for the sound indicator while recording. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        indexCorrupt = !load()
    }

    /** True if index.json existed but could not be parsed; recoverInterrupted() then rebuilds from disk. */
    private var indexCorrupt = false

    fun recordingsDir(): File = File(appContext.filesDir, "recordings").apply { if (!exists()) mkdirs() }
    fun dir(id: String): File = File(recordingsDir(), id).apply { mkdirs() }
    fun chunkFile(id: String, fileName: String): File = File(dir(id), fileName)

    private fun indexFile() = File(recordingsDir(), "index.json")

    @Synchronized fun get(id: String): Recording? = _recordings.value.firstOrNull { it.id == id }

    /** Guard (§6.2): only one recording may be active at a time. */
    @Synchronized fun isAnyRecording(): Boolean = _recordings.value.any { it.status == "RECORDING" }

    fun setActive(id: String?) { _activeId.value = id }
    fun setLevel(rms: Float) { _level.value = rms.coerceIn(0f, 1f) }
    fun clearLevel() { _level.value = 0f }

    @Synchronized fun upsert(rec: Recording) {
        val list = _recordings.value.toMutableList()
        val i = list.indexOfFirst { it.id == rec.id }
        if (i >= 0) list[i] = rec else list.add(0, rec)
        _recordings.value = list
        save()
    }

    @Synchronized fun update(id: String, block: (Recording) -> Recording) {
        val cur = _recordings.value.firstOrNull { it.id == id } ?: return
        upsert(block(cur))
    }

    @Synchronized fun updateChunk(id: String, index: Int, block: (Chunk) -> Chunk) {
        update(id) { rec ->
            rec.copy(chunks = rec.chunks.map { if (it.index == index) block(it) else it })
        }
    }

    @Synchronized fun setChunkStatus(id: String, index: Int, status: String) {
        updateChunk(id, index) { it.copy(status = status) }
    }

    @Synchronized fun addChunk(id: String, chunk: Chunk) {
        update(id) { rec -> rec.copy(chunks = rec.chunks + chunk) }
    }

    @Synchronized fun addMark(id: String, mark: Mark) {
        update(id) { rec -> rec.copy(marks = rec.marks + mark) }
    }

    @Synchronized fun setMarkNote(id: String, offsetMs: Long, note: String) {
        update(id) { rec ->
            rec.copy(marks = rec.marks.map { if (it.offsetMs == offsetMs) it.copy(note = note) else it })
        }
    }

    fun rename(id: String, title: String) {
        update(id) { it.copy(title = title.trim().ifBlank { it.title }) }
    }

    /** Total bytes of audio files on disk for a recording. */
    fun diskBytes(id: String): Long =
        dir(id).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    @Synchronized fun setMom(id: String, momJson: String) {
        update(id) { it.copy(mom = momJson) }
    }

    @Synchronized fun remove(id: String) {
        _recordings.value = _recordings.value.filterNot { it.id == id }
        dir(id).deleteRecursively()
        save()
    }

    /** All chunk transcripts concatenated in order (plain text). */
    fun fullTranscript(id: String): String =
        get(id)?.chunks?.joinToString(" ") { it.transcript }?.trim() ?: ""

    /** Chunk segments turned into a global-offset timeline (ms from recording start). */
    fun combinedSegments(id: String): List<TimedSegment> {
        val rec = get(id) ?: return emptyList()
        val out = mutableListOf<TimedSegment>()
        var base = 0L
        rec.chunks.forEach { c ->
            c.segments.forEach { s ->
                out.add(TimedSegment(base + s.startMs, base + s.endMs, s.text))
            }
            base += c.durationMs
        }
        return out
    }

    /**
     * Crash recovery (§6.4). Run once per process start, OFF the main thread
     * (wrapping a 19 MB chunk takes a moment): any recording still marked
     * RECORDING while no service is capturing it is stale. Leftover .pcm chunks
     * are wrapped to WAV, then the chunk list is REBUILT from the files on disk
     * (keeping existing metadata such as transcripts), and the recording becomes
     * INTERRUPTED with a "terputus di menit N" message.
     *
     * Disk work happens outside the repository lock so the UI is never blocked,
     * and the final update is applied to the CURRENT entry (not a stale snapshot).
     *
     * @return ids of recordings that were salvaged and have audio (candidates for STT)
     */
    fun recoverInterrupted(): List<String> {
        if (indexCorrupt) rebuildFromDisk()
        val stale = _recordings.value.filter { it.status == "RECORDING" && RecordingService.activeId != it.id }
        val salvaged = mutableListOf<String>()
        stale.forEach { rec ->
            try {
                val d = dir(rec.dirName)
                d.listFiles { f -> f.isFile && f.extension == "pcm" }?.sortedBy { it.name }?.forEach { pcm ->
                    try {
                        WavIO.wrapPcmToWav(pcm, File(d, pcm.nameWithoutExtension + ".wav"))
                        pcm.delete()
                    } catch (_: Exception) { /* keep the .pcm: nothing is lost, retried next start */ }
                }
                d.listFiles { f -> f.isFile && f.name.endsWith(".tmp") }?.forEach { it.delete() }
                val wavs = d.listFiles { f -> f.isFile && f.extension == "wav" }?.sortedBy { it.name } ?: emptyList()
                val rx = Regex("chunk_(\\d+)\\.wav")
                update(rec.id) { cur ->
                    val byName = cur.chunks.associateBy { it.fileName }
                    val chunks = wavs.mapIndexed { i, f ->
                        byName[f.name]?.copy(durationMs = WavIO.durationMs(f))
                            ?: Chunk(rx.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull() ?: (i + 1),
                                f.name, WavIO.durationMs(f))
                    }
                    val minutes = chunks.sumOf { it.durationMs } / 60000
                    cur.copy(
                        chunks = chunks,
                        status = if (chunks.isEmpty()) "FAILED" else "INTERRUPTED",
                        message = if (chunks.isEmpty()) "Rekaman gagal: tidak ada audio tersimpan"
                                  else "Rekaman terputus di menit $minutes"
                    )
                }
                if (wavs.isNotEmpty()) salvaged.add(rec.id)
            } catch (_: Exception) { /* best effort per recording */ }
        }
        if (RecordingService.activeId == null) { _activeId.value = null; clearLevel() }
        return salvaged
    }

    /** index.json missing/corrupt: re-create entries for recording folders found on disk. */
    private fun rebuildFromDisk() {
        indexCorrupt = false
        val known = _recordings.value.map { it.dirName }.toSet()
        recordingsDir().listFiles()?.filter { it.isDirectory && it.name !in known }?.forEach { d ->
            val hasAudio = d.listFiles()?.any { it.extension == "pcm" || it.extension == "wav" } == true
            if (hasAudio) upsert(Recording(d.name, "Rekaman dipulihkan", d.lastModified(), d.name, "RECORDING"))
        }
    }

    // ── Persistence (atomic) ──────────────────────────────────────────────
    @Synchronized private fun save() {
        val tmp = File(recordingsDir(), "index.json.tmp")
        try {
            val arr = JSONArray()
            _recordings.value.forEach { r ->
                val chunks = JSONArray()
                r.chunks.forEach { c ->
                    val segs = JSONArray()
                    c.segments.forEach { s ->
                        segs.put(JSONObject()
                            .put("startMs", s.startMs)
                            .put("endMs", s.endMs)
                            .put("text", s.text))
                    }
                    chunks.put(JSONObject()
                        .put("index", c.index)
                        .put("fileName", c.fileName)
                        .put("durationMs", c.durationMs)
                        .put("status", c.status)
                        .put("transcript", c.transcript)
                        .put("segments", segs))
                }
                val marks = JSONArray()
                r.marks.forEach { m ->
                    marks.put(JSONObject()
                        .put("offsetMs", m.offsetMs)
                        .put("type", m.type)
                        .put("note", m.note))
                }
                arr.put(JSONObject()
                    .put("id", r.id)
                    .put("title", r.title)
                    .put("createdAt", r.createdAt)
                    .put("dirName", r.dirName)
                    .put("status", r.status)
                    .put("chunks", chunks)
                    .put("mom", r.mom)
                    .put("marks", marks)
                    .put("message", r.message))
            }
            FileOutputStream(tmp).use { out ->
                out.write(arr.toString().toByteArray(Charsets.UTF_8))
                out.flush(); out.fd.sync()          // data is on disk BEFORE the rename
            }
            // Same filesystem: rename is atomic, so a crash never leaves a half-written index.
            if (!tmp.renameTo(indexFile())) {
                // Fallback: copy then delete temp.
                tmp.copyTo(indexFile(), overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {
            tmp.delete()
        }
    }

    /** @return true if the index was absent or parsed fine; false if it exists but is corrupt. */
    private fun load(): Boolean {
        return try {
            val f = indexFile()
            if (!f.exists()) return true
            val arr = JSONArray(f.readText())
            val list = mutableListOf<Recording>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val chunks = mutableListOf<Chunk>()
                val ca = o.optJSONArray("chunks") ?: JSONArray()
                for (j in 0 until ca.length()) {
                    val co = ca.getJSONObject(j)
                    val segs = mutableListOf<TimedSegment>()
                    val sa = co.optJSONArray("segments") ?: JSONArray()
                    for (k in 0 until sa.length()) {
                        val so = sa.getJSONObject(k)
                        segs.add(TimedSegment(so.optLong("startMs"), so.optLong("endMs"), so.optString("text")))
                    }
                    chunks.add(Chunk(
                        co.optInt("index", j + 1),
                        co.optString("fileName"),
                        co.optLong("durationMs", 0),
                        co.optString("status", "RECORDED"),
                        co.optString("transcript", ""),
                        segs
                    ))
                }
                val marks = mutableListOf<Mark>()
                val ma = o.optJSONArray("marks") ?: JSONArray()
                for (j in 0 until ma.length()) {
                    val mo = ma.getJSONObject(j)
                    marks.add(Mark(mo.optLong("offsetMs"), mo.optString("type"), mo.optString("note")))
                }
                list.add(Recording(
                    o.optString("id"),
                    o.optString("title"),
                    o.optLong("createdAt", 0),
                    o.optString("dirName", o.optString("id")),
                    o.optString("status", "RECORDED"),
                    chunks,
                    o.optString("mom", ""),
                    marks,
                    o.optString("message", "")
                ))
            }
            _recordings.value = list
            true
        } catch (_: Exception) { false }
    }
}
