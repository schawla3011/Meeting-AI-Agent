package com.antigravity.meetingrecorder

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // After 2 seconds, route based on auth state
        Handler(Looper.getMainLooper()).postDelayed({
            val user = FirebaseAuth.getInstance().currentUser
            val dest  = if (user != null) MainActivity::class.java else AuthActivity::class.java
            startActivity(Intent(this, dest))
            finish()
        }, 2000L)
    }
}
