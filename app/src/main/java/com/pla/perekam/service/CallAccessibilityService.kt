package com.pla.perekam.service

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.pla.perekam.util.PermissionHelper
import com.pla.perekam.util.Prefs
import com.pla.perekam.util.RecordingStore

class CallAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var activePackage: String? = null
    private var lastName: String? = null
    private var missedChecks = 0

    private val watchdog = object : Runnable {
        override fun run() {
            if (activePackage != null) {
                val mode = getSystemService(AudioManager::class.java).mode
                if (mode == AudioManager.MODE_NORMAL) {
                    missedChecks++
                } else {
                    missedChecks = 0
                }

                if (missedChecks >= 5) {
                    stopCall()
                } else {
                    handler.postDelayed(this, 1500)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        RecorderService.createChannels(this)
        RecorderService.send(this, RecorderService.ACTION_REFRESH)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        val prefs = Prefs(this)
        val allowed = prefs.autoMaster &&
            ((pkg == "com.whatsapp" && prefs.autoWhatsapp) ||
                (pkg == "com.whatsapp.w4b" && prefs.autoBusiness))

        if (!allowed || !PermissionHelper.hasMic(this)) return

        val root = rootInActiveWindow ?: return
        val texts = mutableListOf<String>()
        collect(root, texts, 0)

        val lower = texts.map { it.lowercase() }
        val ended = lower.any {
            it == "call ended" || it == "panggilan berakhir" ||
                it == "call declined" || it == "panggilan ditolak"
        }

        if (ended) {
            stopCall()
            return
        }

        val mode = getSystemService(AudioManager::class.java).mode
        val inCommunication = mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_IN_CALL

        val callControl = lower.any {
            it.contains("end call") || it.contains("akhiri panggilan") ||
                it.contains("hang up") || it.contains("tutup telepon") ||
                it.contains("leave call") || it.contains("keluar dari panggilan")
        }

        val calling = lower.any {
            it.contains("calling") || it.contains("memanggil") ||
                it.contains("ringing") || it.contains("berdering") ||
                it.contains("connecting") || it.contains("menghubungkan")
        }

        val chatScreen = lower.any {
            it.contains("type a message") || it.contains("ketik pesan") ||
                it == "chats" || it == "chat"
        }

        if (inCommunication && (callControl || calling) && !chatScreen) {
            missedChecks = 0
            val name = if (prefs.contactName) extractName(texts) else null

            if (activePackage == null) {
                activePackage = pkg
                lastName = name
                RecorderService.send(this, RecorderService.ACTION_START_CALL) {
                    putExtra(RecorderService.EXTRA_LABEL, name)
                    putExtra(RecorderService.EXTRA_AUTO_STOP, true)
                }
                handler.removeCallbacks(watchdog)
                handler.postDelayed(watchdog, 2000)
            } else if (!name.isNullOrBlank() && name != lastName) {
                lastName = name
                RecorderService.send(this, RecorderService.ACTION_START_CALL) {
                    putExtra(RecorderService.EXTRA_LABEL, name)
                    putExtra(RecorderService.EXTRA_AUTO_STOP, true)
                }
            }
        }
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 30 || out.size > 250) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out, depth + 1)
        }
    }

    private fun extractName(texts: List<String>): String? {
        val blocked = listOf(
            "whatsapp", "calling", "ringing", "connecting", "end call",
            "speaker", "mute", "video", "bluetooth", "message", "chat",
            "memanggil", "berdering", "menghubungkan", "akhiri", "pengeras",
            "bisukan", "pesan", "terenkripsi", "encrypted", "return to call"
        )

        return texts.firstOrNull { raw ->
            val t = raw.trim()
            val l = t.lowercase()
            t.length in 2..50 &&
                !t.matches(Regex(".*\\d{1,2}:\\d{2}.*")) &&
                blocked.none { l.contains(it) } &&
                (t.any { it.isLetter() } || t.startsWith("+"))
        }
    }

    private fun stopCall() {
        activePackage = null
        lastName = null
        missedChecks = 0
        handler.removeCallbacks(watchdog)
        if (RecorderService.activeType == RecordingStore.CALL) {
            RecorderService.stop(this)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}