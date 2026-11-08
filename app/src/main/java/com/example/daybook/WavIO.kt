package com.example.daybook

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * WAV header read/write helpers shared by the recording service (chunk close)
 * and crash recovery. All Daybook recordings are 16kHz mono 16-bit PCM.
 *
 * Recordings are written as raw `.pcm` first (no half-written header that can
 * corrupt), then wrapped to `.wav` by prepending a 44-byte header sized from the
 * PCM file length (§6.2). Recovery wraps a leftover `.pcm` from file size (§6.4).
 */
object WavIO {
    const val SAMPLE_RATE = 16000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val HEADER_SIZE = 44

    /** Write a 44-byte WAV header. dataLen=0 means sizes are patched later. */
    fun writeWavHeader(
        out: OutputStream,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS,
        bits: Int = BITS_PER_SAMPLE,
        dataLen: Long = 0L
    ) {
        val byteRate = sampleRate * channels * bits / 8
        val blockAlign = channels * bits / 8
        out.write("RIFF".toByteArray(Charsets.US_ASCII)); out.write(intLE((36 + dataLen).toInt()))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII)); out.write(intLE(16))
        out.write(shortLE(1)); out.write(shortLE(channels))
        out.write(intLE(sampleRate)); out.write(intLE(byteRate))
        out.write(shortLE(blockAlign)); out.write(shortLE(bits))
        out.write("data".toByteArray(Charsets.US_ASCII)); out.write(intLE(dataLen.toInt()))
    }

    /** Patch the RIFF/data sizes of a WAV whose header was written with dataLen=0. */
    fun patchWavHeader(
        file: File,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS,
        bits: Int = BITS_PER_SAMPLE
    ) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val dataLen = file.length() - HEADER_SIZE
                raf.seek(4); raf.writeInt(Integer.reverseBytes((36 + dataLen).toInt()))
                raf.seek(40); raf.writeInt(Integer.reverseBytes(dataLen.toInt()))
            }
        } catch (_: Exception) { /* best effort */ }
    }

    /** Wrap a raw PCM file into a WAV by prepending a 44-byte header sized from the
     *  PCM file length. Used by chunk-close and crash recovery. Returns the WAV. */
    fun wrapPcmToWav(
        pcm: File,
        wav: File,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS,
        bits: Int = BITS_PER_SAMPLE
    ): File {
        // Write to a temp file and rename: a crash mid-wrap never leaves a truncated .wav.
        val tmp = File(wav.parentFile, wav.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            writeWavHeader(out, sampleRate, channels, bits, pcm.length())
            pcm.inputStream().use { it.copyTo(out) }
            out.flush(); out.fd.sync()
        }
        if (wav.exists()) wav.delete()
        if (!tmp.renameTo(wav)) throw java.io.IOException("rename ${tmp.name} gagal")
        return wav
    }

    /** Merge WAV chunks by copying PCM payloads only, never their individual headers. */
    fun mergeWavChunks(chunks: List<File>, destination: File): File {
        require(chunks.isNotEmpty()) { "Tidak ada chunk audio untuk diunggah." }
        val totalPcmBytes = chunks.sumOf { file ->
            require(file.isFile && file.length() >= HEADER_SIZE) { "File audio tidak valid: ${file.name}" }
            file.length() - HEADER_SIZE
        }
        require(totalPcmBytes <= Int.MAX_VALUE.toLong() - 36L) { "Rekaman terlalu besar untuk format WAV standar." }
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, destination.name + ".tmp")
        try {
            FileOutputStream(temp).use { out ->
                writeWavHeader(out, dataLen = totalPcmBytes)
                val buffer = ByteArray(64 * 1024)
                chunks.forEach { chunk ->
                    chunk.inputStream().use { input ->
                        var skipped = 0L
                        while (skipped < HEADER_SIZE) {
                            val n = input.skip(HEADER_SIZE - skipped)
                            if (n <= 0) throw java.io.IOException("Header WAV tidak lengkap: ${chunk.name}")
                            skipped += n
                        }
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                }
                out.flush(); out.fd.sync()
            }
            if (destination.exists()) destination.delete()
            if (!temp.renameTo(destination)) throw java.io.IOException("Gagal menyiapkan audio gabungan.")
            return destination
        } catch (e: Exception) { temp.delete(); throw e }
    }

    /** Duration in ms of a 16 kHz mono 16-bit WAV (44-byte header), from file size. */
    fun durationMs(wav: File): Long =
        maxOf(0L, wav.length() - HEADER_SIZE) * 1000L / (2L * SAMPLE_RATE)

    private fun intLE(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
    private fun shortLE(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
}
