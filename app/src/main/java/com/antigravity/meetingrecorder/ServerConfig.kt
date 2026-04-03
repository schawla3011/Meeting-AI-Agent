package com.antigravity.meetingrecorder

import android.content.Context

/**
 * Provides the backend server URL and all network-related constants.
 *
 * The server URL is hardcoded to the production Render deployment.
 * To change the URL, update BASE_URL here and release a new app version.
 */
object ServerConfig {

    /** Production backend URL — managed via app updates */
    const val BASE_URL = "https://meeting-ai-agent-6emk.onrender.com"

    /** POST: upload audio file */
    const val UPLOAD_ENDPOINT = "/upload-audio"

    /** GET /transcript/{filename}: poll for Whisper result after upload */
    const val TRANSCRIPT_ENDPOINT = "/transcript"

    // OkHttp timeouts
    const val CONNECT_TIMEOUT_SECS = 30L    // longer for Render cold-starts
    const val READ_TIMEOUT_SECS    = 60L
    const val WRITE_TIMEOUT_SECS   = 120L

    // Transcript polling settings
    /** How long to keep polling before giving up (2 minutes) */
    const val TRANSCRIPT_POLL_TIMEOUT_SECS = 120L
    /** How long to wait between poll attempts */
    const val TRANSCRIPT_POLL_INTERVAL_MS  = 4_000L

    /** Returns the hardcoded base URL. Context parameter kept for API compatibility. */
    fun getBaseUrl(context: Context): String = BASE_URL
}
