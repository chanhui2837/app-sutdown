package com.appshutdown.app

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SignupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        val etId: EditText = findViewById(R.id.etId)
        val etPw: EditText = findViewById(R.id.etPw)
        val etName: EditText = findViewById(R.id.etName)
        val btnSignup: Button = findViewById(R.id.btnSignup)
        val tvError: TextView = findViewById(R.id.tvError)

        btnSignup.setOnClickListener {
            val id = etId.text.toString().trim()
            val pw = etPw.text.toString()
            val name = etName.text.toString().trim().ifEmpty { id }
            if (id.length < 3) { tvError.text = "아이디 3자 이상"; return@setOnClickListener }
            if (pw.length < 6) { tvError.text = "비밀번호 6자 이상"; return@setOnClickListener }
            tvError.text = "가입 중..."
            lifecycleScope.launch {
                try {
                    val res = withContext(Dispatchers.IO) {
                        ApiClient.get(this@SignupActivity).signup(AuthReq(id, pw, name))
                    }
                    if (res.isSuccessful && res.body() != null) {
                        AuthManager.saveToken(this@SignupActivity, res.body()!!.token)
                        BlockMonitorService.start(this@SignupActivity)
                        Toast.makeText(this@SignupActivity, "가입 완료!", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        val msg = res.errorBody()?.string() ?: "가입 실패"
                        tvError.text = try {
                            com.google.gson.Gson().fromJson(msg, Map::class.java)["error"].toString()
                        } catch (_: Exception) { "가입 실패" }
                    }
                } catch (e: Exception) {
                    tvError.text = "서버 접속 실패: ${e.message}"
                }
            }
        }
    }
}
