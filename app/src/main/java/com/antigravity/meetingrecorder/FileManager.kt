package com.antigravity.meetingrecorder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Represents where a recording will be / was saved.
 */
sealed class RecordingOutput {
    /** Android 8 & 9 (API 26-28): direct file in public Music/MeetingRecorder/ */
    data class LegacyFile(val file: File) : RecordingOutput()

    /**
     * Android 10+ (API 29+): MediaStore entry.
     * IS_PENDING=1 during recording; set to 0 on [FileManager.finalizeOutput]
     * so the file becomes visible in Files / gallery apps.
     */
    data class MediaStoreEntry(val uri: Uri, val displayName: String) : RecordingOutput()
}

/**
 * Handles all file/URI creation and finalisation for recordings.
 *
 * Visible storage paths:
 *  - Android 10+  → Music/MeetingRecorder/<filename>.m4a   (MediaStore)
 *  - Android 8-9  → /sdcard/Music/MeetingRecorder/<filename>.m4a
 */
object FileManager {

    private const val SUB_DIR   = "MeetingRecorder"
    private const val DATE_FMT  = "yyyyMMdd_HHmmss"
    private const val MIME_TYPE = "audio/mp4"

    /** Creates the output destination before recording starts. */
    fun createOutput(context: Context): RecordingOutput {
        val timestamp = SimpleDateFormat(DATE_FMT, Locale.getDefault()).format(Date())
        val filename  = "meeting_$timestamp.m4a"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: MediaStore — no storage permission required
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME,  filename)
                put(MediaStore.Audio.Media.MIME_TYPE,     MIME_TYPE)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$SUB_DIR")
                put(MediaStore.Audio.Media.IS_PENDING,    1)
            }
            val uri = context.contentResolver
                .insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            RecordingOutput.MediaStoreEntry(uri, filename)
        } else {
            // Android 8-9: direct external storage (needs WRITE_EXTERNAL_STORAGE)
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                SUB_DIR
            )
            if (!dir.exists()) dir.mkdirs()
            RecordingOutput.LegacyFile(File(dir, filename))
        }
    }

    /**
     * Must be called after recording stops on API 29+.
     * Clears IS_PENDING so the file becomes visible to the user.
     */
    fun finalizeOutput(context: Context, output: RecordingOutput) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            output is RecordingOutput.MediaStoreEntry) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(output.uri, values, null, null)
        }
    }

    /** Human-readable path shown in toasts / logs. */
    fun getDisplayPath(output: RecordingOutput): String = when (output) {
        is RecordingOutput.LegacyFile       -> output.file.absolutePath
        is RecordingOutput.MediaStoreEntry  -> "Music/$SUB_DIR/${output.displayName}"
    }

    /** Filename only — for display in the saved-toast. */
    fun getDisplayName(output: RecordingOutput): String = when (output) {
        is RecordingOutput.LegacyFile       -> output.file.name
        is RecordingOutput.MediaStoreEntry  -> output.displayName
    }
}
