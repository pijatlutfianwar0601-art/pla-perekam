package com.pla.perekam.util

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import com.pla.perekam.R
import com.pla.perekam.service.PauseTileService
import com.pla.perekam.service.ScreenTileService
import com.pla.perekam.service.VoiceTileService

object UiHelper {
    fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    fun spinner(context: Context, spinner: Spinner, items: List<String>, selected: Int) {
        spinner.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            items
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.setSelection(selected.coerceIn(0, items.lastIndex))
    }

    fun addTile(activity: Activity, kind: String) {
        if (Build.VERSION.SDK_INT < 33) {
            toast(
                activity,
                "Buka panel Pengaturan Cepat, tekan edit/ubah ubin, lalu seret ubin pla perekam."
            )
            return
        }

        val serviceClass: Class<*> = when (kind) {
            "screen" -> ScreenTileService::class.java
            "pause" -> PauseTileService::class.java
            else -> VoiceTileService::class.java
        }

        val label = when (kind) {
            "screen" -> activity.getString(R.string.tile_screen)
            "pause" -> activity.getString(R.string.tile_pause)
            else -> activity.getString(R.string.tile_voice)
        }

        val iconRes = when (kind) {
            "screen" -> R.drawable.ic_screen
            "pause" -> R.drawable.ic_pause
            else -> R.drawable.ic_mic
        }

        try {
            val manager = activity.getSystemService(StatusBarManager::class.java)
            manager.requestAddTileService(
                ComponentName(activity, serviceClass),
                label,
                Icon.createWithResource(activity, iconRes),
                activity.mainExecutor
            ) { result ->
                val message = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                        "Ubin berhasil ditambahkan."
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                        "Ubin sudah ada di Pengaturan Cepat."
                    else ->
                        "Ubin belum ditambahkan. Cari pla perekam pada menu edit ubin."
                }
                toast(activity, message)
            }
        } catch (_: Exception) {
            toast(activity, "Tambahkan ubin secara manual melalui menu edit Pengaturan Cepat.")
        }
    }
}