package com.example.daybook

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Plays a recording made of several consecutive WAV chunks as ONE continuous timeline
 * (positions are global ms from recording start, the same clock as TimedSegment).
 * Chunks are played one after another; seeking maps a global position to chunk + offset.
 * Call [tick] periodically while [playing] to refresh [positionMs]; call [release] when done.
 */
class RecordingPlayer(
    private val files: List<File>,
    private val durations: List<Long>
) {
    private var mp: MediaPlayer? = null
    private var index = -1

    val totalMs: Long = durations.sum()

    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun clearError() { error = null }

    /** Start (or restart) playback at a global position. */
    fun playFrom(globalMs: Long) {
        if (files.isEmpty() || totalMs <= 0) return
        val g = globalMs.coerceIn(0L, totalMs - 1)
        var i = 0
        var acc = 0L
        while (i < durations.size - 1 && g >= acc + durations[i]) { acc += durations[i]; i++ }
        positionMs = g
        open(i, (g - acc).toInt())
    }

    /** Play / pause. After a seek while paused, resumes from the seeked position. */
    fun toggle() {
        when {
            playing -> { tick(); mp?.pause(); playing = false }
            mp != null -> { mp?.start(); playing = true }
            else -> playFrom(positionMs)
        }
    }

    /** Move the playhead; keeps playing if it was playing, otherwise just remembers the position. */
    fun seek(globalMs: Long) {
        positionMs = globalMs.coerceIn(0L, totalMs)
        if (playing) playFrom(positionMs) else releasePlayer()
    }

    /** Refresh [positionMs] from the underlying player. */
    fun tick() {
        val p = mp ?: return
        if (index !in files.indices) return
        try {
            positionMs = durations.take(index).sum() + p.currentPosition
        } catch (_: IllegalStateException) { /* player released meanwhile */ }
    }

    fun release() {
        releasePlayer()
        playing = false
    }

    private fun open(i: Int, localMs: Int) {
        releasePlayer()
        val f = files[i]
        if (!f.exists()) {
            error = "File audio bagian ${i + 1} tidak ditemukan"
            playing = false
            return
        }
        val p = MediaPlayer()
        mp = p
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            p.setDataSource(f.absolutePath)
            p.prepare()
            if (localMs > 0) p.seekTo(localMs)
            p.setOnCompletionListener { next(i) }
            p.setOnErrorListener { _, _, _ ->
                error = "Gagal memutar audio"
                releasePlayer()
                playing = false
                true
            }
            p.start()
            index = i
            playing = true
            error = null
        } catch (e: Exception) {
            releasePlayer()
            playing = false
            error = "Gagal memutar audio: ${e.message}"
        }
    }

    private fun next(i: Int) {
        if (i + 1 < files.size) {
            open(i + 1, 0)
        } else {
            releasePlayer()
            playing = false
            positionMs = 0L
        }
    }

    private fun releasePlayer() {
        mp?.let {
            try { it.setOnCompletionListener(null); it.setOnErrorListener(null); it.release() } catch (_: Exception) {}
        }
        mp = null
        index = -1
    }
}
