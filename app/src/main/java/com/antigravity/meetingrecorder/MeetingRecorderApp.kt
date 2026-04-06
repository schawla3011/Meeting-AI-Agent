package com.antigravity.meetingrecorder

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * Application class — applies the saved theme (dark/light) before any Activity is created.
 */
class MeetingRecorderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val theme  = prefs.getString(KEY_THEME, THEME_DARK)
        AppCompatDelegate.setDefaultNightMode(
            if (theme == THEME_LIGHT) AppCompatDelegate.MODE_NIGHT_NO
            else AppCompatDelegate.MODE_NIGHT_YES
        )
    }

    companion object {
        const val PREFS_NAME  = "pravah_prefs"
        const val KEY_THEME   = "theme_mode"
        const val THEME_DARK  = "dark"
        const val THEME_LIGHT = "light"
    }
}
