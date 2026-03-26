package com.antigravity.meetingrecorder

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class AuthActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        val viewPager = findViewById<ViewPager2>(R.id.auth_view_pager)
        val tabLayout = findViewById<TabLayout>(R.id.auth_tabs)

        viewPager.adapter = AuthPagerAdapter(this)
        TabLayoutMediator(tabLayout, viewPager) { tab, pos ->
            tab.text = if (pos == 0) "Sign In" else "Sign Up"
        }.attach()
    }

    private class AuthPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount() = 2
        override fun createFragment(position: Int): Fragment =
            if (position == 0) SignInFragment() else SignUpFragment()
    }

    // -------------------------------------------------------------------------

    class SignInFragment : Fragment() {

        private lateinit var etEmail: EditText
        private lateinit var etPassword: EditText
        private lateinit var btnSignIn: Button
        private lateinit var tvForgot: TextView
        private lateinit var progress: ProgressBar

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            inflater.inflate(R.layout.fragment_sign_in, container, false)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            etEmail    = view.findViewById(R.id.et_signin_email)
            etPassword = view.findViewById(R.id.et_signin_password)
            btnSignIn  = view.findViewById(R.id.btn_signin)
            tvForgot   = view.findViewById(R.id.tv_forgot_password)
            progress   = view.findViewById(R.id.signin_progress)

            btnSignIn.setOnClickListener { attemptSignIn() }
            tvForgot.setOnClickListener  { sendPasswordReset() }
        }

        private fun attemptSignIn() {
            val email    = etEmail.text.toString().trim()
            val password = etPassword.text.toString()
            if (!validate(email, password)) return

            setLoading(true)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password).await()
                    withContext(Dispatchers.Main) {
                        startActivity(Intent(requireContext(), MainActivity::class.java))
                        requireActivity().finish()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        setLoading(false)
                        toast("Sign in failed: ${e.message}")
                    }
                }
            }
        }

        private fun sendPasswordReset() {
            val email = etEmail.text.toString().trim()
            if (email.isBlank()) { toast("Enter your email first"); return }
            FirebaseAuth.getInstance().sendPasswordResetEmail(email)
                .addOnSuccessListener { toast("Reset link sent to $email") }
                .addOnFailureListener { toast("Error: ${it.message}") }
        }

        private fun validate(email: String, password: String): Boolean {
            if (email.isBlank())    { etEmail.error    = "Required"; return false }
            if (password.isBlank()) { etPassword.error = "Required"; return false }
            return true
        }

        private fun setLoading(on: Boolean) {
            progress.visibility = if (on) View.VISIBLE else View.GONE
            btnSignIn.isEnabled = !on
        }

        private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }

    // -------------------------------------------------------------------------

    class SignUpFragment : Fragment() {

        private lateinit var etName: EditText
        private lateinit var etEmail: EditText
        private lateinit var etPassword: EditText
        private lateinit var etCompany: EditText
        private lateinit var etIndustry: EditText
        private lateinit var etDesignation: EditText
        private lateinit var btnSignUp: Button
        private lateinit var progress: ProgressBar

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            inflater.inflate(R.layout.fragment_sign_up, container, false)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            etName        = view.findViewById(R.id.et_name)
            etEmail       = view.findViewById(R.id.et_signup_email)
            etPassword    = view.findViewById(R.id.et_signup_password)
            etCompany     = view.findViewById(R.id.et_company)
            etIndustry    = view.findViewById(R.id.et_industry)
            etDesignation = view.findViewById(R.id.et_designation)
            btnSignUp     = view.findViewById(R.id.btn_signup)
            progress      = view.findViewById(R.id.signup_progress)

            btnSignUp.setOnClickListener { attemptSignUp() }
        }

        private fun attemptSignUp() {
            val name        = etName.text.toString().trim()
            val email       = etEmail.text.toString().trim()
            val password    = etPassword.text.toString()
            val company     = etCompany.text.toString().trim()
            val industry    = etIndustry.text.toString().trim()
            val designation = etDesignation.text.toString().trim()

            if (!validate(name, email, password)) return
            setLoading(true)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = FirebaseAuth.getInstance()
                        .createUserWithEmailAndPassword(email, password).await()

                    val uid     = result.user!!.uid
                    val profile = UserProfile(uid, name, email, company, industry, designation)

                    FirebaseFirestore.getInstance()
                        .collection("users").document(uid)
                        .set(profile).await()

                    withContext(Dispatchers.Main) {
                        startActivity(Intent(requireContext(), MainActivity::class.java))
                        requireActivity().finish()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        setLoading(false)
                        toast("Sign up failed: ${e.message}")
                    }
                }
            }
        }

        private fun validate(name: String, email: String, password: String): Boolean {
            if (name.isBlank())     { etName.error     = "Required"; return false }
            if (email.isBlank())    { etEmail.error    = "Required"; return false }
            if (password.length < 6){ etPassword.error = "Min 6 chars"; return false }
            return true
        }

        private fun setLoading(on: Boolean) {
            progress.visibility = if (on) View.VISIBLE else View.GONE
            btnSignUp.isEnabled = !on
        }

        private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }
}
