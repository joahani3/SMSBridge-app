package com.workez365.smsbridge

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException

/**
 * 로그인 / 회원가입 화면
 *
 * - 같은 계정으로 여러 기기(Master + Slave들)에 로그인하면
 *   모두 같은 uid 하위 데이터를 공유 → 그룹이 형성됨
 * - 다른 계정을 쓰는 유저는 데이터가 완전히 분리됨 (Firestore Security Rules로 보장)
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnRegister: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvToggleMode: TextView

    // 현재 모드: true = 로그인, false = 회원가입
    private var isLoginMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()

        // 이미 로그인된 상태라면 MainActivity로 바로 이동
        if (auth.currentUser != null) {
            navigateToMain()
            return
        }

        initViews()
        setupListeners()
    }

    private fun initViews() {
        etEmail      = findViewById(R.id.et_email)
        etPassword   = findViewById(R.id.et_password)
        btnLogin     = findViewById(R.id.btn_login)
        btnRegister  = findViewById(R.id.btn_register)
        progressBar  = findViewById(R.id.progress_bar)
        tvToggleMode = findViewById(R.id.tv_toggle_mode)
    }

    private fun setupListeners() {
        btnLogin.setOnClickListener {
            val email    = etEmail.text.toString().trim()
            val password = etPassword.text.toString()
            if (validateInput(email, password)) signIn(email, password)
        }

        btnRegister.setOnClickListener {
            val email    = etEmail.text.toString().trim()
            val password = etPassword.text.toString()
            if (validateInput(email, password)) createAccount(email, password)
        }

        // 로그인 ↔ 회원가입 전환 텍스트
        tvToggleMode.setOnClickListener { toggleMode() }
    }

    // -------------------------------------------------------
    // Firebase Auth: 로그인
    // -------------------------------------------------------

    private fun signIn(email: String, password: String) {
        showLoading(true)

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                showLoading(false)
                if (task.isSuccessful) {
                    Toast.makeText(this, "로그인 성공!", Toast.LENGTH_SHORT).show()
                    navigateToMain()
                } else {
                    val msg = when (task.exception) {
                        is FirebaseAuthInvalidCredentialsException ->
                            "이메일 또는 비밀번호가 올바르지 않습니다."
                        else ->
                            "로그인 실패: ${task.exception?.message}"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
    }

    // -------------------------------------------------------
    // Firebase Auth: 회원가입
    // -------------------------------------------------------

    private fun createAccount(email: String, password: String) {
        showLoading(true)

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                showLoading(false)
                if (task.isSuccessful) {
                    Toast.makeText(this, "계정 생성 완료! 로그인되었습니다.", Toast.LENGTH_SHORT).show()
                    navigateToMain()
                } else {
                    val msg = when (task.exception) {
                        is FirebaseAuthWeakPasswordException ->
                            "비밀번호는 6자리 이상이어야 합니다."
                        is FirebaseAuthUserCollisionException ->
                            "이미 사용 중인 이메일입니다. 로그인을 시도해주세요."
                        is FirebaseAuthInvalidCredentialsException ->
                            "올바른 이메일 형식을 입력해주세요."
                        else ->
                            "회원가입 실패: ${task.exception?.message}"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
    }

    // -------------------------------------------------------
    // 유틸
    // -------------------------------------------------------

    private fun validateInput(email: String, password: String): Boolean {
        if (email.isBlank()) {
            etEmail.error = "이메일을 입력해주세요"
            etEmail.requestFocus()
            return false
        }
        if (password.length < 6) {
            etPassword.error = "비밀번호는 6자리 이상"
            etPassword.requestFocus()
            return false
        }
        return true
    }

    private fun toggleMode() {
        isLoginMode = !isLoginMode
        if (isLoginMode) {
            btnLogin.visibility    = View.VISIBLE
            btnRegister.visibility = View.GONE
            tvToggleMode.text      = "계정이 없으신가요? 회원가입"
        } else {
            btnLogin.visibility    = View.GONE
            btnRegister.visibility = View.VISIBLE
            tvToggleMode.text      = "이미 계정이 있으신가요? 로그인"
        }
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        btnLogin.isEnabled    = !show
        btnRegister.isEnabled = !show
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish() // 뒤로 가기로 로그인 화면에 돌아올 수 없게 스택에서 제거
    }
}
