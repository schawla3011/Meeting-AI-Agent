package com.antigravity.meetingrecorder

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * Wraps [MediaRecorder] with production-quality settings for meeting audio.
 *
 * Format  : MPEG-4 container (.m4a)
 * Codec   : AAC-LC
 * Bitrate : 128 kbps
 * Sample  : 44,100 Hz stereo
 *
 * Accepts both [RecordingOutput.LegacyFile] (file path) and
 * [RecordingOutput.MediaStoreEntry] (file descriptor via ContentResolver).
 */
class AudioRecorderManager(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var pendingPfd: ParcelFileDescriptor? = null
    private var activeOutput: RecordingOutput? = null

    val isRecording: Boolean
        get() = mediaRecorder != null

    /**
     * Starts recording into the given [output].
     * Throws if the microphone is unavailable or the output URI can't be opened.
     */
    fun startRecording(output: RecordingOutput) {
        stopRecording()   // ensure clean state

        val recorder = createRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setAudioChannels(2)

            when (output) {
                is RecordingOutput.LegacyFile -> {
                    setOutputFile(output.file.absolutePath)
                }
                is RecordingOutput.MediaStoreEntry -> {
                    val pfd = context.contentResolver
                        .openFileDescriptor(output.uri, "w")
                        ?: throw IllegalStateException("Cannot open FD for ${output.uri}")
                    pendingPfd = pfd
                    setOutputFile(pfd.fileDescriptor)
                }
            }

            try {
                prepare()
                start()
            } catch (e: Exception) {
                release()
                pendingPfd?.runCatching { close() }
                pendingPfd = null
                throw e
            }
        }

        mediaRecorder = recorder
        activeOutput  = output
        Log.i(TAG, "Recording started → $output")
    }

    /**
     * Stops and finalises the recording.
     * Returns the [RecordingOutput] that was active, or null if not recording.
     */
    fun stopRecording(): RecordingOutput? {
        val output = activeOutput
        mediaRecorder?.runCatching { stop(); release() }
        mediaRecorder = null
        activeOutput  = null
        pendingPfd?.runCatching { close() }
        pendingPfd = null
        Log.i(TAG, "Recording stopped. output=$output")
        return output
    }

    /** Release all resources without finalising (use on error / onDestroy). */
    fun release() {
        mediaRecorder?.runCatching { release() }
        mediaRecorder = null
        pendingPfd?.runCatching { close() }
        pendingPfd = null
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else MediaRecorder()

    companion object {
        private const val TAG = "AudioRecorderManager"
    }
}
