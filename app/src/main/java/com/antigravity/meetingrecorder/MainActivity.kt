package com.antigravity.meetingrecorder

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.antigravity.meetingrecorder.databinding.ActivityMainBinding
import org.json.JSONObject

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
                    val recording = intent.getBooleanExtra(RecordingService.EXTRA_IS_RECORDING, false)
                    val uploading = intent.getBooleanExtra(RecordingService.EXTRA_IS_UPLOADING, false)
                    updateUi(recording = recording, uploading = uploading)
                    if (!recording) stopTimer()
                }
                RecordingService.ACTION_FILE_SAVED -> {
                    val name = intent.getStringExtra(RecordingService.EXTRA_FILE_NAME)
                    showToast("✅ Saved: $name")
                    showUploadStatus(getString(R.string.upload_uploading), isError = false)
                }
                RecordingService.ACTION_UPLOAD_SUCCESS -> {
                    // File is on the server; GPT analysis running in background
                    showUploadStatus(getString(R.string.upload_transcribing), isError = false)
                }
                RecordingService.ACTION_UPLOAD_COMPLETE -> {
                    val filename   = intent.getStringExtra(RecordingService.EXTRA_UPLOAD_FILENAME) ?: ""
                    val sizeKb     = intent.getDoubleExtra(RecordingService.EXTRA_UPLOAD_SIZE_KB, 0.0)
                    val transcript = intent.getStringExtra(RecordingService.EXTRA_UPLOAD_TRANSCRIPT) ?: ""
                    val analysisJson = intent.getStringExtra(RecordingService.EXTRA_UPLOAD_ANALYSIS) ?: ""
                    showUploadStatus(getString(R.string.upload_success, filename, sizeKb), isError = false)
                    showAnalysis(analysisJson)
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
            addAction(RecordingService.ACTION_UPLOAD_COMPLETE)
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
        clearResults()
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
        binding.uploadProgressBar.visibility = if (uploading) View.VISIBLE else View.GONE

        val canStart = !recording && !uploading
        binding.btnStartMeeting.isEnabled = canStart
        binding.btnStartMeeting.alpha     = if (canStart) 1.0f else 0.4f
        binding.btnStopMeeting.isEnabled  = recording
        binding.btnStopMeeting.alpha      = if (recording) 1.0f else 0.4f

        if (!recording) binding.tvTimer.text = "00:00:00"
    }

    private fun showUploadStatus(message: String, isError: Boolean) {
        binding.tvUploadStatus.text = message
        binding.tvUploadStatus.setTextColor(
            getColor(if (isError) android.R.color.holo_red_light else R.color.text_secondary)
        )
        binding.tvUploadStatus.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------
    // Analysis display (Summary + Tasks)
    // ------------------------------------------------------------------

    /**
     * Parses the GPT analysis JSON and populates the Summary and Tasks cards.
     * analysisJson: {"summary": "• ...\n• ...", "tasks": [{"task": ..., "owner": ..., "deadline": ...}]}
     */
    private fun showAnalysis(analysisJson: String) {
        if (analysisJson.isBlank()) {
            binding.summaryCard.visibility = View.GONE
            binding.tasksCard.visibility   = View.GONE
            return
        }

        try {
            val obj     = JSONObject(analysisJson)
            val summary = obj.optString("summary", "").trim()
            val tasks   = obj.optJSONArray("tasks")

            // --- Summary card ---
            if (summary.isNotBlank()) {
                binding.tvSummary.text = summary
                binding.summaryCard.visibility = View.VISIBLE
            } else {
                binding.summaryCard.visibility = View.GONE
            }

            // --- Tasks card ---
            binding.tasksContainer.removeAllViews()
            if (tasks != null && tasks.length() > 0) {
                for (i in 0 until tasks.length()) {
                    val task = tasks.getJSONObject(i)
                    addTaskRow(
                        taskText     = task.optString("task", ""),
                        owner        = task.optString("owner", "Unassigned"),
                        deadline     = task.optString("deadline", "Not specified"),
                        isLast       = i == tasks.length() - 1
                    )
                }
                binding.tasksCard.visibility = View.VISIBLE
            } else {
                // Show empty state
                val empty = TextView(this).apply {
                    text      = getString(R.string.tasks_empty)
                    textSize  = 14f
                    setTextColor(getColor(R.color.text_hint))
                }
                binding.tasksContainer.addView(empty)
                binding.tasksCard.visibility = View.VISIBLE
            }

        } catch (e: Exception) {
            // Malformed JSON — hide analysis cards silently
            binding.summaryCard.visibility = View.GONE
            binding.tasksCard.visibility   = View.GONE
        }
    }

    /**
     * Inflates a single task row and adds it to tasksContainer.
     */
    private fun addTaskRow(taskText: String, owner: String, deadline: String, isLast: Boolean) {
        val dp = resources.displayMetrics.density

        // Outer row container
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val vPad = (12 * dp).toInt()
            setPadding(0, vPad, 0, vPad)
        }

        // Task text
        val tvTask = TextView(this).apply {
            text      = taskText
            textSize  = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (6 * dp).toInt()
            layoutParams = lp
        }
        row.addView(tvTask)

        // Meta row: owner chip + deadline
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
        }

        // Owner chip
        val ownerChip = TextView(this).apply {
            text      = "👤 $owner"
            textSize  = 12f
            setTextColor(getColor(R.color.owner_chip_text))
            setBackgroundColor(getColor(R.color.owner_chip_bg))
            val hPad = (8 * dp).toInt()
            val vPad = (3 * dp).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (12 * dp).toInt()
            layoutParams = lp
        }
        meta.addView(ownerChip)

        // Deadline (only if meaningful)
        if (deadline.isNotBlank() && !deadline.equals("not specified", ignoreCase = true)) {
            val tvDeadline = TextView(this).apply {
                text      = "🗓 $deadline"
                textSize  = 12f
                setTextColor(getColor(R.color.deadline_text))
            }
            meta.addView(tvDeadline)
        }

        row.addView(meta)

        // Divider between tasks
        if (!isLast) {
            val divider = View(this).apply {
                setBackgroundColor(getColor(R.color.divider))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
            }
            row.addView(divider)
        }

        binding.tasksContainer.addView(row)
    }

    // ------------------------------------------------------------------
    // Transcript display
    // ------------------------------------------------------------------

    private fun showTranscript(text: String) {
        binding.transcriptCard.visibility = View.VISIBLE
        binding.tvTranscript.text = if (text.isNotBlank()) text
                                    else getString(R.string.transcript_empty)
    }

    private fun clearResults() {
        binding.tvUploadStatus.visibility  = View.GONE
        binding.tvUploadStatus.text        = ""
        binding.summaryCard.visibility     = View.GONE
        binding.tasksCard.visibility       = View.GONE
        binding.transcriptCard.visibility  = View.GONE
        binding.tvTranscript.text          = ""
        binding.tvSummary.text             = ""
        binding.tasksContainer.removeAllViews()
    }

    // ------------------------------------------------------------------
    // Timer
    // ------------------------------------------------------------------

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
            hint = "https://your-app.onrender.com"
            setSingleLine()
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
                "• Cloud: https://your-app.onrender.com\n" +
                "• Emulator: http://10.0.2.2:8000\n" +
                "• Real device: http://<Mac-IP>:8000"
            )
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newUrl = input.text.toString().trim()
                if (newUrl.isNotBlank()) {
                    val saved = ServerConfig.saveBaseUrl(this, newUrl)
                    showToast("✅ Server URL saved: $saved")
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
