package com.pla.perekam.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioEngine(private val context: Context) {
    private var recorder: MediaRecorder? = null

    fun start(file: File, source: Int) {
        stop()

        val sources = linkedSetOf(
            source,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )

        var lastError: Exception? = null

        for (candidate in sources) {
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            try {
                r.setAudioSource(candidate)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioChannels(1)
                r.setAudioSamplingRate(44100)
                r.setAudioEncodingBitRate(128000)
                r.setOutputFile(file.absolutePath)
                r.prepare()
                r.start()
                recorder = r
                return
            } catch (e: Exception) {
                lastError = e
                try { r.reset() } catch (_: Exception) {}
                try { r.release() } catch (_: Exception) {}
                file.delete()
            }
        }

        throw IllegalStateException(
            "Tidak bisa membuka sumber suara. Coba sumber lain atau izinkan mikrofon.",
            lastError
        )
    }

    fun pause() {
        try { recorder?.pause() } catch (_: Exception) {}
    }

    fun resume() {
        try { recorder?.resume() } catch (_: Exception) {}
    }

    fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        var ok = true
        try {
            r.stop()
        } catch (_: Exception) {
            ok = false
        }
        try { r.reset() } catch (_: Exception) {}
        try { r.release() } catch (_: Exception) {}
        return ok
    }
}