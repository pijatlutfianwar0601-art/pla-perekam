package com.pla.perekam.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecordingStore {
    const val VOICE = "voice"
    const val SCREEN = "screen"
    const val CALL = "call"

    fun folder(context: Context, type: String): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val sub = when (type) {
            SCREEN -> "Recordings/PerekamLayar"
            CALL -> "Recordings/PerekamPanggilan"
            else -> "Recordings/PerekamSuara"
        }
        return File(base, sub).apply { mkdirs() }
    }

    fun newFile(context: Context, type: String, label: String? = null): File {
        val time = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val clean = (label ?: "")
            .replace(Regex("[^\\p{L}\\p{N}_+.-]"), "_")
            .take(45)
        val prefix = when (type) {
            SCREEN -> "Layar"
            CALL -> "Panggilan"
            else -> "Suara"
        }
        val ext = if (type == SCREEN) "mp4" else "m4a"
        val name = if (clean.isBlank()) "${prefix}_$time.$ext" else "${prefix}_${clean}_$time.$ext"
        return File(folder(context, type), name)
    }

    fun all(context: Context, type: String): List<File> {
        return folder(context, type)
            .listFiles { file -> file.isFile && file.length() > 0 }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun export(context: Context, file: File, type: String): Boolean {
        if (!file.exists() || file.length() == 0L) return false

        val sub = when (type) {
            SCREEN -> "Recordings/PerekamLayar"
            CALL -> "Recordings/PerekamPanggilan"
            else -> "Recordings/PerekamSuara"
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(
                        MediaStore.MediaColumns.MIME_TYPE,
                        if (type == SCREEN) "video/mp4" else "audio/mp4"
                    )
                    put(MediaStore.MediaColumns.RELATIVE_PATH, sub)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val collection = if (type == SCREEN) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }

                val uri = context.contentResolver.insert(collection, values) ?: return false
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                } ?: return false

                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    sub
                ).apply { mkdirs() }

                val target = File(dir, file.name)
                file.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }

                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(target.absolutePath),
                    arrayOf(if (type == SCREEN) "video/mp4" else "audio/mp4"),
                    null
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun cleanup(context: Context) {
        val days = Prefs(context).deleteDays
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days.toLong() * 24L * 60L * 60L * 1000L
        all(context, CALL).forEach {
            if (it.lastModified() < cutoff) it.delete()
        }
    }

    fun sizeText(bytes: Long): String {
        return if (bytes >= 1024 * 1024) {
            String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        } else {
            "${maxOf(1, bytes / 1024)} KB"
        }
    }
}