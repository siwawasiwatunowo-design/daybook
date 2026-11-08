package com.example.daybook

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Offline speech-to-text using Vosk (local, on-device). No audio/teks is sent
 * to the cloud by this class.
 *
 * Recordings are 16kHz mono 16-bit (see RecordingService), so PCM is fed
 * straight to the recognizer with no decoding/resampling step.
 *
 * Output is TIMED (§7): a list of segments {startMs, endMs, text} via
 * Recognizer.setWords(true), so the result screen can show a timeline and
 * jump to moments. The Indonesian model (vosk-model-small-id) is downloaded
 * once on first use (~40MB) and cached in filesDir. Accuracy is POC-grade.
 */
object LocalTranscriber {
    private const val MODEL_DIR_NAME = "vosk-model-small-id"
    private const val MODEL_ZIP_FOLDER = "vosk-model-small-id-0.4"
    private const val MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-id-0.4.zip"

    /** Ensure the Vosk model is present on disk, downloading once if needed. */
    fun ensureModel(context: Context): File {
        val dir = File(context.filesDir, MODEL_DIR_NAME)
        if (dir.exists() && File(dir, "conf").exists()) return dir
        dir.parentFile?.mkdirs()

        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS).build()
        val req = Request.Builder().url(MODEL_URL).build()
        val tmpZip = File(context.cacheDir, "vosk-model.zip")
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Download model gagal: HTTP ${resp.code}")
            resp.body?.byteStream()?.use { input ->
                FileOutputStream(tmpZip).use { input.copyTo(it) }
            } ?: throw Exception("Response body kosong")
        }
        unzip(tmpZip, context.filesDir)
        tmpZip.delete()

        val extracted = File(context.filesDir, MODEL_ZIP_FOLDER)
        if (extracted.exists() && !dir.exists()) extracted.renameTo(dir)
        if (!dir.exists()) throw Exception("Model gagal diekstrak")
        return dir
    }

    private fun unzip(zipFile: File, destDir: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) outFile.mkdirs()
                else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Transcribe a 16kHz mono 16-bit WAV file into timed segments. The model is
     * loaded once by the caller (SttWorker) and reused across chunks. Throws on
     * failure; the caller marks the chunk FAILED and continues. (§7)
     */
    fun transcribeTimed(model: Model, wavFile: File): List<TimedSegment> {
        val recognizer = Recognizer(model, 16000.0f)
        recognizer.setWords(true)
        try {
            FileInputStream(wavFile).use { fis ->
                fis.skip(WavIO.HEADER_SIZE.toLong()) // skip the 44-byte WAV header
                val buffer = ByteArray(8192)
                val segments = mutableListOf<TimedSegment>()
                while (true) {
                    val n = fis.read(buffer)
                    if (n < 0) break
                    if (recognizer.acceptWaveForm(buffer, n)) {
                        addUtterance(JSONObject(recognizer.result), segments)
                    }
                }
                addUtterance(JSONObject(recognizer.finalResult), segments)
                return segments
            }
        } finally {
            recognizer.close()
        }
    }

    private fun addUtterance(res: JSONObject, out: MutableList<TimedSegment>) {
        val text = res.optString("text", "").trim()
        if (text.isEmpty()) return
        val arr = res.optJSONArray("result") ?: return
        if (arr.length() == 0) return
        val start = arr.optJSONObject(0)?.optDouble("start", 0.0) ?: 0.0
        val end = arr.optJSONObject(arr.length() - 1)?.optDouble("end", 0.0) ?: 0.0
        out.add(TimedSegment((start * 1000).toLong(), (end * 1000).toLong(), text))
    }

    /** Convenience: plain-text transcript (loads model internally). */
    fun transcribe(context: Context, wavFile: File): String {
        val modelDir = ensureModel(context)
        val model = Model(modelDir.absolutePath)
        return try {
            transcribeTimed(model, wavFile).joinToString(" ") { it.text }
        } finally {
            model.close()
        }
    }
}
