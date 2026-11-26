package com.pla.perekam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pla.perekam.MainActivity
import com.pla.perekam.R
import com.pla.perekam.util.AudioEngine
import com.pla.perekam.util.PermissionHelper
import com.pla.perekam.util.Prefs
import com.pla.perekam.util.RecordingStore
import java.io.File

class RecorderService : Service() {
    companion object {
        const val CHANNEL = "pla_recording"
        const val CONTROL_CHANNEL = "pla_control"
        const val ID = 101
        const val CONTROL_ID = 102

        const val ACTION_START_VOICE = "com.pla.perekam.action.START_VOICE"
        const val ACTION_START_SCREEN = "com.pla.perekam.action.START_SCREEN"
        const val ACTION_START_CALL = "com.pla.perekam.action.START_CALL"
        const val ACTION_STOP = "com.pla.perekam.action.STOP"
        const val ACTION_PAUSE = "com.pla.perekam.action.PAUSE"
        const val ACTION_REFRESH = "com.pla.perekam.action.REFRESH"
        const val ACTION_STATE = "com.pla.perekam.action.STATE"

        const val EXTRA_TYPE = "type"
        const val EXTRA_LABEL = "label"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_AUTO_STOP = "autoStop"

        @Volatile var activeType: String = ""
            private set
        @Volatile var paused: Boolean = false
            private set
        @Volatile var running: Boolean = false
            private set
        @Volatile var lastMessage: String = ""

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Rekaman berlangsung", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(CONTROL_CHANNEL, "Kontrol rekaman", NotificationManager.IMPORTANCE_LOW)
            )
        }

        fun send(context: Context, action: String, configure: Intent.() -> Unit = {}) {
            val intent = Intent(context, RecorderService::class.java)
                .setAction(action)
                .apply(configure)
            try {
                if (action == ACTION_START_VOICE ||
                    action == ACTION_START_SCREEN ||
                    action == ACTION_START_CALL ||
                    (running && action == ACTION_REFRESH)
                ) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                lastMessage = "Android menolak mulai dari latar belakang. Buka aplikasi sekali, lalu coba lagi."
                context.sendBroadcast(Intent(ACTION_STATE).setPackage(context.packageName))
            }
        }

        fun togglePause(context: Context) {
            if (running) send(context, ACTION_PAUSE)
        }

        fun stop(context: Context) {
            if (running) send(context, ACTION_STOP)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var audioEngine: AudioEngine? = null
    private var mediaRecorder: MediaRecorder? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var currentFile: File? = null
    private var startedAt = 0L
    private var pausedTotal = 0L
    private var pauseStarted = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var previousSpeaker = false
    private var speakerChanged = false
    private var previousMode = AudioManager.MODE_NORMAL
    private var modeChanged = false
    private var autoStopRequested = false
    private var normalModeSeconds = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            handler.post { finishRecording("Tangkapan layar dihentikan sistem.") }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            updateNotification()

            if (activeType == RecordingStore.CALL && autoStopRequested) {
                val am = getSystemService(AudioManager::class.java)
                if (am.mode == AudioManager.MODE_NORMAL) {
                    normalModeSeconds++
                    if (normalModeSeconds >= 8) {
                        finishRecording("Panggilan selesai. Rekaman disimpan.")
                        return
                    }
                } else {
                    normalModeSeconds = 0
                }
            }

            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_VOICE -> startVoice()
            ACTION_START_SCREEN -> startScreen(intent)
            ACTION_START_CALL -> startCall(
                intent.getStringExtra(EXTRA_LABEL),
                intent.getBooleanExtra(EXTRA_AUTO_STOP, false)
            )
            ACTION_STOP -> finishRecording("Rekaman dihentikan dan disimpan.")
            ACTION_PAUSE -> togglePause()
            ACTION_REFRESH -> {
                ensureControlNotification()
                broadcastState()
            }
        }
        return START_NOT_STICKY
    }

    private fun beginForeground(type: Int, title: String): Boolean {
        return try {
            ServiceCompat.startForeground(this, ID, notification(title), type)
            true
        } catch (e: Exception) {
            lastMessage = "Tidak bisa memulai layanan: ${e.message ?: "izin atau pembatasan Android"}"
            broadcastState()
            stopSelf()
            false
        }
    }

    private fun startVoice() {
        if (running) {
            lastMessage = "Hentikan rekaman yang sedang berjalan terlebih dahulu."
            broadcastState()
            return
        }
        if (!PermissionHelper.hasMic(this)) {
            fail("Izin mikrofon belum diberikan.")
            return
        }

        val type = if (Build.VERSION.SDK_INT >= 29)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0

        if (!beginForeground(type, "Merekam suara")) return

        val file = RecordingStore.newFile(this, RecordingStore.VOICE)
        val engine = AudioEngine(this)

        try {
            engine.start(file, MediaRecorder.AudioSource.MIC, 0, false, 0)
            audioEngine = engine
            currentFile = file
            activeType = RecordingStore.VOICE
            markStarted()
        } catch (e: Exception) {
            file.delete()
            fail(e.message ?: "Perekam suara gagal dimulai.")
        }
    }

    private fun safe(text: String): String =
        text.replace(Regex("[^\\p{L}\\p{N}_+.-]"), "_").take(45)

    private fun startCall(label: String?, autoStop: Boolean) {
        if (running) {
            if (activeType == RecordingStore.CALL && !label.isNullOrBlank()) {
                currentFile?.let { old ->
                    val renamed = File(old.parentFile, old.name.replaceFirst("Panggilan_", "Panggilan_${safe(label)}_"))
                    if (!renamed.exists() && old.renameTo(renamed)) currentFile = renamed
                }
            }
            return
        }

        if (!PermissionHelper.hasMic(this)) {
            fail("Izin mikrofon belum diberikan.")
            return
        }

        val type = if (Build.VERSION.SDK_INT >= 29)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0

        if (!beginForeground(type, "Merekam panggilan")) return

        val prefs = Prefs(this)
        configureAudio(prefs)

        val file = RecordingStore.newFile(this, RecordingStore.CALL, label)
        val engine = AudioEngine(this)

        try {
            val gain = when (prefs.gain) {
                1 -> 3
                2 -> 6
                3 -> 9
                else -> 0
            }
            val bass = when (prefs.bass) {
                1 -> 250
                2 -> 500
                3 -> 800
                else -> 0
            }

            engine.start(file, prefs.source, gain, prefs.noise == 1, bass)
            audioEngine = engine
            currentFile = file
            activeType = RecordingStore.CALL
            autoStopRequested = autoStop
            markStarted()
        } catch (e: Exception) {
            file.delete()
            restoreAudio()
            fail(e.message ?: "Perekam panggilan gagal dimulai.")
        }
    }

    private fun startScreen(intent: Intent) {
        if (running) {
            fail("Hentikan rekaman yang sedang berjalan terlebih dahulu.")
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (data == null) {
            fail("Izin tangkapan layar tidak diterima.")
            return
        }

        val withMic = Prefs(this).screenMic
        if (withMic && !PermissionHelper.hasMic(this)) {
            fail("Izin mikrofon belum diberikan.")
            return
        }

        var fgsType = if (Build.VERSION.SDK_INT >= 29)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0

        if (withMic && Build.VERSION.SDK_INT >= 29) {
            fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }

        if (!beginForeground(fgsType, "Merekam layar")) return

        val file = RecordingStore.newFile(this, RecordingStore.SCREEN)
        var r: MediaRecorder? = null

        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val mp = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("Token tangkapan layar tidak valid.")

            projection = mp
            mp.registerCallback(projectionCallback, handler)

            val wm = getSystemService(WindowManager::class.java)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)

            val targetHeight = Prefs(this).screenHeight
            val scale = minOf(1f, targetHeight.toFloat() / metrics.heightPixels)
            var width = (metrics.widthPixels * scale).toInt().coerceAtLeast(2)
            var height = (metrics.heightPixels * scale).toInt().coerceAtLeast(2)
            width -= width % 2
            height -= height % 2

            r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            if (withMic) r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (withMic) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(44100)
                r.setAudioEncodingBitRate(128000)
                r.setAudioChannels(1)
            }
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(width, height)
            r.setVideoFrameRate(30)
            r.setVideoEncodingBitRate(if (height >= 1080) 8_000_000 else 4_000_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()

            virtualDisplay = mp.createVirtualDisplay(
                "PLARekamLayar",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface,
                null,
                handler
            )

            r.start()
            mediaRecorder = r
            currentFile = file
            activeType = RecordingStore.SCREEN
            markStarted()
        } catch (e: Exception) {
            try { r?.reset() } catch (_: Exception) {}
            try { r?.release() } catch (_: Exception) {}
            file.delete()
            releaseProjection()
            fail(e.message ?: "Perekam layar gagal dimulai.")
        }
    }

    private fun markStarted() {
        running = true
        paused = false
        startedAt = System.currentTimeMillis()
        pausedTotal = 0
        normalModeSeconds = 0
        lastMessage = "Rekaman dimulai."

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pla:perekam")
            .apply { acquire(6 * 60 * 60 * 1000L) }

        feedback(true)
        updateNotification()
        broadcastState()
        handler.postDelayed(ticker, 1000)
    }

    private fun togglePause() {
        if (!running) return
        try {
            if (!paused) {
                audioEngine?.pause()
                if (Build.VERSION.SDK_INT >= 24) mediaRecorder?.pause()
                pauseStarted = System.currentTimeMillis()
                paused = true
                lastMessage = "Rekaman dijeda."
            } else {
                audioEngine?.resume()
                if (Build.VERSION.SDK_INT >= 24) mediaRecorder?.resume()
                pausedTotal += System.currentTimeMillis() - pauseStarted
                paused = false
                lastMessage = "Rekaman dilanjutkan."
            }
            updateNotification()
            broadcastState()
        } catch (e: Exception) {
            lastMessage = "Jeda tidak didukung pada perangkat ini."
            broadcastState()
        }
    }

    private fun finishRecording(message: String) {
        if (!running) {
            stopSelf()
            return
        }

        handler.removeCallbacks(ticker)
        running = false
        paused = false

        val file = currentFile
        val type = activeType
        var valid = true

        try {
            audioEngine?.stop()
        } catch (_: Exception) {
            valid = false
        }

        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
            valid = false
        }

        try { mediaRecorder?.reset() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        audioEngine = null

        releaseProjection()
        restoreAudio()

        if (file != null && valid && file.exists() && file.length() > 100) {
            val exported = RecordingStore.export(this, file, type)
            lastMessage = if (exported) {
                "$message Tersimpan di Recordings."
            } else {
                "$message Tersimpan di penyimpanan aplikasi; salinan publik gagal."
            }
        } else {
            file?.delete()
            lastMessage = "Rekaman terlalu pendek atau gagal, berkas kosong dihapus."
        }

        currentFile = null
        activeType = ""
        autoStopRequested = false

        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null

        feedback(false)
        RecordingStore.cleanup(this)

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(CONTROL_ID)
        broadcastState()
        stopSelf()
    }

    private fun fail(message: String) {
        running = false
        activeType = ""
        lastMessage = message
        broadcastState()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun elapsed(): Long {
        val extra = if (paused) System.currentTimeMillis() - pauseStarted else 0
        return maxOf(0, System.currentTimeMillis() - startedAt - pausedTotal - extra)
    }

    private fun timeText(): String {
        val sec = elapsed() / 1000
        return "%02d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60)
    }

    private fun pending(action: String, code: Int): PendingIntent {
        val intent = Intent(this, RecorderService::class.java).setAction(action)
        return PendingIntent.getService(
            this, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notification(title: String): Notification {
        val open = PendingIntent.getActivity(
            this, 20,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val state = if (paused) "Dijeda" else "Sedang merekam"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("$title • $state")
            .setContentText(timeText())
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(false)
            .addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "Lanjutkan" else "Jeda",
                pending(ACTION_PAUSE, 21)
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Berhenti dan simpan", pending(ACTION_STOP, 22))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        if (!running) return
        val title = when (activeType) {
            RecordingStore.SCREEN -> "Merekam layar"
            RecordingStore.CALL -> "Merekam panggilan"
            else -> "Merekam suara"
        }
        getSystemService(NotificationManager::class.java).notify(ID, notification(title))
    }

    private fun ensureControlNotification() {
        if (running || !Prefs(this).permanentNotification) return

        val open = PendingIntent.getActivity(
            this, 30,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val screenPending = PendingIntent.getActivity(
            this, 32,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_SCREEN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(this, CONTROL_CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("pla perekam")
            .setContentText("Pintasan mulai rekaman")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_btn_speak_now, "Rekam suara", pending(ACTION_START_VOICE, 31))
            .addAction(android.R.drawable.ic_menu_camera, "Rekam layar", screenPending)
            .addAction(android.R.drawable.ic_menu_call, "Rekam panggilan", pending(ACTION_START_CALL, 33))
            .build()

        getSystemService(NotificationManager::class.java).notify(CONTROL_ID, n)
    }

    private fun configureAudio(prefs: Prefs) {
        val am = getSystemService(AudioManager::class.java)

        if (prefs.autoSpeaker) {
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    previousSpeaker = am.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    val speaker = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null) {
                        speakerChanged = am.setCommunicationDevice(speaker)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    previousSpeaker = am.isSpeakerphoneOn
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = true
                    speakerChanged = true
                }
            } catch (_: Exception) {}
        }

        if (prefs.forceCommunication && am.mode == AudioManager.MODE_IN_CALL) {
            try {
                previousMode = am.mode
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                modeChanged = true
            } catch (_: Exception) {}
        }
    }

    private fun restoreAudio() {
        val am = getSystemService(AudioManager::class.java)
        try {
            if (speakerChanged) {
                if (Build.VERSION.SDK_INT >= 31) {
                    am.clearCommunicationDevice()
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = previousSpeaker
                }
            }
            if (modeChanged) am.mode = previousMode
        } catch (_: Exception) {}
        speakerChanged = false
        modeChanged = false
    }

    private fun releaseProjection() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }

    private fun feedback(start: Boolean) {
        val prefs = Prefs(this)
        if (prefs.vibrate) {
            try {
                val vibrator = getSystemService(Vibrator::class.java)
                vibrator.vibrate(
                    VibrationEffect.createOneShot(if (start) 80 else 160, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } catch (_: Exception) {}
        }

        if (prefs.announce) {
            val text = if (start) "Rekaman dimulai" else "Rekaman selesai"
            getSystemService(NotificationManager::class.java).notify(
                200,
                NotificationCompat.Builder(this, CONTROL_CHANNEL)
                    .setSmallIcon(R.drawable.ic_mic)
                    .setContentTitle(text)
                    .setAutoCancel(true)
                    .setTimeoutAfter(2500)
                    .build()
            )
        }
    }

    private fun broadcastState() {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
        ensureControlNotification()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (running) {
            try { audioEngine?.stop() } catch (_: Exception) {}
            try { mediaRecorder?.stop() } catch (_: Exception) {}
            try { mediaRecorder?.release() } catch (_: Exception) {}
            releaseProjection()
            restoreAudio()
            try { wakeLock?.release() } catch (_: Exception) {}
            running = false
            activeType = ""
        }
        super.onDestroy()
    }
}