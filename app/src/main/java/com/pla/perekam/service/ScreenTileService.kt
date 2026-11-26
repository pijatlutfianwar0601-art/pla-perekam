package com.pla.perekam.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.pla.perekam.MainActivity
import com.pla.perekam.util.RecordingStore

class ScreenTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (RecorderService.running &&
                RecorderService.activeType == RecordingStore.SCREEN
            ) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Rekam layar"
            updateTile()
        }
    }

    override fun onClick() {
        if (RecorderService.running && RecorderService.activeType == RecordingStore.SCREEN) {
            RecorderService.stop(this)
            return
        }

        val launch = Intent(this, MainActivity::class.java)
        launch.setAction(MainActivity.ACTION_SCREEN)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        val pending = PendingIntent.getActivity(
            this, 41, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launch)
        }
    }
}