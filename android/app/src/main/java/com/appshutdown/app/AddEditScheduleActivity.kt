package com.appshutdown.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddEditScheduleActivity : AppCompatActivity() {

    private var editId: String? = null

    private val pickApps = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val picked = res.data?.getStringArrayListExtra("picked") ?: return@registerForActivityResult
            val etApps: EditText = findViewById(R.id.etApps)
            val cur = etApps.text.toString()
                .split(",", "\n", " ", ";")
                .map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
            cur.addAll(picked)
            etApps.setText(cur.joinToString("\n"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_edit_schedule)

        editId = intent.getStringExtra("editId")

        val etName: EditText = findViewById(R.id.etName)
        val spinnerType: Spinner = findViewById(R.id.spinnerType)
        val etApps: EditText = findViewById(R.id.etApps)
        val etLimit: EditText = findViewById(R.id.etLimit)
        val etStart: EditText = findViewById(R.id.etStart)
        val etEnd: EditText = findViewById(R.id.etEnd)
        val etDays: EditText = findViewById(R.id.etDays)
        val switchActive: Switch = findViewById(R.id.switchActive)
        val switchAllow: Switch = findViewById(R.id.switchAllowlist)
        val btnSave: Button = findViewById(R.id.btnSave)
        val btnPickApps: Button = findViewById(R.id.btnPickApps)
        btnPickApps.setOnClickListener {
            val cur = etApps.text.toString()
                .split(",", "\n", " ", ";")
                .map { it.trim() }.filter { it.isNotEmpty() }
            pickApps.launch(
                Intent(this, AppPickerActivity::class.java)
                    .putStringArrayListExtra("selected", ArrayList(cur))
            )
        }
        val tvError: TextView = findViewById(R.id.tvError)

        val types = arrayOf("TIME_WINDOW", "DAILY_LIMIT", "ALWAYS", "QUICK")
        val labels = arrayOf("시간대 차단 (예: 22:00~07:00)", "하루 사용량 (예: 60분 쓰면 차단)", "항상 차단", "즉시 차단(Quick Block)")
        spinnerType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        // 수정 모드면 기존 값 로드
        if (editId != null) {
            title = "일정 수정"
            lifecycleScope.launch {
                try {
                    val res = withContext(Dispatchers.IO) { ApiClient.get(this@AddEditScheduleActivity).getSchedules() }
                    val s = res.body()?.schedules?.find { it.id == editId }
                    if (s != null) {
                        etName.setText(s.name)
                        spinnerType.setSelection(types.indexOf(s.type).coerceAtLeast(0))
                        etApps.setText(s.blockedApps.joinToString("\n"))
                        etLimit.setText(s.dailyLimitMinutes.toString())
                        etStart.setText(s.startTime)
                        etEnd.setText(s.endTime)
                        etDays.setText(if (s.days.isEmpty()) "" else s.days.sorted().joinToString(","))
                        switchActive.isChecked = s.isActive
                        switchAllow.isChecked = s.allowlistMode
                    }
                } catch (_: Exception) {}
            }
        }

        btnSave.setOnClickListener {
            val type = types[spinnerType.selectedItemPosition]
            val apps = etApps.text.toString()
                .split(",", "\n", " ", ";")
                .map { it.trim() }.filter { it.isNotEmpty() }
            if (apps.isEmpty()) { tvError.text = "차단할 앱 패키지명 1개 이상 입력 (예: com.instagram.android)"; return@setOnClickListener }
            val days = etDays.text.toString().split(",")
                .mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.distinct()

            val body = mutableMapOf<String, Any>(
                "name" to etName.text.toString().trim().ifEmpty { "차단 일정" },
                "type" to type,
                "blockedApps" to apps,
                "dailyLimitMinutes" to (etLimit.text.toString().toIntOrNull() ?: 60),
                "startTime" to etStart.text.toString().trim().ifEmpty { "22:00" },
                "endTime" to etEnd.text.toString().trim().ifEmpty { "07:00" },
                "days" to days,
                "isActive" to switchActive.isChecked,
                "allowlistMode" to switchAllow.isChecked,
                "strictMode" to true
            )
            tvError.text = "저장 중..."
            lifecycleScope.launch {
                try {
                    val res = withContext(Dispatchers.IO) {
                        val api = ApiClient.get(this@AddEditScheduleActivity)
                        if (editId != null) api.updateSchedule(editId!!, body)
                        else api.createSchedule(body)
                    }
                    if (res.isSuccessful) {
                        res.body()?.schedule?.let {
                            // 로컬 캐시 즉시 갱신
                        }
                        Toast.makeText(this@AddEditScheduleActivity, "저장됨", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        tvError.text = "저장 실패 (${res.code()}) - 시간 형식 HH:MM 확인"
                    }
                } catch (e: Exception) {
                    tvError.text = "서버 접속 실패: ${e.message}"
                }
            }
        }
    }
}
