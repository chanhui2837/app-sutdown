package com.appshutdown.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddEditScheduleActivity : AppCompatActivity() {

    private var editId: String? = null
    // 일정 추가 시 종류가 정해져서 넘어옴 (TIME_WINDOW / DAILY_LIMIT)
    private var fixedType: String? = null

    // 차단 앱: 직접 입력 없음, 선택기로만 구성 (패키지 + 표시 이름)
    private val pickedPkgs = mutableListOf<String>()
    private val pickedLabels = mutableMapOf<String, String>()

    // 30분 간격 드롭다운
    private val times: List<String> =
        (0 until 24).flatMap { h -> listOf("%02d:00".format(h), "%02d:30".format(h)) }

    private lateinit var dayBoxes: List<CheckBox>
    private lateinit var cbAll: CheckBox
    private var bulkCheck = false

    private val pickApps = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val pkgs = res.data?.getStringArrayListExtra("picked") ?: return@registerForActivityResult
            val labels = res.data?.getStringArrayListExtra("pickedLabels") ?: arrayListOf()
            pickedPkgs.clear()
            pickedLabels.clear()
            pkgs.forEachIndexed { i, p ->
                if (!pickedPkgs.contains(p)) pickedPkgs.add(p)
                pickedLabels[p] = labels.getOrNull(i) ?: p
            }
            refreshPicked()
        }
    }

    private fun refreshPicked() {
        val tv: TextView = findViewById(R.id.tvPickedApps)
        tv.text = if (pickedPkgs.isEmpty()) "(선택된 앱 없음)"
        else pickedPkgs.joinToString("\n") { pickedLabels[it] ?: it }
    }

    private fun updateFieldVisibility(type: String) {
        val rowTime: View = findViewById(R.id.rowTime)
        val rowDays: View = findViewById(R.id.rowDays)
        val etLimit: EditText = findViewById(R.id.etLimit)
        when (type) {
            "TIME_WINDOW" -> {
                rowTime.visibility = View.VISIBLE
                rowDays.visibility = View.VISIBLE
                etLimit.visibility = View.GONE
            }
            "DAILY_LIMIT" -> {
                rowTime.visibility = View.GONE
                rowDays.visibility = View.GONE
                etLimit.visibility = View.VISIBLE
            }
            else -> {
                rowTime.visibility = View.VISIBLE
                rowDays.visibility = View.VISIBLE
                etLimit.visibility = View.VISIBLE
            }
        }
    }

    private fun resolveLabel(pkg: String): String {
        return try {
            val pm: PackageManager = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))?.toString() ?: pkg
        } catch (_: Exception) {
            pkg
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_edit_schedule)

        editId = intent.getStringExtra("editId")
        fixedType = intent.getStringExtra("presetType")

        val etName: EditText = findViewById(R.id.etName)
        val spinnerType: Spinner = findViewById(R.id.spinnerType)
        val tvTypeFixed: TextView = findViewById(R.id.tvTypeFixed)
        val etLimit: EditText = findViewById(R.id.etLimit)
        val spinnerStart: Spinner = findViewById(R.id.spinnerStart)
        val spinnerEnd: Spinner = findViewById(R.id.spinnerEnd)
        val switchActive: Switch = findViewById(R.id.switchActive)
        val switchAllow: Switch = findViewById(R.id.switchAllowlist)
        val btnSave: Button = findViewById(R.id.btnSave)
        val tvError: TextView = findViewById(R.id.tvError)
        val btnPickApps: Button = findViewById(R.id.btnPickApps)

        val timeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, times)
        spinnerStart.adapter = timeAdapter
        spinnerEnd.adapter = timeAdapter
        spinnerStart.setSelection(times.indexOf("22:00").coerceAtLeast(0))
        spinnerEnd.setSelection(times.indexOf("07:00").coerceAtLeast(0))

        // 요일 체크박스 (매일 + 월~일)
        cbAll = findViewById(R.id.cbAll)
        dayBoxes = listOf(
            findViewById(R.id.cbD1), findViewById(R.id.cbD2), findViewById(R.id.cbD3),
            findViewById(R.id.cbD4), findViewById(R.id.cbD5), findViewById(R.id.cbD6),
            findViewById(R.id.cbD7)
        )
        cbAll.setOnCheckedChangeListener { _, checked ->
            if (bulkCheck) return@setOnCheckedChangeListener
            bulkCheck = true
            dayBoxes.forEach { it.isChecked = checked }
            bulkCheck = false
        }
        dayBoxes.forEach { box ->
            box.setOnCheckedChangeListener { _, checked ->
                if (bulkCheck) return@setOnCheckedChangeListener
                if (!checked) {
                    bulkCheck = true
                    cbAll.isChecked = false
                    bulkCheck = false
                } else if (dayBoxes.all { it.isChecked }) {
                    bulkCheck = true
                    cbAll.isChecked = true
                    bulkCheck = false
                }
            }
        }
        // 기본: 매일
        bulkCheck = true
        cbAll.isChecked = true
        dayBoxes.forEach { it.isChecked = true }
        bulkCheck = false

        btnPickApps.setOnClickListener {
            pickApps.launch(
                Intent(this, AppPickerActivity::class.java)
                    .putStringArrayListExtra("selected", ArrayList(pickedPkgs))
            )
        }
        refreshPicked()

        val types = arrayOf("TIME_WINDOW", "DAILY_LIMIT", "ALWAYS", "QUICK")
        val labels = arrayOf("시간대 차단", "사용량 차단 (합산)", "항상 차단", "즉시 차단(Quick Block)")
        spinnerType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        // 추가 모드(종류 선택 후 진입): 종류 고정 + 해당 종류에 맞는 입력칸만 표시
        if (fixedType != null) {
            spinnerType.visibility = View.GONE
            tvTypeFixed.visibility = View.VISIBLE
            tvTypeFixed.text = if (fixedType == "TIME_WINDOW") "🕙 시간대 차단" else "⏳ 사용량 차단"
            title = if (fixedType == "TIME_WINDOW") "시간대 차단 추가" else "사용량 차단 추가"
            updateFieldVisibility(fixedType!!)
        } else {
            // 수정 모드: 종류 변경 시 입력칸 전환
            spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    updateFieldVisibility(types[pos])
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

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
                        updateFieldVisibility(s.type)
                        pickedPkgs.clear()
                        pickedPkgs.addAll(s.blockedApps)
                        pickedLabels.clear()
                        s.blockedApps.forEach { pickedLabels[it] = resolveLabel(it) }
                        refreshPicked()
                        etLimit.setText(s.dailyLimitMinutes.toString())
                        val si = times.indexOf(s.startTime)
                        if (si >= 0) spinnerStart.setSelection(si)
                        val ei = times.indexOf(s.endTime)
                        if (ei >= 0) spinnerEnd.setSelection(ei)
                        bulkCheck = true
                        if (s.days.isEmpty()) {
                            cbAll.isChecked = true
                            dayBoxes.forEach { it.isChecked = true }
                        } else {
                            cbAll.isChecked = false
                            dayBoxes.forEachIndexed { i, b -> b.isChecked = s.days.contains(i + 1) }
                            if (dayBoxes.all { it.isChecked }) cbAll.isChecked = true
                        }
                        bulkCheck = false
                        switchActive.isChecked = s.isActive
                        switchAllow.isChecked = s.allowlistMode
                    }
                } catch (_: Exception) {}
            }
        }

        btnSave.setOnClickListener {
            val type = fixedType ?: types[spinnerType.selectedItemPosition]
            if (pickedPkgs.isEmpty()) {
                tvError.text = "차단할 앱 1개 이상 필요 ('앱 선택' 버튼 이용)"
                return@setOnClickListener
            }
            // 매일(전체 체크)이면 빈 배열 = 매일
            val checkedDays = dayBoxes.mapIndexedNotNull { i, b -> if (b.isChecked) i + 1 else null }
            val days = if (checkedDays.size == 7) emptyList() else checkedDays

            val body = mutableMapOf<String, Any>(
                "name" to etName.text.toString().trim().ifEmpty { "차단 일정" },
                "type" to type,
                "blockedApps" to pickedPkgs.toList(),
                "dailyLimitMinutes" to (etLimit.text.toString().toIntOrNull() ?: 60),
                "startTime" to times[spinnerStart.selectedItemPosition],
                "endTime" to times[spinnerEnd.selectedItemPosition],
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
                        Toast.makeText(this@AddEditScheduleActivity, "저장됨", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        tvError.text = "저장 실패 (${res.code()})"
                    }
                } catch (e: Exception) {
                    tvError.text = "서버 접속 실패: ${e.message}"
                }
            }
        }
    }
}
