package com.antigravity.meetingrecorder

/**
 * Result of an audio upload + transcription attempt.
 */
sealed class UploadResult {

    /** Server accepted the file and (optionally) returned a transcript. */
    data class Success(
        val filename:   String,
        val sizeKb:     Double,
        val message:    String,
        val transcript: String,   // empty string if transcription was skipped
    ) : UploadResult()

    /** Upload (or transcription) failed. */
    data class Failure(
        val error:          String,
        val isNetworkError: Boolean = false,
    ) : UploadResult()
}
