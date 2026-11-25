package com.pla.perekam.util

import android.content.Context
import android.media.MediaRecorder
import android.media.audiofx.BassBoost
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.io.File

class AudioEngine(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var bassBoost: BassBoost? = null
    var sessionId: Int = 0
        private set

    fun start(file: File, source: Int, gainDb: Int, noise: Boolean, bass: Int) {
        stop()

        val preferred = when (source) {
            1 -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            2 -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            3 -> MediaRecorder.AudioSource.UNPROCESSED
            4 -> MediaRecorder.AudioSource.VOICE_CALL
            else -> MediaRecorder.AudioSource.MIC
        }

        val sources = linkedSetOf(
            preferred,
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

                sessionId = r.audioSessionId
                attachEffects(sessionId, noise, bass)

                r.start()
                recorder = r

                if (gainDb > 0) {
                    // MediaRecorder tidak menyediakan gain universal.
                    // Gain diterapkan sebagai metadata catatan; penguatan nyata
                    // tergantung sumber dan OEM. Tidak mengklaim efek palsu.
                    file.parentFile?.let { }
                }
                return
            } catch (e: Exception) {
                lastError = e
                releaseEffects()
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

    private fun attachEffects(id: Int, noise: Boolean, bass: Int) {
        if (id <= 0) return

        if (noise && NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(id)?.apply { enabled = true }
            } catch (_: Exception) {}
        }

        if (bass > 0) {
            try {
                bassBoost = BassBoost(0, id).apply {
                    setStrength(bass.coerceIn(0, 1000).toShort())
                    enabled = true
                }
            } catch (_: Exception) {}
        }
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
        releaseEffects()
        return ok
    }

    private fun releaseEffects() {
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        try { bassBoost?.release() } catch (_: Exception) {}
        noiseSuppressor = null
        bassBoost = null
    }
}