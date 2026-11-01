package com.example.daybook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Foreground recording service (microphone type). Records 16kHz mono 16-bit
 * PCM WAV, split into chunks (default 10 min) under filesDir/recordings/{id}/.
 *
 * Decoupled from any Activity/Note: it just records + persists chunks + updates
 * RecordingRepository. The Recordings tab observes the repo. Survives screen
 * off / app background as long as the system allows the foreground service.
 *
 * STT/MoM processing is NOT done here — that is the job of WorkManager workers
 * (added in a later step), which run async in the background after recording.
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var recorder: AudioRecord? = null
    private var recordJob: Job? = null
    private var tickJob: Job? = null
    private var currentFile: File? = null
    private var fos: FileOutputStream? = null
    private var recordingId: String = ""
    private var title: String = "Meeting"
    private var chunkIndex = 0
    private var chunkStartMs = 0L
    private var totalMs = 0L

    private val sampleRate = 16000

    companion object {
        const val CHANNEL_ID = "daybook_recording"
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val ACTION_START = "com.example.daybook.START"
        const val ACTION_STOP = "com.example.daybook.STOP"
        const val CHUNK_MS = 10L * 60L * 1000L // 10 minutes
        private const val NOTIFY_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                recordingId = intent.getStringExtra(EXTRA_ID) ?: System.currentTimeMillis().toString()
                title = intent.getStringExtra(EXTRA_TITLE) ?: "Meeting"
                ensureChannel()
                startForeground(NOTIFY_ID, buildNotification("Merekam…"))
                startRecording()
            }
            ACTION_STOP -> { stopRecording(); finish() }
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        RecordingRepository.upsert(
            Recording(recordingId, title, System.currentTimeMillis(), recordingId, "RECORDING", emptyList(), "")
        )
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFmt = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFmt)
        if (minBuf < 0) { finish(); return }
        val bufSize = (minBuf * 4).coerceAtLeast(3200)

        @Suppress("MissingPermission")
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFmt, bufSize)
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); finish(); return
        }
        recorder = rec
        rec.startRecording()
        openNewChunk()

        recordJob = scope.launch {
            val buffer = ByteArray(bufSize)
            while (isActive) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read > 0) {
                    fos?.write(buffer, 0, read)
                    totalMs += (read.toLong() * 1000L) / (2L * sampleRate) // 16-bit mono: 2 bytes/sample
                    if (System.currentTimeMillis() - chunkStartMs >= CHUNK_MS) {
                        closeCurrentChunk()
                        openNewChunk()
                    }
                } else if (read < 0) break
            }
        }
        tickJob = scope.launch {
            while (isActive) {
                delay(1000)
                updateNotification()
            }
        }
    }

    private fun openNewChunk() {
        chunkIndex++
        val file = File(RecordingRepository.dir(recordingId), "chunk_%04d.wav".format(chunkIndex))
        currentFile = file
        val out = FileOutputStream(file)
        fos = out
        writeWavHeader(out, sampleRate, 1, 16)
        chunkStartMs = System.currentTimeMillis()
        updateNotification()
    }

    private fun closeCurrentChunk() {
        val f = currentFile ?: return
        try { fos?.flush(); fos?.close() } catch (_: Exception) {}
        try {
            RandomAccessFile(f, "rw").use { raf ->
                val dataLen = f.length() - 44
                raf.seek(4); raf.writeInt(Integer.reverseBytes((36 + dataLen).toInt()))
                raf.seek(40); raf.writeInt(Integer.reverseBytes(dataLen.toInt()))
            }
        } catch (_: Exception) {}
        val dur = System.currentTimeMillis() - chunkStartMs
        RecordingRepository.update(recordingId) { rec ->
            rec.copy(chunks = rec.chunks + Chunk(chunkIndex, f.name, dur, "RECORDED", ""))
        }
        currentFile = null
        fos = null
    }

    private fun stopRecording() {
        recordJob?.cancel()
        tickJob?.cancel()
        try { recorder?.stop() } catch (_: Exception) {}
        closeCurrentChunk()
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        RecordingRepository.update(recordingId) { it.copy(status = "PROCESSING") }
        // Processing (STT/MoM) is enqueued by the caller via WorkManager (later step).
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        recordJob?.cancel()
        tickJob?.cancel()
        try { recorder?.release() } catch (_: Exception) {}
        try { fos?.close() } catch (_: Exception) {}
        scope.cancel()
    }

    // ── Notification ────────────────────────────────────────────────────────
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val mm = totalMs / 60000
        val ss = (totalMs % 60000) / 1000
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Daybook — $title")
            .setContentText("$text • chunk $chunkIndex • %02d:%02d".format(mm, ss))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFY_ID, buildNotification("Merekam…"))
        } catch (_: SecurityException) { /* POST_NOTIFICATIONS not granted — service still runs */ }
    }

    // ── WAV header ──────────────────────────────────────────────────────────
    private fun writeWavHeader(out: FileOutputStream, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        out.write("RIFF".toByteArray(Charsets.US_ASCII)); out.write(intLE(0))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII)); out.write(intLE(16))
        out.write(shortLE(1)); out.write(shortLE(channels))
        out.write(intLE(sampleRate)); out.write(intLE(byteRate))
        out.write(shortLE(blockAlign)); out.write(shortLE(bitsPerSample))
        out.write("data".toByteArray(Charsets.US_ASCII)); out.write(intLE(0))
    }
    private fun intLE(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
    private fun shortLE(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
}
