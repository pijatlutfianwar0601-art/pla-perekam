package com.pla.perekam.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.pla.perekam.service.RecorderService
import com.pla.perekam.util.PermissionHelper
import com.pla.perekam.util.Prefs
import com.pla.perekam.util.RecordingStore

class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val prefs = Prefs(context)
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

        if (state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
            if (!prefs.autoMaster || !prefs.autoCellular || !PermissionHelper.hasMic(context)) return

            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            val label = if (prefs.contactName) contactName(context, number) ?: number else null

            RecorderService.send(context, RecorderService.ACTION_START_CALL) {
                putExtra(RecorderService.EXTRA_LABEL, label)
                putExtra(RecorderService.EXTRA_AUTO_STOP, true)
            }
        }

        if (state == TelephonyManager.EXTRA_STATE_IDLE) {
            if (RecorderService.activeType == RecordingStore.CALL) {
                RecorderService.stop(context)
            }
        }
    }

    private fun contactName(context: Context, number: String?): String? {
        if (number.isNullOrBlank()) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}