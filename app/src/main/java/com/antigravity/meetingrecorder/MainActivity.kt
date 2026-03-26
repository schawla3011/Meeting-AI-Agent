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
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.antigravity.meetingrecorder.databinding.ActivityMainBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var userProfile: UserProfile? = null
    private var recordingService: RecordingService? = null
    private var serviceBound = false

    private val auth = FirebaseAuth.getInstance()
    private val db   = FirebaseFirestore.getInstance()

    private val handler = Handler(Looper.getMainLooper())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------
    // Service binding
    // ------------------------------------------------------------------
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: IBinder?) {
            recordingService = (binder as? RecordingService.LocalBinder)?.getService()
            serviceBound = true
            syncUiWithService()
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            recordingService = null
            serviceBound = false
        }
    }

    // ------------------------------------------------------------------
    // Timer
    // ------------------------------------------------------------------
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
                    showUploadStatus(getString(R.string.upload_transcribing), isError = false)
                }
                RecordingService.ACTION_UPLOAD_COMPLETE -> {
                    val filename     = intent.getStringExtra(RecordingService.EXTRA_UPLOAD_FILENAME) ?: ""
                    val sizeKb       = intent.getDoubleExtra(RecordingService.EXTRA_UPLOAD_SIZE_KB, 0.0)
                    val transcript   = intent.getStringExtra(RecordingService.EXTRA_UPLOAD_TRANSCRIPT) ?: ""
                    val analysisJson = intent.getStringExtra(RecordingService.EXTRA_UPLOAD_ANALYSIS) ?: ""

                    showUploadStatus(getString(R.string.upload_success, filename, sizeKb), isError = false)
                    showAnalysis(analysisJson)
                    showTranscript(transcript)
                    updateUi(recording = false, uploading = false)

                    // Send MOM email in background
                    sendMomEmail(transcript, analysisJson)
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
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[android.Manifest.permission.RECORD_AUDIO] == true) startRecording()
            else showToast("Microphone permission is required to record meetings.")
        }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Guard: must be logged in
        if (auth.currentUser == null) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadUserProfile()
        setupClickListeners()
        registerStateReceiver()
        bindToService()
    }

    override fun onResume()  { super.onResume();  syncUiWithService() }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stateReceiver)
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
        handler.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------------------
    // Profile
    // ------------------------------------------------------------------

    private fun loadUserProfile() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val profile = doc.toObject(UserProfile::class.java)
                userProfile = profile
                profile?.let { updateProfileHeader(it) }
            }
            .addOnFailureListener {
                Log.w("MainActivity", "Profile load failed: ${it.message}")
            }
    }

    private fun updateProfileHeader(profile: UserProfile) {
        val initials = profile.name.split(" ")
            .take(2).joinToString("") { it.firstOrNull()?.uppercase() ?: "" }

        binding.tvAvatar.text          = initials.ifBlank { "?" }
        binding.tvUserName.text        = profile.name.ifBlank { profile.email }
        binding.tvUserDesignation.text = listOfNotNull(
            profile.designation.ifBlank { null },
            profile.company.ifBlank { null }
        ).joinToString(" · ")
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
        binding.btnSettings.setOnClickListener    { showServerUrlDialog() }
        binding.btnLogout.setOnClickListener      { confirmLogout() }
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
            bindService(Intent(this, RecordingService::class.java), serviceConnection, BIND_AUTO_CREATE)
        }
        clearResults()
        updateUi(recording = true, uploading = false)
        startTimer()
    }

    private fun stopRecording() {
        startService(Intent(this, RecordingService::class.java).apply { action = RecordingService.ACTION_STOP })
        updateUi(recording = false, uploading = true)
        stopTimer()
    }

    // ------------------------------------------------------------------
    // Logout
    // ------------------------------------------------------------------

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Sign Out")
            .setMessage("Are you sure you want to sign out?")
            .setPositiveButton("Sign Out") { _, _ ->
                auth.signOut()
                startActivity(Intent(this, AuthActivity::class.java))
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        binding.ivStatusDot.visibility  = if (recording) View.VISIBLE else View.INVISIBLE
        binding.uploadProgressBar.visibility = if (uploading) View.VISIBLE else View.GONE

        val canStart = !recording && !uploading
        binding.btnStartMeeting.isEnabled = canStart
        binding.btnStartMeeting.alpha     = if (canStart) 1f else 0.4f
        binding.btnStopMeeting.isEnabled  = recording
        binding.btnStopMeeting.alpha      = if (recording) 1f else 0.4f

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

            if (summary.isNotBlank()) {
                binding.tvSummary.text        = summary
                binding.summaryCard.visibility = View.VISIBLE
            }

            binding.tasksContainer.removeAllViews()
            if (tasks != null && tasks.length() > 0) {
                for (i in 0 until tasks.length()) {
                    val task = tasks.getJSONObject(i)
                    addTaskRow(
                        taskText = task.optString("task", ""),
                        owner    = task.optString("owner", "Unassigned"),
                        deadline = task.optString("deadline", "Not specified"),
                        isLast   = i == tasks.length() - 1
                    )
                }
                binding.tasksCard.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Analysis parse error", e)
        }
    }

    private fun addTaskRow(taskText: String, owner: String, deadline: String, isLast: Boolean) {
        val dp = resources.displayMetrics.density

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
        }

        // Task title
        row.addView(TextView(this).apply {
            text      = taskText
            textSize  = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (6 * dp).toInt() }
        })

        // Owner + deadline row
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (8 * dp).toInt() }
        }

        meta.addView(TextView(this).apply {
            text      = "👤 $owner"
            textSize  = 11f
            setTextColor(getColor(R.color.owner_chip_text))
            setBackgroundColor(getColor(R.color.owner_chip_bg))
            setPadding((8 * dp).toInt(), (3 * dp).toInt(), (8 * dp).toInt(), (3 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = (10 * dp).toInt() }
        })

        if (deadline.isNotBlank() && !deadline.equals("not specified", ignoreCase = true)) {
            meta.addView(TextView(this).apply {
                text      = "🗓 $deadline"
                textSize  = 11f
                setTextColor(getColor(R.color.deadline_text))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = (8 * dp).toInt() }
            })
        }

        // Spacer
        meta.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })

        // 📅 Add to Calendar button
        val calBtn = Button(this).apply {
            text      = "📅"
            textSize  = 14f
            setTextColor(getColor(R.color.primary))
            background = null
            contentDescription = "Add to calendar"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                CalendarHelper.addTaskToCalendar(this@MainActivity, taskText, owner, deadline)
            }
        }
        meta.addView(calBtn)

        row.addView(meta)

        if (!isLast) {
            row.addView(View(this).apply {
                setBackgroundColor(getColor(R.color.divider))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
            })
        }

        binding.tasksContainer.addView(row)
    }

    // ------------------------------------------------------------------
    // Email MOM
    // ------------------------------------------------------------------

    private fun sendMomEmail(transcript: String, analysisJson: String) {
        val profile = userProfile ?: return
        if (profile.email.isBlank()) return

        val date = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()).format(Date())
        var summary = ""
        var tasksArray = JSONArray()

        // Parse analysis
        if (analysisJson.isNotBlank()) {
            try {
                val obj = JSONObject(analysisJson)
                summary     = obj.optString("summary", "")
                tasksArray  = obj.optJSONArray("tasks") ?: JSONArray()
            } catch (_: Exception) {}
        }

        val payload = JSONObject().apply {
            put("to_email",   profile.email)
            put("user_name",  profile.name)
            put("company",    profile.company)
            put("designation", profile.designation)
            put("meeting_date", date)
            put("summary",    summary)
            put("tasks",      tasksArray)
            put("transcript", transcript)
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val baseUrl = ServerConfig.getBaseUrl(applicationContext)
                val request = Request.Builder()
                    .url("$baseUrl/send-mom")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                val success  = response.isSuccessful
                Log.i("MainActivity", "MOM email: HTTP ${response.code}")

                withContext(Dispatchers.Main) {
                    binding.tvEmailSent.text = if (success)
                        "📧 MOM emailed to ${profile.email}"
                    else
                        "⚠️ Email send failed (check SMTP config on server)"
                    binding.tvEmailSent.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "sendMomEmail error: $e")
                withContext(Dispatchers.Main) {
                    binding.tvEmailSent.text = "⚠️ Could not send email: ${e.message}"
                    binding.tvEmailSent.visibility = View.VISIBLE
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Transcript
    // ------------------------------------------------------------------

    private fun showTranscript(text: String) {
        binding.transcriptCard.visibility = View.VISIBLE
        binding.tvTranscript.text = if (text.isNotBlank()) text
                                    else getString(R.string.transcript_empty)
    }

    private fun clearResults() {
        listOf(binding.tvUploadStatus, binding.summaryCard, binding.tasksCard,
               binding.tvEmailSent, binding.transcriptCard).forEach {
            it.visibility = View.GONE
        }
        binding.tvTranscript.text  = ""
        binding.tvSummary.text     = ""
        binding.tasksContainer.removeAllViews()
    }

    // ------------------------------------------------------------------
    // Timer
    // ------------------------------------------------------------------

    private fun startTimer() { handler.removeCallbacks(timerRunnable); handler.post(timerRunnable) }
    private fun stopTimer()  { handler.removeCallbacks(timerRunnable) }

    private fun formatElapsed(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------
    // Server URL settings dialog
    // ------------------------------------------------------------------

    private fun showServerUrlDialog() {
        val input = EditText(this).apply {
            setText(ServerConfig.getBaseUrl(this@MainActivity))
            setTextColor(0xFFE0E0E0.toInt())
            hint = "https://your-app.onrender.com"
            setSingleLine()
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        val wrap = LinearLayout(this).apply {
            val p = (8 * resources.displayMetrics.density).toInt()
            setPadding(p * 3, p, p * 3, p)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("⚙️ Server URL")
            .setMessage(
                "Cloud: https://meeting-ai-agent-6emk.onrender.com\n" +
                "Emulator: http://10.0.2.2:8000"
            )
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                val saved = ServerConfig.saveBaseUrl(this, input.text.toString().trim())
                showToast("✅ Saved: $saved")
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset") { _, _ ->
                ServerConfig.saveBaseUrl(this, ServerConfig.DEFAULT_URL)
                showToast("🔄 Reset to ${ServerConfig.DEFAULT_URL}")
            }
            .show()
    }
}
