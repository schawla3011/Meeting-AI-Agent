package com.antigravity.meetingrecorder

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.antigravity.meetingrecorder.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ------------------------------------------------------------------
    // Service binding
    // ------------------------------------------------------------------
    private var recordingService: RecordingService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            recordingService = (binder as? RecordingService.LocalBinder)?.getService()
            serviceBound = true
            syncUiWithService()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            serviceBound = false
        }
    }

    // ------------------------------------------------------------------
    // Timer
    // ------------------------------------------------------------------
    private val handler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            val service = recordingService ?: return
            if (service.isRecording) {
                val elapsed = System.currentTimeMillis() - service.recordingStartTimeMs
                binding.tvTimer.text = formatElapsed(elapsed)
                handler.postDelayed(this, 500L)
            }
        }
    }

    // ------------------------------------------------------------------
    // Broadcast receiver
    // ------------------------------------------------------------------
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RecordingService.ACTION_RECORDING_STATE -> {
                    val recording  = intent.getBooleanExtra(RecordingService.EXTRA_IS_RECORDING, false)
                    val uploading  = intent.getBooleanExtra(RecordingService.EXTRA_IS_UPLOADING, false)
                    updateUi(recording = recording, uploading = uploading)
                    if (!recording) stopTimer()
                }
                RecordingService.ACTION_FILE_SAVED -> {
                    val name = intent.getStringExtra(RecordingService.EXTRA_FILE_NAME)
                    // Brief toast: file is saved; upload is about to start
                    showToast("✅ Saved: $name")
                    showUploadStatus(getString(R.string.upload_uploading), isError = false)
                }
                RecordingService.ACTION_UPLOAD_SUCCESS -> {
                    val filename   = intent.getStringExtra(RecordingService.EXTRA_UPLOAD_FILENAME) ?: ""
                    val sizeKb     = intent.getDoubleExtra(RecordingService.EXTRA_UPLOAD_SIZE_KB, 0.0)
                    val transcript = intent.getStringExtra(RecordingService.EXTRA_UPLOAD_TRANSCRIPT) ?: ""
                    showUploadStatus(
                        getString(R.string.upload_success, filename, sizeKb),
                        isError = false
                    )
                    showTranscript(transcript)
                    updateUi(recording = false, uploading = false)
                }
                RecordingService.ACTION_UPLOAD_FAILURE -> {
                    val error     = intent.getStringExtra(RecordingService.EXTRA_UPLOAD_ERROR) ?: "Unknown"
                    val isNetwork = intent.getBooleanExtra(RecordingService.EXTRA_UPLOAD_IS_NETWORK_ERR, false)
                    showUploadStatus(
                        getString(if (isNetwork) R.string.upload_network_error else R.string.upload_error, error),
                        isError = true
                    )
                    updateUi(recording = false, uploading = false)
                }
                RecordingService.ACTION_ERROR -> {
                    val msg = intent.getStringExtra(RecordingService.EXTRA_ERROR_MESSAGE)
                    showToast("⚠️ Error: $msg")
                    updateUi(recording = false, uploading = false)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Permission launcher
    // ------------------------------------------------------------------
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[android.Manifest.permission.RECORD_AUDIO] == true) {
                startRecording()
            } else {
                showToast("Microphone permission is required to record meetings.")
            }
        }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
        registerStateReceiver()
        bindToService()
    }

    override fun onResume() {
        super.onResume()
        syncUiWithService()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stateReceiver)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        handler.removeCallbacks(timerRunnable)
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    private fun setupClickListeners() {
        binding.btnStartMeeting.setOnClickListener {
            if (PermissionHelper.allGranted(this)) startRecording()
            else permissionLauncher.launch(PermissionHelper.requiredPermissions())
        }
        binding.btnStopMeeting.setOnClickListener { stopRecording() }
        binding.btnSettings.setOnClickListener { showServerUrlDialog() }
    }

    private fun registerStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(RecordingService.ACTION_RECORDING_STATE)
            addAction(RecordingService.ACTION_FILE_SAVED)
            addAction(RecordingService.ACTION_UPLOAD_SUCCESS)
            addAction(RecordingService.ACTION_UPLOAD_FAILURE)
            addAction(RecordingService.ACTION_ERROR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
    }

    private fun bindToService() {
        bindService(Intent(this, RecordingService::class.java), serviceConnection, 0)
    }

    // ------------------------------------------------------------------
    // Recording control
    // ------------------------------------------------------------------

    private fun startRecording() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, RecordingService::class.java).apply { action = RecordingService.ACTION_START }
        )
        if (!serviceBound) {
            bindService(
                Intent(this, RecordingService::class.java),
                serviceConnection, BIND_AUTO_CREATE
            )
        }
        clearTranscript()
        clearUploadStatus()
        updateUi(recording = true, uploading = false)
        startTimer()
    }

    private fun stopRecording() {
        startService(
            Intent(this, RecordingService::class.java).apply { action = RecordingService.ACTION_STOP }
        )
        updateUi(recording = false, uploading = true)
        stopTimer()
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------

    private fun syncUiWithService() {
        val service = recordingService
        when {
            service?.isRecording == true -> {
                updateUi(recording = true, uploading = false)
                val elapsed = System.currentTimeMillis() - service.recordingStartTimeMs
                binding.tvTimer.text = formatElapsed(elapsed)
                startTimer()
            }
            service?.isUploading == true -> {
                updateUi(recording = false, uploading = true)
                showUploadStatus(getString(R.string.upload_uploading), isError = false)
            }
            else -> updateUi(recording = false, uploading = false)
        }
    }

    private fun updateUi(recording: Boolean, uploading: Boolean) {
        // Status pill
        binding.statusPill.setBackgroundResource(
            if (recording) R.drawable.bg_status_recording else R.drawable.bg_status_idle
        )
        binding.tvStatus.text = getString(
            when {
                recording -> R.string.status_recording
                uploading -> R.string.status_uploading
                else      -> R.string.status_idle
            }
        )
        binding.ivStatusDot.visibility = if (recording) View.VISIBLE else View.INVISIBLE

        // Upload progress
        binding.uploadProgressBar.visibility = if (uploading) View.VISIBLE else View.GONE

        // Buttons
        val canStart = !recording && !uploading
        binding.btnStartMeeting.isEnabled = canStart
        binding.btnStartMeeting.alpha     = if (canStart) 1.0f else 0.4f
        binding.btnStopMeeting.isEnabled  = recording
        binding.btnStopMeeting.alpha      = if (recording) 1.0f else 0.4f

        // Timer
        if (!recording) {
            binding.tvTimer.text = "00:00:00"
        }
    }

    private fun showUploadStatus(message: String, isError: Boolean) {
        binding.tvUploadStatus.text = message
        binding.tvUploadStatus.setTextColor(
            getColor(if (isError) android.R.color.holo_red_light else android.R.color.holo_green_light)
        )
        binding.tvUploadStatus.visibility = View.VISIBLE
    }

    private fun clearUploadStatus() {
        binding.tvUploadStatus.visibility = View.GONE
        binding.tvUploadStatus.text = ""
    }

    private fun showTranscript(text: String) {
        binding.transcriptCard.visibility = View.VISIBLE
        binding.tvTranscript.text = if (text.isNotBlank()) text
                                    else getString(R.string.transcript_empty)
    }

    private fun clearTranscript() {
        binding.transcriptCard.visibility = View.GONE
        binding.tvTranscript.text = ""
    }

    private fun startTimer() {
        handler.removeCallbacks(timerRunnable)
        handler.post(timerRunnable)
    }

    private fun stopTimer() { handler.removeCallbacks(timerRunnable) }

    private fun formatElapsed(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun showToast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------
    // Server URL settings dialog
    // ------------------------------------------------------------------

    private fun showServerUrlDialog() {
        val currentUrl = ServerConfig.getBaseUrl(this)

        val input = EditText(this).apply {
            setText(currentUrl)
            setTextColor(0xFFE0E0E0.toInt())
            setHintTextColor(0xFF9E9E9E.toInt())
            hint = "http://192.168.x.x:8000"
            setSingleLine()
            // pad inside the dialog
            val dp16 = (16 * resources.displayMetrics.density).toInt()
            setPadding(dp16, dp16, dp16, dp16)
        }

        val container = LinearLayout(this).apply {
            val dp8 = (8 * resources.displayMetrics.density).toInt()
            setPadding(dp8 * 3, dp8, dp8 * 3, dp8)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("⚙️ Server URL")
            .setMessage(
                "• Emulator: http://10.0.2.2:8000\n" +
                "• Real device: http://<your-Mac-IP>:8000\n\n" +
                "Find Mac IP: System Settings → Wi-Fi → Details"
            )
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newUrl = input.text.toString().trim()
                if (newUrl.isNotBlank()) {
                    val saved = ServerConfig.saveBaseUrl(this, newUrl)
                    val note  = if (saved != newUrl) " (auto-corrected to http://)" else ""
                    showToast("✅ Server URL saved: $saved$note")
                } else {
                    showToast("⚠️ URL cannot be empty")
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset default") { _, _ ->
                ServerConfig.saveBaseUrl(this, ServerConfig.DEFAULT_URL)
                showToast("🔄 Reset to ${ServerConfig.DEFAULT_URL}")
            }
            .show()
    }
}
