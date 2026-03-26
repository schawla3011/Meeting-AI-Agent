package com.antigravity.meetingrecorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Utility helpers for runtime permission checks.
 */
object PermissionHelper {

    /** All permissions the app needs at runtime. */
    fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        // WRITE_EXTERNAL_STORAGE only needed on Android 8 & 9 (API 26-28)
        // for saving to the public Music folder
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    /** Returns true only when every required permission is granted. */
    fun allGranted(context: Context): Boolean =
        requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
        }

    /** Returns true when microphone permission specifically is granted. */
    fun micGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    /** Maps a result array back to denied permission names. */
    fun deniedPermissions(
        permissions: Array<out String>,
        grantResults: IntArray
    ): List<String> =
        permissions.filterIndexed { index, _ ->
            grantResults[index] != PackageManager.PERMISSION_GRANTED
        }
}
