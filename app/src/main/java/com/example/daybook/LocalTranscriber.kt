package com.example.daybook

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Offline speech-to-text using Vosk (local, on-device). No audio/teks is sent
 * to the cloud by this class.
 *
 * Recordings are 16kHz mono 16-bit WAV (see VM.startRecording), so we feed the
 * PCM data straight to the recognizer with no decoding/resampling step.
 *
 * The Indonesian model (vosk-model-small-id) is downloaded once on first use
 * (~40MB) and cached in filesDir. Accuracy is POC-grade; the goal is reliable
 * offline transcription, not perfection.
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
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Transcribe a 16kHz mono 16-bit WAV file to text. Runs on the calling
     * thread (caller must use Dispatchers.IO). Throws on failure.
     */
    fun transcribe(context: Context, wavFile: File): String {
        val modelDir = ensureModel(context)
        val model = Model(modelDir.absolutePath)
        val recognizer = Recognizer(model, 16000.0f)
        try {
            FileInputStream(wavFile).use { fis ->
                // Skip the 44-byte WAV header we wrote in VM.startRecording.
                val header = ByteArray(44)
                fis.read(header)
                val buffer = ByteArray(8192)
                val sb = StringBuilder()
                while (true) {
                    val n = fis.read(buffer)
                    if (n < 0) break
                    if (recognizer.acceptWaveForm(buffer, n)) {
                        val seg = JSONObject(recognizer.result).optString("text", "")
                        if (seg.isNotBlank()) sb.append(seg).append(' ')
                    }
                }
                val finalText = JSONObject(recognizer.finalResult).optString("text", "")
                if (finalText.isNotBlank()) sb.append(finalText).append(' ')
                return sb.toString().trim()
            }
        } finally {
            recognizer.close()
            model.close()
        }
    }
}
