package com.example.daybook

import android.app.Application

/**
 * Process entry point. onCreate runs once per process lifetime, which is exactly
 * when a stale RECORDING entry (from a crashed/killed process) needs recovery.
 *
 * Recovery wraps leftover .pcm chunks, rebuilds the chunk list from disk and marks
 * the recording INTERRUPTED (§6.4). It runs on a background thread so app start is
 * never blocked by copying audio; salvaged audio is then queued for transcription.
 */
class DaybookApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RecordingRepository.init(applicationContext)
        Thread {
            try {
                RecordingRepository.recoverInterrupted().forEach { id ->
                    val recovered = RecordingRepository.get(id)
                    if (recovered?.backendOptIn == true)
                        BackendUploadWorker.enqueue(applicationContext, id)
                    else if (RecordingService.AUTO_TRANSCRIBE)
                        SttWorker.enqueue(applicationContext, id)
                }
            } catch (_: Exception) { /* best effort */ }
        }.start()
    }
}
