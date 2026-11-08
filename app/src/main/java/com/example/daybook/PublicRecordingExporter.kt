package com.example.daybook

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/**
 * Publishes recording assets to the user's Documents folder.
 *
 * Android 10+: MediaStore makes these files visible in File Manager without
 * broad storage permission. The private filesDir copy remains the working copy
 * used by recording recovery and STT.
 */
object PublicRecordingExporter {
    private fun relativePath(recordingId: String): String {
        val safeId = recordingId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "Documents/Daybook/Recordings/$safeId/"
    }

    private fun findExisting(context: Context, path: String, name: String): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        context.contentResolver.query(collection, projection, selection, arrayOf(path, name), null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return Uri.withAppendedPath(collection, cursor.getLong(0).toString())
            }
        }
        return null
    }

    private fun publish(
        context: Context,
        recordingId: String,
        name: String,
        mime: String,
        write: (java.io.OutputStream) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < 29) {
            // Scoped-storage publication is supported on Android 10+. On older
            // devices retain the private source rather than silently requesting
            // broad storage access or writing to an inaccessible location.
            return
        }
        val resolver = context.contentResolver
        val path = relativePath(recordingId)
        val existing = findExisting(context, path, name)
        val uri = existing ?: run {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            resolver.insert(MediaStore.Files.getContentUri("external"), values)
                ?: throw IllegalStateException("Tidak bisa membuat file publik: $name")
        }
        resolver.openOutputStream(uri, "wt")?.use(write)
            ?: throw IllegalStateException("Tidak bisa menulis file publik: $name")
        if (existing == null) {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
    }

    fun exportAudioChunk(context: Context, recordingId: String, source: File) {
        if (!source.exists() || source.length() <= WavIO.HEADER_SIZE) return
        publish(context, recordingId, source.name, "audio/wav") { out ->
            source.inputStream().use { it.copyTo(out) }
        }
    }

    fun exportRecording(context: Context, recording: Recording) {
        val audioFiles = recording.chunks.sortedBy { it.index }
            .map { RecordingRepository.chunkFile(recording.id, it.fileName) }
            .filter { it.exists() && it.length() > WavIO.HEADER_SIZE }
        if (audioFiles.isNotEmpty()) {
            val dataLength = audioFiles.sumOf { it.length() - WavIO.HEADER_SIZE }
            publish(context, recording.id, "recording.wav", "audio/wav") { out ->
                WavIO.writeWavHeader(out, dataLen = dataLength)
                audioFiles.forEach { wav ->
                    wav.inputStream().use { input ->
                        var remaining = WavIO.HEADER_SIZE.toLong()
                        while (remaining > 0) {
                            val skipped = input.skip(remaining)
                            if (skipped <= 0) break
                            remaining -= skipped
                        }
                        input.copyTo(out)
                    }
                }
            }
        }
        exportTextAssets(context, recording)
    }

    fun exportTextAssets(context: Context, recording: Recording) {
        val transcript = recording.chunks.sortedBy { it.index }
            .joinToString("\n\n") { it.transcript.trim() }
            .trim()
        publish(context, recording.id, "transcript.txt", "text/plain") { out ->
            out.write(transcript.toByteArray(Charsets.UTF_8))
        }

        val transcriptJson = org.json.JSONArray().apply {
            recording.chunks.sortedBy { it.index }.forEach { chunk ->
                put(org.json.JSONObject()
                    .put("chunk", chunk.index)
                    .put("audioFile", chunk.fileName)
                    .put("durationMs", chunk.durationMs)
                    .put("status", chunk.status)
                    .put("text", chunk.transcript)
                    .put("segments", org.json.JSONArray().apply {
                        chunk.segments.forEach { segment ->
                            put(org.json.JSONObject()
                                .put("startMs", segment.startMs)
                                .put("endMs", segment.endMs)
                                .put("text", segment.text))
                        }
                    })
                )
            }
        }
        publish(context, recording.id, "transcript.json", "application/json") { out ->
            out.write(transcriptJson.toString(2).toByteArray(Charsets.UTF_8))
        }

        val metadata = org.json.JSONObject()
            .put("id", recording.id)
            .put("title", recording.title)
            .put("createdAt", recording.createdAt)
            .put("status", recording.status)
            .put("message", recording.message)
            .put("durationMs", recording.chunks.sumOf { it.durationMs })
            .put("audioFiles", org.json.JSONArray().apply {
                recording.chunks.sortedBy { it.index }.forEach { put(it.fileName) }
            })
            .put("marks", org.json.JSONArray().apply {
                recording.marks.forEach { mark ->
                    put(org.json.JSONObject()
                        .put("offsetMs", mark.offsetMs)
                        .put("type", mark.type)
                        .put("note", mark.note))
                }
            })
        publish(context, recording.id, "recording.json", "application/json") { out ->
            out.write(metadata.toString(2).toByteArray(Charsets.UTF_8))
        }

        if (recording.mom.isNotBlank()) {
            publish(context, recording.id, "mom.json", "application/json") { out ->
                out.write(recording.mom.toByteArray(Charsets.UTF_8))
            }
        }
    }

    fun deleteRecording(context: Context, recordingId: String) {
        if (Build.VERSION.SDK_INT < 29) return
        val path = relativePath(recordingId)
        val collection = MediaStore.Files.getContentUri("external")
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        context.contentResolver.delete(collection, selection, arrayOf(path))
    }
}
