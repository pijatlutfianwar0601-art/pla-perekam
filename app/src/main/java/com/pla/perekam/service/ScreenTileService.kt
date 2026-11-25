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
            Re