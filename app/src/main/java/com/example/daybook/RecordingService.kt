package com.example.daybook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Live state of the active recording, observed by the Rekam tab. */
data class LiveRecording(
    val id: String,
    val title: String,
    val elapsedMs: Long = 0,
    val level: Float = 0f,            // 0..1, for the sound indicator
    val markCount: Int = 0,
    val lastMarkMs: Long? = null,
    val lastMarkType: String? = null,
    val warning: String? = null       // e.g. mic blocked / silent / low storage
)

/**
 * Foreground recording service (microphone type), built so that nothing already
 * recorded is lost if something goes wrong.
 *
 *  - The microphone is NEVER stopped while rotating chunks. Two threads:
 *      read thread : AudioRecord.read() -> queue   (never waits for the disk)
 *      write thread: queue -> chunk_XXXX.pcm       (rotates files)
 *  - Chunks are raw PCM while recording (no header to corrupt); flushed and
 *    fsync'ed every ~5 s of audio. When a chunk closes it is wrapped into WAV.
 *  - Rotation is by SAMPLE COUNT, not wall-clock: after 10 min, at the next
 *    ~0.5 s pause (adaptive threshold); forced at 10.5 min.
 *  - "Tandai" marks store the audio offset (from samples) and vibrate briefly.
 *  - Partial wake lock, mic-blocked / silent-mic detection, low-storage guard.
 *  - If the process/service dies, RecordingRepository salvages the audio on the
 *    next app start and marks the recording INTERRUPTED.
 *
 * STT/MoM is NOT done here.
 */
class RecordingService : Service() {

    private val running = AtomicBoolean(false)
    private val readerDone = AtomicBoolean(false)
    private val finalizing = AtomicBoolean(false)
    private val samplesRead = AtomicLong(0)
    private val queue = LinkedBlockingQueue<ShortArray>()

    private var recorder: AudioRecord? = null
    private var readThread: Thread? = null
    private var writeThread: Thread? = null
    private var tickThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var recCallback: AudioManager.AudioRecordingCallback? = null

    private var recordingId: String = ""
    private var title: String = "Meeting"
    @Volatile private var endReason: String? = null   // non-null => recording was interrupted

    // writer-thread state
    private var wIndex = 0
    private var wPcm: File? = null
    private var wOut: FileOutputStream? = null
    private var wSamples = 0L

