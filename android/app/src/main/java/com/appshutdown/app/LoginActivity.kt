package com.appshutdown.app

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        if (AuthManager.isLoggedIn(this)) {
            goMain()
            return
        }

        val etUrl: EditText = findViewById(R.id.etUrl)
        val etId: EditText = findViewById(R.id.etId)
        val etPw: EditText = findViewById(R.id.etPw)
        val btnLogin: Button = findViewById(R.id.btnLogin)
        val btnGoSignup: Button = findViewById(R.id.btnGoSignup)
        val tvError: TextView = findViewById(R.id.tvError)
        etUrl.setText(AuthManager.getBaseUrl(this))

        btnLogin.setOnClickListener {
            val url = etUrl.text.toString().trim()
            val id = etId.text.toString().trim()
            val pw = etPw.text.toString()
            if (id.isEmpty() || pw.isEmpty()) {
                tvError.text = "아이디/비밀번호 입력"
                return@setOnClickListener
            }
            if (url.isNotEmpty()) AuthManager.setBaseUrl(this, url)
            tvError.text = "로그인 중..."
            lifecycleScope.launch {
                try {
                    val res = withContext(Dispatchers.IO) {
                        ApiClient.get(this@LoginActivity).login(AuthReq(id, pw))
                    }
                    if (res.isSuccessful && res.body() != null) {
                        AuthManager.saveToken(this@LoginActivity, res.body()!!.token)
                        BlockMonitorService.start(this@LoginActivity)
                        goMain()
                    } else {
                        val msg = res.errorBody()?.string() ?: "로그인 실패"
                        tvError.text = parseErr(msg)
                    }
                } catch (e: Exception) {
                    tvError.text = "서버 접속 실패: ${e.message}\n(URL 확인: 에뮬레이터=10.0.2.2, 실기기=PC IP)"
                }
            }
        }
        btnGoSignup.setOnClickListener {
            AuthManager.setBaseUrl(this, etUrl.text.toString().trim().ifEmpty { AuthManager.getBaseUrl(this) })
            startActivity(Intent(this, SignupActivity::class.java))
        }
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun parseErr(json: String): String {
        return try {
            val m = com.google.gson.Gson().fromJson(json, Map::class.java)
            m["error"]?.toString() ?: "로그인 실패"
        } catch (_: Exception) { "로그인 실패" }
    }
}
