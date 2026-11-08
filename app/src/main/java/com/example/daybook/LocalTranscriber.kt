package com.example.daybook

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Offline speech-to-text using Whisper (multilingual "base", int8) via
 * sherpa-onnx. Runs fully on-device; no audio/text is sent to the cloud by
 * this class.
 *
 * Recordings are 16kHz mono 16-bit (see RecordingService). Whisper decodes at
 * most ~30 s per call, so each chunk (~10 min) is cut into <=25 s windows,
 * preferring to cut at the quietest moment near the end of a window so words
 * are not split. Whisper gives no reliable word timestamps, so each window
 * becomes one TimedSegment {startMs, endMs, text} (§7) — coarse, but enough to
 * jump to moments in the result screen.
 *
 * The model (~160 MB: encoder + decoder + tokens) is downloaded once on first
 * use from Hugging Face and cached in filesDir.
 */
object LocalTranscriber {
    private const val MODEL_DIR_NAME = "whisper-base-int8"
    private const val BASE_URL =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/"
    private const val ENCODER = "base-encoder.int8.onnx"
    private const val DECODER = "base-decoder.int8.onnx"
    private const val TOKENS = "base-tokens.txt"
    private const val DONE_MARKER = ".complete"
    private const val LANGUAGE = "id"

    private const val SR = 16000
    private const val WINDOW_SAMPLES = 25 * SR       // < Whisper's 30 s limit
    private const val MIN_WINDOW_SAMPLES = 10 * SR   // never cut before this
    private const val FRAME_SAMPLES = SR / 5         // 200 ms frames for pause search
    private const val SILENT_RMS = 0.002f            // skip silent windows (avoids Whisper hallucinations)

    /** Ensure the Whisper model is present on disk, downloading once if needed. */
    fun ensureModel(context: Context): File {
        val dir = File(context.filesDir, MODEL_DIR_NAME)
        if (File(dir, DONE_MARKER).exists()) return dir

        // Old Vosk model is no longer used; free the space.
        File(context.filesDir, "vosk-model-small-id").deleteRecursively()
        File(context.filesDir, "vosk-model-small-id-0.4").deleteRecursively()

        dir.mkdirs()
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS).build()

        // Files that finished earlier are kept, so a retry resumes at the next file.
        for (name in listOf(TOKENS, ENCODER, DECODER)) {
            val target = File(dir, name)
            if (target.exists() && target.length() > 0) continue
            download(client, BASE_URL + name, target)
        }
        File(dir, DONE_MARKER).writeText("ok")
        return dir
    }

    private fun download(client: OkHttpClient, url: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".part")
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Download model gagal: HTTP ${resp.code} ($url)")
            val body = resp.body ?: throw Exception("Response body kosong")
            val expected = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(tmp).use { input.copyTo(it) }
            }
            if (expected > 0 && tmp.length() != expected) {
                tmp.delete()
                throw Exception("Download tidak lengkap: ${target.name}")
            }
        }
        if (!tmp.renameTo(target)) throw Exception("Gagal menyimpan ${target.name}")
    }

    /** Create the recognizer once (SttWorker) and reuse it across chunks. Call release() when done. */
    fun createRecognizer(modelDir: File): OfflineRecognizer {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SR, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(modelDir, ENCODER).absolutePath,
                    decoder = File(modelDir, DECODER).absolutePath,
                    language = LANGUAGE,
                    task = "transcribe"
                ),
                tokens = File(modelDir, TOKENS).absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
        )
        return OfflineRecognizer(config = config)
    }

    /**
     * Transcribe a 16kHz mono 16-bit WAV file into timed segments. The recognizer is
     * created once by the caller (SttWorker) and reused across chunks. Throws on
     * failure; the caller marks the chunk FAILED and continues. (§7)
     */
    fun transcribeTimed(recognizer: OfflineRecognizer, wavFile: File): List<TimedSegment> {
        val pcm = readPcm(wavFile)
        val segments = mutableListOf<TimedSegment>()
        var start = 0
        while (start < pcm.size) {
            var end = minOf(start + WINDOW_SAMPLES, pcm.size)
            if (end < pcm.size) end = findQuietCut(pcm, start, end)

            val samples = FloatArray(end - start) { pcm[start + it] / 32768f }
            if (rms(samples) >= SILENT_RMS) {
                val text = decode(recognizer, samples).trim()
                if (text.isNotEmpty()) {
                    segments.add(TimedSegment(start * 1000L / SR, end * 1000L / SR, text))
                }
            }
            start = end
        }
        return segments
    }

    private fun decode(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SR)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    /** Cut point = centre of the quietest 200 ms frame in the tail of the window. */
    private fun findQuietCut(pcm: ShortArray, start: Int, end: Int): Int {
        val from = maxOf(start + MIN_WINDOW_SAMPLES, end - 5 * SR)
        var bestPos = end
        var bestEnergy = Long.MAX_VALUE
        var pos = from
        while (pos + FRAME_SAMPLES <= end) {
            var e = 0L
            for (i in pos until pos + FRAME_SAMPLES) e += abs(pcm[i].toInt())
            if (e < bestEnergy) {
                bestEnergy = e
                bestPos = pos + FRAME_SAMPLES / 2
            }
            pos += FRAME_SAMPLES
        }
        return if (bestPos > start) bestPos else end
    }

    private fun rms(x: FloatArray): Float {
        if (x.isEmpty()) return 0f
        var s = 0.0
        for (v in x) s += v * v
        return sqrt(s / x.size).toFloat()
    }

    /** Read the 16-bit little-endian PCM payload (skips the 44-byte WAV header). */
    private fun readPcm(wavFile: File): ShortArray {
        val bytes = FileInputStream(wavFile).use { fis ->
            fis.skip(WavIO.HEADER_SIZE.toLong())
            fis.readBytes()
        }
        val out = ShortArray(bytes.size / 2)
        for (i in out.indices) {
            out[i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
        }
        return out
    }

    /** Convenience: plain-text transcript (loads model internally). */
    fun transcribe(context: Context, wavFile: File): String {
        val recognizer = createRecognizer(ensureModel(context))
        return try {
            transcribeTimed(recognizer, wavFile).joinToString(" ") { it.text }
        } finally {
            recognizer.release()
        }
    }
}
