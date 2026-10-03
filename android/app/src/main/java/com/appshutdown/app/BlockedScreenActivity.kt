package com.appshutdown.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/** 차단된 앱을 열면 대신 뜨는 화면. 뒤로가기로 우회 불가. */
class BlockedScreenActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocked_screen)

        val pkg = intent.getStringExtra("pkg") ?: "차단된 앱"
        val name = intent.getStringExtra("scheduleName") ?: "차단 일정"
        val type = intent.getStringExtra("scheduleType") ?: ""

        findViewById<TextView>(R.id.tvTitle).text = "⛔ 지금은 사용할 수 없어요"
        findViewById<TextView>(R.id.tvDesc).text =
            "「$name」 일정에 의해 차단됨\n($pkg)\n\n${typeDesc(type)}\n\n홈으로 돌아가 다른 일을 해보세요!"

        findViewById<Button>(R.id.btnHome).setOnClickListener {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(home)
            finish()
        }

        // 뒤로가기 눌러도 차단 앱으로 못 돌아가게 홈으로
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val home = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(home)
                finish()
            }
        })
    }

    private fun typeDesc(t: String) = when (t) {
        "DAILY_LIMIT" -> "오늘 허용 시간을 다 썼어요. 내일 다시 사용할 수 있습니다."
        "TIME_WINDOW" -> "설정한 차단 시간대입니다."
        "ALWAYS" -> "항상 차단으로 설정되어 있습니다."
        "QUICK" -> "즉시 차단(집중 세션) 중입니다."
        else -> ""
    }
}
