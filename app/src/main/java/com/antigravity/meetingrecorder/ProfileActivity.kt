package com.antigravity.meetingrecorder

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class ProfileActivity : AppCompatActivity() {

    private val auth = FirebaseAuth.getInstance()
    private val db   = FirebaseFirestore.getInstance()

    private lateinit var tvAvatar:      TextView
    private lateinit var tvEmail:       TextView
    private lateinit var etName:        EditText
    private lateinit var etCompany:     EditText
    private lateinit var etIndustry:    EditText
    private lateinit var etDesignation: EditText
    private lateinit var progress:      ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        tvAvatar      = findViewById(R.id.tv_profile_avatar)
        tvEmail       = findViewById(R.id.tv_profile_email_display)
        etName        = findViewById(R.id.et_profile_name)
        etCompany     = findViewById(R.id.et_profile_company)
        etIndustry    = findViewById(R.id.et_profile_industry)
        etDesignation = findViewById(R.id.et_profile_designation)
        progress      = findViewById(R.id.profile_progress)

        findViewById<TextView>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_save).setOnClickListener { saveProfile() }

        loadProfile()
    }

    private fun loadProfile() {
        val uid = auth.currentUser?.uid ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val doc     = db.collection("users").document(uid).get().await()
                val profile = doc.toObject(UserProfile::class.java)
                withContext(Dispatchers.Main) {
                    profile?.let {
                        val initials = it.name.split(" ")
                            .take(2).joinToString("") { w -> w.firstOrNull()?.uppercase() ?: "" }
                        tvAvatar.text       = initials.ifBlank { "P" }
                        tvEmail.text        = it.email
                        etName.setText(it.name)
                        etCompany.setText(it.company)
                        etIndustry.setText(it.industry)
                        etDesignation.setText(it.designation)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Could not load profile: ${e.message}") }
            }
        }
    }

    private fun saveProfile() {
        val uid  = auth.currentUser?.uid ?: return
        val name = etName.text.toString().trim()
        if (name.isBlank()) { etName.error = "Name is required"; return }

        val email = auth.currentUser?.email ?: ""
        val updatedProfile = UserProfile(
            uid         = uid,
            name        = name,
            email       = email,
            company     = etCompany.text.toString().trim(),
            industry    = etIndustry.text.toString().trim(),
            designation = etDesignation.text.toString().trim()
        )

        progress.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.collection("users").document(uid).set(updatedProfile).await()
                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    toast("Profile saved ✓")
                    // Update avatar
                    val initials = name.split(" ")
                        .take(2).joinToString("") { it.firstOrNull()?.uppercase() ?: "" }
                    tvAvatar.text = initials.ifBlank { "P" }
                    setResult(RESULT_OK)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    toast("Save failed: ${e.message}")
                }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
