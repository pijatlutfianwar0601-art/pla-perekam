package com.pla.perekam.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.pla.perekam.util.PermissionHelper
import com.pla.perekam.util.RecordingStore

class VoiceTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (RecorderService.running &&
                RecorderService.activeType == RecordingStore.VOICE
            ) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Rekam suara"
            updateTile()
        }
    }

    override fun onClick() {
        if (RecorderService.running && RecorderService.activeType == RecordingStore.VOICE) {
            RecorderService.stop(this)
        } else if (!RecorderService.running && PermissionHelper.hasMic(this)) {
            RecorderService.send(this, RecorderService.ACTION_START_VOICE)
        } else {
            RecorderService.lastMessage = "Buka pla perekam untuk memberi izin atau menghentikan rekaman lain."
        }
    }
}