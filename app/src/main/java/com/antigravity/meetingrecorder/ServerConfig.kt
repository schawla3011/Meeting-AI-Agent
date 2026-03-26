package com.antigravity.meetingrecorder

import android.content.Context

/**
 * Provides the backend server URL and all network-related constants.
 *
 * URL is persisted in SharedPreferences so it can be changed at runtime
 * from the in-app Settings dialog without recompiling.
 *
 * Defaults:
 *  - Cloud (Render) → https://meeting-ai-agent-6emk.onrender.com
 *  - Emulator       → http://10.0.2.2:8000  (change via ⚙ Settings in app)
 *  - Real device    → http://<your-Mac-IP>:8000
 */
object ServerConfig {

    /** POST: upload audio file */
    const val UPLOAD_ENDPOINT      = "/upload-audio"

    /** GET /transcript/{filename}: poll for Whisper result after upload */
    const val TRANSCRIPT_ENDPOINT  = "/transcript"

    // OkHttp timeouts
    const val CONNECT_TIMEOUT_SECS = 30L    // longer for Render cold-starts
    const val READ_TIMEOUT_SECS    = 60L
    const val WRITE_TIMEOUT_SECS   = 120L

    // Transcript polling settings
    /** How long to keep polling before giving up (2 minutes) */
    const val TRANSCRIPT_POLL_TIMEOUT_SECS = 120L
    /** How long to wait between poll attempts */
    const val TRANSCRIPT_POLL_INTERVAL_MS  = 4_000L

    private const val PREFS_NAME   = "server_prefs"
    private const val KEY_BASE_URL = "base_url"

    /** Default: cloud backend (no local server needed) */
    const val DEFAULT_URL = "https://meeting-ai-agent-6emk.onrender.com"

    /** Returns the currently configured base URL (from SharedPreferences). */
    fun getBaseUrl(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, DEFAULT_URL) ?: DEFAULT_URL

    /**
     * Saves a new base URL to SharedPreferences.
     * Auto-corrects https → http ONLY for known local addresses.
     */
    fun saveBaseUrl(context: Context, url: String): String {
        val trimmed   = url.trimEnd('/')
        // Only force HTTP for local/private IPs; leave cloud URLs (https) untouched
        val corrected = if (trimmed.startsWith("https://") && isLocalAddress(trimmed)) {
            trimmed.replaceFirst("https://", "http://")
        } else {
            trimmed
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, corrected)
            .apply()
        return corrected
    }

    private fun isLocalAddress(url: String): Boolean {
        return url.contains("10.0.2.2") ||
               url.contains("localhost") ||
               url.contains("127.0.0.1") ||
               url.contains("192.168.")  ||
               url.contains("10.")
    }
}