    companion object {
        const val CHANNEL_ID = "daybook_recording"
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MARK_TYPE = "mark_type"
        const val ACTION_START = "com.example.daybook.START"
        const val ACTION_STOP = "com.example.daybook.STOP"
        const val ACTION_MARK = "com.example.daybook.MARK"
        const val MARK_BINGUNG = "BINGUNG"
        const val MARK_PENTING = "PENTING"

        /** Queue STT (SttWorker) automatically when a recording ends. */
        const val AUTO_TRANSCRIBE = true

        const val SAMPLE_RATE = WavIO.SAMPLE_RATE
        const val CHUNK_MIN_SAMPLES = 10L * 60 * SAMPLE_RATE            // 10 min: start looking for a pause
        const val CHUNK_MAX_SAMPLES = CHUNK_MIN_SAMPLES + 30L * SAMPLE_RATE // 10.5 min: forced cut
        private const val FRAME = 320                                   // 20 ms
        private const val SILENCE_RUN_SAMPLES = SAMPLE_RATE / 2L        // 0.5 s of pause
        private const val SYNC_EVERY_SAMPLES = 5L * SAMPLE_RATE         // fsync every ~5 s of audio
        private const val MIN_FREE_START = 200L * 1024 * 1024
        private const val WARN_FREE = 300L * 1024 * 1024
        private const val MIN_FREE_STOP = 50L * 1024 * 1024
        private const val NOTIFY_ID = 1

        const val MSG_MIC_SILENT = "Mic tidak menangkap suara. Cek apakah ada telepon / app lain / headset yang memakai mic."
        const val MSG_MIC_BLOCKED = "Mic sedang dipakai aplikasi lain (mis. telepon). Audio selama itu kosong."
        const val MSG_LOW_STORAGE = "Storage hampir penuh. Rekaman akan dihentikan otomatis jika terlalu sedikit."

        /** Id of the recording being captured right now (null when idle). Process-level. */
        @Volatile var activeId: String? = null

        private val _live = MutableStateFlow<LiveRecording?>(null)
        val live: StateFlow<LiveRecording?> = _live.asStateFlow()

        private val _startError = MutableStateFlow<String?>(null)
        val startError: StateFlow<String?> = _startError.asStateFlow()
        fun clearStartError() { _startError.value = null }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> if (running.get()) requestStop(null) else stopSelf()
            ACTION_MARK -> if (running.get()) addMark(intent.getStringExtra(EXTRA_MARK_TYPE) ?: MARK_BINGUNG) else stopSelf()
            else -> if (!running.get()) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ── Start ───────────────────────────────────────────────────────────────

    @Suppress("MissingPermission") // RECORD_AUDIO is requested by the UI before ACTION_START
    private fun handleStart(intent: Intent) {
        ensureChannel()
        val tentativeTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Meeting"
        // Required within ~5 s of startForegroundService(), so do it first, always.
        try {
            ServiceCompat.startForeground(
                this, NOTIFY_ID, buildNotification(tentativeTitle),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            if (!running.get()) { _startError.value = "Tidak bisa memulai rekaman: ${e.message}"; stopSelf() }
            return
        }
        // Only one active recording: a second START is ignored.
        if (!running.compareAndSet(false, true)) return

        recordingId = intent.getStringExtra(EXTRA_ID) ?: System.currentTimeMillis().toString()
        title = tentativeTitle
        endReason = null
        finalizing.set(false); readerDone.set(false)
        samplesRead.set(0); queue.clear()
        wIndex = 0; wPcm = null; wOut = null; wSamples = 0

        if (freeBytes() < MIN_FREE_START) {
            abortStart("Storage tidak cukup untuk merekam (sisa ${formatSize(freeBytes())}).")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { abortStart("Perangkat tidak mendukung format rekam 16 kHz."); return }
        val bufBytes = max(minBuf * 4, SAMPLE_RATE * 2 * 2) // >= ~2 s of internal buffer

        val rec: AudioRecord = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
        } catch (e: Exception) { abortStart("Izin mikrofon belum diberikan."); return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); abortStart("Mikrofon tidak bisa dibuka (mungkin sedang dipakai app lain)."); return
        }
        recorder = rec

        // Entry in index.json exists from the first second (needed for recovery).
        activeId = recordingId
        RecordingRepository.setActive(recordingId)
        RecordingRepository.upsert(
            Recording(recordingId, title, System.currentTimeMillis(), recordingId, "RECORDING")
        )
        acquireWakeLock()
        _live.value = LiveRecording(recordingId, title)

        try {
            openChunk()
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("mic tidak mulai")
        } catch (e: Exception) {
            try { rec.release() } catch (_: Exception) {}
            recorder = null
            wOut?.close(); wPcm?.delete(); wOut = null; wPcm = null
            RecordingRepository.update(recordingId) { it.copy(status = "FAILED") }
            abortStart("Gagal memulai rekaman: ${e.message}")
            return
        }
        registerSilencedCallback(rec)

        readThread = thread(name = "daybook-read") { readLoop(rec) }
        writeThread = thread(name = "daybook-write") { writeLoop() }
        tickThread = thread(name = "daybook-tick") { tickLoop() }
    }

    private fun abortStart(message: String) {
        _startError.value = message
        activeId = null
        RecordingRepository.setActive(null)
        _live.value = null
        releaseWakeLock()
        running.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Read thread: mic -> queue (never waits for the disk) ────────────────

    private fun readLoop(rec: AudioRecord) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(FRAME * 10) // 200 ms
        var zeroRun = 0L
        try {
            while (running.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    samplesRead.addAndGet(n.toLong())
                    queue.put(buf.copyOf(n))
                    var peak = 0
                    var sumSq = 0.0
                    for (i in 0 until n) {
                        val v = abs(buf[i].toInt())
                        if (v > peak) peak = v
                        sumSq += v.toDouble() * v
                    }
                    val rms = sqrt(sumSq / n)
                    val level = sqrt((rms / 4000.0).coerceIn(0.0, 1.0)).toFloat()
                    _live.update { it?.copy(level = level) }
                    // Android can silently return all-zero data when the mic is taken away.
                    if (peak == 0) {
                        zeroRun += n
                        if (zeroRun >= 10L * SAMPLE_RATE) setWarning(MSG_MIC_SILENT)
                    } else if (zeroRun > 0) {
                        zeroRun = 0; clearWarning(MSG_MIC_SILENT)
                    }
                } else if (n == 0) {
                    Thread.sleep(10)
                } else if (running.get()) {
                    requestStop("Mikrofon berhenti (kode error $n).")
                    break
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            readerDone.set(true)
        }
    }

    // ── Write thread: queue -> chunk_XXXX.pcm, rotate on pauses ─────────────

    private fun writeLoop() {
        val bytes = ByteArray(FRAME * 2)
        var sinceSync = 0L
        var silentRun = 0L
        var floor = 1000.0                      // adaptive noise floor (RMS)
        var ceil = 0.0                          // slowly-decaying recent loud level (RMS)
        var lastStorageCheck = System.currentTimeMillis()
        try {
            while (true) {
                val block = queue.poll(1, TimeUnit.SECONDS)
                if (block == null) {
                    if (readerDone.get() && queue.isEmpty()) break else continue
                }
                var i = 0
                while (i < block.size) {
                    val len = minOf(FRAME, block.size - i)
                    var sumSq = 0.0
                    for (k in 0 until len) {
                        val v = block[i + k].toInt()
                        bytes[2 * k] = (v and 0xFF).toByte()
                        bytes[2 * k + 1] = ((v shr 8) and 0xFF).toByte()
                        sumSq += v.toDouble() * v
                    }
                    wOut!!.write(bytes, 0, len * 2)
                    wSamples += len; sinceSync += len; i += len

                    val rms = sqrt(sumSq / len)
                    floor = if (rms < floor) rms else floor + (rms - floor) * 0.0005
                    ceil = if (rms > ceil) rms else ceil - (ceil - rms) * 0.0005
                    // Never let the "pause" threshold climb above ~35% of the recent speech level:
                    // with steady speech and no gaps the floor creeps up and speech would be
                    // mistaken for silence (cut mid-speech at exactly 10:00).
                    val threshold = minOf(max(floor * 2.5, 100.0), ceil * 0.35)
                    silentRun = if (rms < threshold) silentRun + len else 0

                    if (sinceSync >= SYNC_EVERY_SAMPLES) {
                        wOut!!.flush(); wOut!!.fd.sync(); sinceSync = 0
                    }
                    if (wSamples >= CHUNK_MAX_SAMPLES ||
                        (wSamples >= CHUNK_MIN_SAMPLES && silentRun >= SILENCE_RUN_SAMPLES)) {
                        closeChunk(); openChunk(); silentRun = 0; sinceSync = 0
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastStorageCheck > 30_000) {
                    lastStorageCheck = now
                    val free = freeBytes()
                    if (free < MIN_FREE_STOP) { requestStop("Storage hampir penuh, rekaman dihentikan otomatis."); }
                    else if (free < WARN_FREE) setWarning(MSG_LOW_STORAGE) else clearWarning(MSG_LOW_STORAGE)
                }
            }
        } catch (e: Exception) {
            requestStop("Gagal menulis audio: ${e.message}")
        } finally {
            closeChunk()
        }
    }

    private fun openChunk() {
        wIndex++
        val f = File(RecordingRepository.dir(recordingId), "chunk_%04d.pcm".format(wIndex))
        wPcm = f
        wOut = FileOutputStream(f)
        wSamples = 0
    }

    /** Close the current chunk: fsync, wrap PCM into WAV, register it. The mic keeps running. */
    private fun closeChunk() {
        val out = wOut ?: return
        val pcm = wPcm ?: return
        val idx = wIndex
        val samples = wSamples
        wOut = null; wPcm = null
        try { out.flush(); out.fd.sync() } catch (_: Exception) {}
        try { out.close() } catch (_: Exception) {}
        if (samples <= 0) { pcm.delete(); return }
        val wav = File(pcm.parentFile, pcm.nameWithoutExtension + ".wav")
        try {
            WavIO.wrapPcmToWav(pcm, wav)
            pcm.delete()
            val durationMs = samples * 1000L / SAMPLE_RATE
            RecordingRepository.addChunk(recordingId, Chunk(idx, wav.name, durationMs, "RECORDED", ""))
        } catch (_: Exception) { /* .pcm is kept; recovery wraps it on next start */ }
    }

    // ── Stop / finalize ─────────────────────────────────────────────────────

    /** reason == null: user stop. Otherwise the recording is flagged INTERRUPTED. */
    private fun requestStop(reason: String?) {
        if (reason != null && endReason == null) endReason = reason
        if (!finalizing.compareAndSet(false, true)) return
        running.set(false)
        thread(name = "daybook-finalize") {
            try { recorder?.stop() } catch (_: Exception) {}
            readThread?.join(3000)
            writeThread?.join(20000)
            try { recorder?.let { r -> unregisterSilencedCallback(r); r.release() } } catch (_: Exception) {}
            recorder = null
            val interrupted = endReason != null
            RecordingRepository.update(recordingId) { r ->
                val minutes = r.chunks.sumOf { it.durationMs } / 60000
                r.copy(
                    status = when {
                        r.chunks.isEmpty() -> "FAILED"
                        interrupted -> "INTERRUPTED"
                        else -> "PROCESSING"   // audio saved; waiting for STT
                    },
                    message = if (interrupted) "Rekaman terputus di menit $minutes: $endReason" else r.message
                )
            }
            val finalizedRecording = RecordingRepository.get(recordingId)
            if (finalizedRecording != null) {
                try { PublicRecordingExporter.exportRecording(applicationContext, finalizedRecording) }
                catch (_: Exception) {
                    // Private source audio remains intact; a later recovery/export can retry.
                }
            }
            val hasAudio = finalizedRecording?.chunks?.isNotEmpty() == true
            if (hasAudio && AUTO_TRANSCRIBE) {
                try { SttWorker.enqueue(applicationContext, recordingId) } catch (_: Exception) {}
            }
            activeId = null
            RecordingRepository.setActive(null)
            _live.value = null
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // System destroyed us while recording: flush what we have; the rest is
        // recovered on the next app start.
        if (running.get() && !finalizing.get()) requestStop("Rekaman dihentikan oleh sistem.")
    }

    // ── Tick (UI clock + notification) ──────────────────────────────────────

    private fun tickLoop() {
        var n = 0
        while (running.get()) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            val ms = samplesRead.get() * 1000L / SAMPLE_RATE
            _live.update { it?.copy(elapsedMs = ms) }
            if (++n % 5 == 0) updateNotification()
        }
    }

    // ── Marks ───────────────────────────────────────────────────────────────

    private fun addMark(type: String) {
        val ms = samplesRead.get() * 1000L / SAMPLE_RATE
        RecordingRepository.addMark(recordingId, Mark(ms, type))
        _live.update { it?.copy(markCount = it.markCount + 1, lastMarkMs = ms, lastMarkType = type) }
        vibrate()
        updateNotification()
    }

    private fun vibrate() {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            else legacyVibrator()
            v?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun legacyVibrator(): Vibrator? = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun freeBytes(): Long = try {
        StatFs(filesDir.absolutePath).availableBytes
    } catch (_: Exception) { Long.MAX_VALUE }

    private fun setWarning(msg: String) { _live.update { cur -> cur?.copy(warning = msg) } }
    private fun clearWarning(msg: String) {
        _live.update { cur -> if (cur != null && cur.warning == msg) cur.copy(warning = null) else cur }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "daybook:recording").apply {
                setReferenceCounted(false)
                acquire(13L * 60 * 60 * 1000) // safety timeout slightly above a 12 h meeting
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
    }

    /** API 29+: detect when another app / a call takes the mic ("client silenced"). */
    private fun registerSilencedCallback(rec: AudioRecord) {
        if (Build.VERSION.SDK_INT < 29) return
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                val silenced = try { rec.activeRecordingConfiguration?.isClientSilenced == true } catch (_: Exception) { false }
                if (silenced) setWarning(MSG_MIC_BLOCKED) else clearWarning(MSG_MIC_BLOCKED)
            }
        }
        recCallback = cb
        try { rec.registerAudioRecordingCallback(ContextCompat.getMainExecutor(this), cb) } catch (_: Exception) {}
    }

    private fun unregisterSilencedCallback(rec: AudioRecord) {
        if (Build.VERSION.SDK_INT < 29) return
        try { recCallback?.let { rec.unregisterAudioRecordingCallback(it) } } catch (_: Exception) {}
        recCallback = null
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

    private fun servicePi(code: Int, action: String, markType: String?): PendingIntent {
        val i = Intent(this, RecordingService::class.java).setAction(action)
        if (markType != null) i.putExtra(EXTRA_MARK_TYPE, markType)
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildNotification(titleText: String = title): Notification {
        val live = _live.value
        val text = (live?.warning ?: "Merekam") + " • " + formatClock(live?.elapsedMs ?: 0L) +
            (if ((live?.markCount ?: 0) > 0) " • ⭐${live?.markCount}" else "")
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        // No Stop action on purpose: a mistaken stop in a meeting is expensive. Stop lives in the app.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Daybook — $titleText")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .addAction(0, "❓ Bingung", servicePi(11, ACTION_MARK, MARK_BINGUNG))
            .addAction(0, "⭐ Penting", servicePi(12, ACTION_MARK, MARK_PENTING))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFY_ID, buildNotification())
        } catch (_: SecurityException) { /* POST_NOTIFICATIONS not granted — recording still runs */ }
    }
}
