package com.antigravity.meetingrecorder

/**
 * Result of an audio upload + background transcription/analysis.
 */
sealed class UploadResult {

    /** Server accepted the file and analysis is complete (or partially available). */
    data class Success(
        val filename:   String,
        val sizeKb:     Double,
        val message:    String,
        val transcript: String,  // empty if transcription skipped
        val analysis:   String,  // raw JSON string {"summary":..., "tasks":[...]}; empty if unavailable
    ) : UploadResult()

    /** Upload failed. */
    data class Failure(
        val error:          String,
        val isNetworkError: Boolean = false,
    ) : UploadResult()
}
