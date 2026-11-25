package com.pla.perekam.util

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("pla_perekam", Context.MODE_PRIVATE)

    var autoMaster: Boolean
        get() = sp.getBoolean("autoMaster", false)
        set(value) = sp.edit().putBoolean("autoMaster", value).apply()

    var permanentNotification: Boolean
        get() = sp.getBoolean("permanentNotification", true)
        set(value) = sp.edit().putBoolean("permanentNotification", value).apply()

    var autoCellular: Boolean
        get() = sp.getBoolean("autoCellular", true)
        set(value) = sp.edit().putBoolean("autoCellular", value).apply()

    var autoWhatsapp: Boolean
        get() = sp.getBoolean("autoWhatsapp", false)
        set(value) = sp.edit().putBoolean("autoWhatsapp", value).apply()

    var autoBusiness: Boolean
        get() = sp.getBoolean("autoBusiness", false)
        set(value) = sp.edit().putBoolean("autoBusiness", value).apply()

    var autoSpeaker: Boolean
        get() = sp.getBoolean("autoSpeaker", false)
        set(value) = sp.edit().putBoolean("autoSpeaker", value).apply()

    var contactName: Boolean
        get() = sp.getBoolean("contactName", true)
        set(value) = sp.edit().putBoolean("contactName", value).apply()

    var forceCommunication: Boolean
        get() = sp.getBoolean("forceCommunication", true)
        set(value) = sp.edit().putBoolean("forceCommunication", value).apply()

    var announce: Boolean
        get() = sp.getBoolean("announce", true)
        set(value) = sp.edit().putBoolean("announce", value).apply()

    var vibrate: Boolean
        get() = sp.getBoolean("vibrate", true)
        set(value) = sp.edit().putBoolean("vibrate", value).apply()

    var source: Int
        get() = sp.getInt("source", 0)
        set(value) = sp.edit().putInt("source", value).apply()

    var gain: Int
        get() = sp.getInt("gain", 0)
        set(value) = sp.edit().putInt("gain", value).apply()

    var noise: Int
        get() = sp.getInt("noise", 0)
        set(value) = sp.edit().putInt("noise", value).apply()

    var bass: Int
        get() = sp.getInt("bass", 0)
        set(value) = sp.edit().putInt("bass", value).apply()

    var deleteDays: Int
        get() = sp.getInt("deleteDays", 0)
        set(value) = sp.edit().putInt("deleteDays", value).apply()

    var screenMic: Boolean
        get() = sp.getBoolean("screenMic", true)
        set(value) = sp.edit().putBoolean("screenMic", value).apply()

    var screenHeight: Int
        get() = sp.getInt("screenHeight", 720)
        set(value) = sp.edit().putInt("screenHeight", value).apply()
}