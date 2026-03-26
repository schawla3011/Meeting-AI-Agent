package com.antigravity.meetingrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground Service that owns the full meeting lifecycle:
 *   1. Audio recording (via [AudioRecorderManager])
 *   2. Auto-upload to backend (via [AudioUploadManager]) after recording stops
 *
 * The service keeps itself alive (with an updated notification) while uploading,
 * then stops when the upload finishes or fails.
 */
class RecordingService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = LocalBinder()

    // ------------------------------------------------------------------
    // Managers
    // ------------------------------------------------------------------
    private lateinit var recorderManager: AudioRecorderManager
    private lateinit var uploadManager: AudioUploadManager
    private lateinit var audioManager: AudioManager

    private val uploadExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ------------------------------------------------------------------
    // Wake lock
    // ------------------------------------------------------------------
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // ------------------------------------------------------------------
    // State (public for LocalBinder access)
    // ------------------------------------------------------------------
    private var currentOutput: RecordingOutput? = null

    var isRecording: Boolean = false
        private set

    var isUploading: Boolean = false
        private set

    var recordingStartTimeMs: Long = 0L
        private set

    fun getDisplayPath(): String = currentOutput?.let { FileManager.getDisplayPath(it) } ?: "—"
    fun getDisplayName(): String = currentOutput?.let { FileManager.getDisplayName(it) } ?: "—"

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        recorderManager = AudioRecorderManager(applicationContext)
        uploadManager   = AudioUploadManager(applicationContext)
        audioManager    = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP  -> handleStop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) handleStop()
        uploadExecutor.shutdownNow()
    }

    // ------------------------------------------------------------------
    // Recording control
    // ------------------------------------------------------------------

    private fun handleStart() {
        if (isRecording || isUploading) return

        if (!acquireAudioFocus()) {
            Log.w(TAG, "Could not acquire audio focus – aborting.")
            return
        }

        val output = try {
            FileManager.createOutput(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create output: ${e.message}", e)
            broadcastError("Could not create output file: ${e.message}")
            stopSelf()
            return
        }

        try {
            recorderManager.startRecording(output)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            abandonAudioFocus()
            broadcastError("Microphone unavailable: ${e.message}")
            stopSelf()
            return
        }

        currentOutput         = output
        isRecording           = true
        recordingStartTimeMs  = System.currentTimeMillis()

        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildRecordingNotification())
        broadcastStateChange(isRecording = true, isUploading = false)
        Log.i(TAG, "Recording started → ${FileManager.getDisplayPath(output)}")
    }

    private fun handleStop() {
        if (!isRecording) return

        val stoppedOutput = recorderManager.stopRecording()
        isRecording = false
        abandonAudioFocus()

        // Finalise MediaStore so file is immediately visible in Files app
        stoppedOutput?.let { FileManager.finalizeOutput(applicationContext, it) }
        broadcastFileSaved(stoppedOutput)

        Log.i(TAG, "Recording stopped. File: ${stoppedOutput?.let { FileManager.getDisplayPath(it) }}")

        // Kick off upload on background thread; keep service alive
        stoppedOutput?.let { triggerUpload(it) } ?: run {
            currentOutput = null
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ------------------------------------------------------------------
    // Upload
    // ------------------------------------------------------------------

    private fun triggerUpload(output: RecordingOutput) {
        isUploading = true
        updateNotification(buildUploadingNotification())
        broadcastStateChange(isRecording = false, isUploading = true)

        uploadExecutor.submit {
            Log.i(TAG, "Upload started for: ${FileManager.getDisplayName(output)}")
            val result = uploadManager.upload(output)

            isUploading   = false
            currentOutput = null

            when (result) {
                is UploadResult.Success -> {
                    Log.i(TAG, "Upload success: ${result.filename} (${result.sizeKb} KB)")
                    broadcastUploadSuccess(result)
                }
                is UploadResult.Failure -> {
                    Log.w(TAG, "Upload failed: ${result.error}")
                    broadcastUploadFailure(result)
                }
            }

            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ------------------------------------------------------------------
    // Audio Focus
    // ------------------------------------------------------------------

    private fun acquireAudioFocus(): Boolean {
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                handleStop()
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(listener)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                listener, AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }
    }

    // ------------------------------------------------------------------
    // Wake lock
    // ------------------------------------------------------------------

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG::WakeLock")
            .apply { acquire(10 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Recording & Upload", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while recording or uploading"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this, 1,
        Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildRecordingNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔴 Recording in progress")
            .setContentText("Tap to return · recording saved automatically on stop")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppPendingIntent())
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent())
            .setOngoing(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun buildUploadingNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("☁️ Uploading recording…")
            .setContentText("Please wait — do not close the app")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(notification: Notification) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    // ------------------------------------------------------------------
    // Broadcasts
    // ------------------------------------------------------------------

    private fun broadcastStateChange(isRecording: Boolean, isUploading: Boolean) {
        sendBroadcast(Intent(ACTION_RECORDING_STATE).apply {
            putExtra(EXTRA_IS_RECORDING, isRecording)
            putExtra(EXTRA_IS_UPLOADING, isUploading)
        })
    }

    private fun broadcastFileSaved(output: RecordingOutput?) {
        output ?: return
        sendBroadcast(Intent(ACTION_FILE_SAVED).apply {
            putExtra(EXTRA_FILE_NAME, FileManager.getDisplayName(output))
            putExtra(EXTRA_FILE_PATH, FileManager.getDisplayPath(output))
        })
    }

    private fun broadcastUploadSuccess(result: UploadResult.Success) {
        sendBroadcast(Intent(ACTION_UPLOAD_SUCCESS).apply {
            putExtra(EXTRA_UPLOAD_FILENAME,   result.filename)
            putExtra(EXTRA_UPLOAD_SIZE_KB,    result.sizeKb)
            putExtra(EXTRA_UPLOAD_MESSAGE,    result.message)
            putExtra(EXTRA_UPLOAD_TRANSCRIPT, result.transcript)
        })
    }

    private fun broadcastUploadFailure(result: UploadResult.Failure) {
        sendBroadcast(Intent(ACTION_UPLOAD_FAILURE).apply {
            putExtra(EXTRA_UPLOAD_ERROR,          result.error)
            putExtra(EXTRA_UPLOAD_IS_NETWORK_ERR, result.isNetworkError)
        })
    }

    private fun broadcastError(message: String) {
        sendBroadcast(Intent(ACTION_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
        })
    }

    // ------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------
    companion object {
        private const val TAG = "RecordingService"

        const val ACTION_START = "com.antigravity.meetingrecorder.ACTION_START"
        const val ACTION_STOP  = "com.antigravity.meetingrecorder.ACTION_STOP"

        const val ACTION_RECORDING_STATE = "com.antigravity.meetingrecorder.RECORDING_STATE"
        const val ACTION_FILE_SAVED      = "com.antigravity.meetingrecorder.FILE_SAVED"
        const val ACTION_UPLOAD_SUCCESS  = "com.antigravity.meetingrecorder.UPLOAD_SUCCESS"
        const val ACTION_UPLOAD_FAILURE  = "com.antigravity.meetingrecorder.UPLOAD_FAILURE"
        const val ACTION_ERROR           = "com.antigravity.meetingrecorder.ERROR"

        const val EXTRA_IS_RECORDING         = "is_recording"
        const val EXTRA_IS_UPLOADING         = "is_uploading"
        const val EXTRA_FILE_NAME            = "file_name"
        const val EXTRA_FILE_PATH            = "file_path"
        const val EXTRA_UPLOAD_FILENAME      = "upload_filename"
        const val EXTRA_UPLOAD_SIZE_KB       = "upload_size_kb"
        const val EXTRA_UPLOAD_MESSAGE       = "upload_message"
        const val EXTRA_UPLOAD_TRANSCRIPT    = "upload_transcript"
        const val EXTRA_UPLOAD_ERROR         = "upload_error"
        const val EXTRA_UPLOAD_IS_NETWORK_ERR= "upload_is_network_err"
        const val EXTRA_ERROR_MESSAGE        = "error_message"

        const val CHANNEL_ID      = "recording_channel"
        const val NOTIFICATION_ID = 1001
    }
}
