package com.antigravity.meetingrecorder

import android.content.Context

/**
 * Provides the backend server URL.
 *
 * URL is persisted in SharedPreferences so it can be changed at runtime
 * from the in-app Settings dialog without recompiling.
 *
 * Defaults:
 *  - Emulator  → http://10.0.2.2:8000
 *  - Real device → http://<your-Mac-IP>:8000  (set via Settings ⚙ in the app)
 */
object ServerConfig {

    const val UPLOAD_ENDPOINT      = "/upload-audio"
    const val CONNECT_TIMEOUT_SECS = 15L
    const val READ_TIMEOUT_SECS    = 60L
    const val WRITE_TIMEOUT_SECS   = 120L

    private const val PREFS_NAME    = "server_prefs"
    private const val KEY_BASE_URL  = "base_url"
    const val DEFAULT_URL           = "http://10.0.2.2:8000"

    /** Returns the currently configured base URL (from SharedPreferences). */
    fun getBaseUrl(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, DEFAULT_URL) ?: DEFAULT_URL

    /** Saves a new base URL to SharedPreferences. Auto-corrects https → http for local servers. */
    fun saveBaseUrl(context: Context, url: String): String {
        // Local FastAPI uses plain HTTP. Auto-correct common mistake of typing https://
        val corrected = url.trimEnd('/').let {
            if (it.startsWith("https://")) it.replaceFirst("https://", "http://") else it
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, corrected)
            .apply()
        return corrected   // Return corrected URL so caller can show it in toast
    }
}
