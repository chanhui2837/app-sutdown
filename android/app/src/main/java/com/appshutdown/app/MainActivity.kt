package com.appshutdown.app

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ScheduleAdapter
    private var schedules: List<Schedule> = emptyList()
    private val strictHandler = Handler(Looper.getMainLooper())
    private val strictTick = object : Runnable {
        override fun run() {
            refreshStrict()
            strictHandler.postDelayed(this, 1000)
        }
    }

    // Quick Block: 앱 고르기 → 즉시 차단 일정 생성
    private val quickPick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val picked = res.data?.getStringArrayListExtra("picked") ?: return@registerForActivityResult
            if (picked.isEmpty()) {
                Toast.makeText(this, "앱을 1개 이상 고르세요", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            createQuickBlock(picked)
        }
    }

    private fun createQuickBlock(pkgs: List<String>) {
        val tvStatus: TextView = findViewById(R.id.tvStatus)
        tvStatus.text = "즉시 차단 시작 중..."
        lifecycleScope.launch {
            try {
                val body = mapOf<String, Any>(
                    "name" to "⚡ 즉시 차단",
                    "type" to "QUICK",
                    "blockedApps" to pkgs,
                    "isActive" to true,
                    "strictMode" to true
                )
                val res = withContext(Dispatchers.IO) {
                    ApiClient.get(this@MainActivity).createSchedule(body)
                }
                if (res.isSuccessful) {
                    Toast.makeText(this@MainActivity, "즉시 차단 시작!", Toast.LENGTH_SHORT).show()
                    load(tvStatus)
                } else {
                    tvStatus.text = "즉시 차단 실패 (${res.code()})"
                }
            } catch (e: Exception) {
                tvStatus.text = "서버 접속 실패: ${e.message}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!AuthManager.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val rv: RecyclerView = findViewById(R.id.rvSchedules)
        val tvStatus: TextView = findViewById(R.id.tvStatus)
        val btnAdd: Button = findViewById(R.id.btnAdd)
        val btnRefresh: Button = findViewById(R.id.btnRefresh)
        val btnPerm: Button = findViewById(R.id.btnPerm)
        val btnLogout: Button = findViewById(R.id.btnLogout)
        val btnQuick: Button = findViewById(R.id.btnQuick)
        val btnStrict: Button = findViewById(R.id.btnStrict)

        adapter = ScheduleAdapter(
            onEdit = { s ->
                startActivity(Intent(this, AddEditScheduleActivity::class.java).apply {
                    putExtra("editId", s.id)
                })
            },
            onDelete = { s -> deleteAsk(s) }
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        btnAdd.setOnClickListener { showTypeChoice() }
        btnRefresh.setOnClickListener { load(tvStatus) }
        btnQuick.setOnClickListener {
            quickPick.launch(
                Intent(this, AppPickerActivity::class.java)
                    .putStringArrayListExtra("selected", ArrayList(emptyList<String>()))
            )
        }
        btnStrict.setOnClickListener { onStrictClick() }
        btnPerm.setOnClickListener { openPermissions() }
        btnLogout.setOnClickListener {
            AuthManager.logout(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        BlockMonitorService.start(this)
        load(tvStatus)
        refreshStrict()
        strictHandler.postDelayed(strictTick, 1000)
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView?>(R.id.tvStatus)?.let { load(it) }
        refreshStrict()
    }

    override fun onDestroy() {
        strictHandler.removeCallbacks(strictTick)
        super.onDestroy()
    }

    /** 일정 종류 선택: 예쁜 버튼 다이얼로그 */
    private fun showTypeChoice() {
        val d = Dialog(this)
        d.setContentView(R.layout.dialog_type_choice)
        d.findViewById<Button>(R.id.btnTypeTime).setOnClickListener {
            d.dismiss()
            startActivity(Intent(this, AddEditScheduleActivity::class.java).apply {
                putExtra("presetType", "TIME_WINDOW")
            })
        }
        d.findViewById<Button>(R.id.btnTypeUsage).setOnClickListener {
            d.dismiss()
            startActivity(Intent(this, AddEditScheduleActivity::class.java).apply {
                putExtra("presetType", "DAILY_LIMIT")
            })
        }
        d.show()
    }

    // ---------- 엄격모드 ----------
    private fun refreshStrict() {
        val btnStrict: Button = findViewById(R.id.btnStrict) ?: return
        when {
            !StrictPrefs.isLocked(this) -> {
                btnStrict.text = "🔓 엄격모드 켜기"
                btnStrict.isEnabled = true
            }
            StrictPrefs.hasPendingUnlock(this) -> {
                val sec = StrictPrefs.remainingSec(this)
                btnStrict.text = "⏳ 엄격 해제까지 ${sec / 60}분 ${sec % 60}초"
                btnStrict.isEnabled = false
            }
            else -> {
                btnStrict.text = "🔒 엄격모드 중 (끄기)"
                btnStrict.isEnabled = true
            }
        }
    }

    private fun onStrictClick() {
        if (!StrictPrefs.isLocked(this)) {
            AlertDialog.Builder(this)
                .setTitle("🔒 엄격모드 켜기")
                .setMessage("엄격모드가 켜지면 일정 삭제가 완전히 막힙니다.\n끄려면 10분을 기다려야 합니다.\n\n켜겠습니까?")
                .setPositiveButton("확인") { _, _ ->
                    StrictPrefs.setOn(this, true)
                    refreshStrict()
                    Toast.makeText(this, "엄격모드 ON: 일정 삭제 불가", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
        } else if (!StrictPrefs.hasPendingUnlock(this)) {
            AlertDialog.Builder(this)
                .setTitle("엄격모드 끄기")
                .setMessage("끄는 방법은 10분 기다리기 하나뿐입니다.")
                .setPositiveButton("⏳ 10분 기다리기") { _, _ ->
                    StrictPrefs.requestUnlock(this)
                    refreshStrict()
                    Toast.makeText(this, "10분 뒤에 엄격모드가 풀립니다", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    // ---------- 삭제 (즉시, 엄격모드 중 불가) ----------
    private fun deleteAsk(s: Schedule) {
        if (StrictPrefs.isLocked(this)) {
            Toast.makeText(this, "🔒 엄격모드 중에는 삭제할 수 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("삭제?")
            .setMessage("「${s.name}」 일정을 삭제합니다.\n(바로 삭제됩니다)")
            .setPositiveButton("삭제") { _, _ -> doDelete(s) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun doDelete(s: Schedule) {
        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    ApiClient.get(this@MainActivity).deleteSchedule(s.id)
                }
                if (res.isSuccessful) {
                    Toast.makeText(this@MainActivity, "삭제됨", Toast.LENGTH_SHORT).show()
                    findViewById<TextView>(R.id.tvStatus)?.let { load(it) }
                } else {
                    Toast.makeText(this@MainActivity, "삭제 실패 (${res.code()})", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openPermissions() {
        AlertDialog.Builder(this)
            .setTitle("필수 권한 2개")
            .setMessage("1) 접근성: 차단 감지용\n2) 사용 정보 접근: 하루 사용시간 측정용\n\n확인을 누르면 설정 화면으로 이동합니다.")
            .setPositiveButton("접근성 설정") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("사용정보 설정") { _, _ ->
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            .setNeutralButton("닫기", null)
            .show()
    }

    private fun load(tvStatus: TextView) {
        tvStatus.text = "불러오는 중..."
        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) { ApiClient.get(this@MainActivity).getSchedules() }
                if (res.isSuccessful) {
                    schedules = res.body()?.schedules ?: emptyList()
                    BlockAccessibilityService.cacheSchedules(this@MainActivity, schedules)
                    adapter.submit(schedules)
                    tvStatus.text = "일정 ${schedules.size}개" +
                        if (StrictPrefs.isLocked(this@MainActivity)) " | 🔒 엄격모드 중" else ""
                } else if (res.code() == 401) {
                    tvStatus.text = "로그인 만료 - 다시 로그인"
                    AuthManager.logout(this@MainActivity)
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                    finish()
                } else {
                    tvStatus.text = "불러오기 실패 (${res.code()})"
                }
            } catch (e: Exception) {
                tvStatus.text = "서버 접속 실패: ${e.message}"
            }
        }
    }

    // ---------- 리스트 어댑터 (수정/삭제만, 끄기 없음) ----------
    class ScheduleAdapter(
        val onEdit: (Schedule) -> Unit,
        val onDelete: (Schedule) -> Unit
    ) : RecyclerView.Adapter<ScheduleAdapter.VH>() {
        private var items: List<Schedule> = emptyList()
        fun submit(l: List<Schedule>) { items = l; notifyDataSetChanged() }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
            val tvDetail: TextView = v.findViewById(R.id.tvDetail)
            val tvGauge: TextView = v.findViewById(R.id.tvGauge)
            val pbGauge: ProgressBar = v.findViewById(R.id.pbGauge)
            val btnEdit: Button = v.findViewById(R.id.btnEdit)
            val btnDelete: Button = v.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_schedule, p, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = items[pos]
            h.tvName.text = "📌 ${s.name}"
            h.tvDetail.text = describe(s)
            // 게이지: 시간대는 시작까지/종료까지, 사용량은 남은 분
            val usedSec = BlockEngine.usedSecondsFor(h.itemView.context, s)
            val g = BlockEngine.gaugeFor(s, usedSec)
            h.tvGauge.text = g.text
            h.pbGauge.progress = g.progress
            h.btnEdit.setOnClickListener { onEdit(s) }
            h.btnDelete.setOnClickListener { onDelete(s) }
        }

        private fun describe(s: Schedule): String {
            val apps = if (s.blockedApps.isEmpty()) "(앱 미지정)" else s.blockedApps.take(3).joinToString(", ") +
                if (s.blockedApps.size > 3) " 외 ${s.blockedApps.size - 3}개" else ""
            val mode = if (s.allowlistMode) "[허용목록외 전부차단] " else ""
            return when (s.type) {
                "DAILY_LIMIT" -> "선택 앱 합산 하루 ${s.dailyLimitMinutes}분 쓰면 전부 차단 | $apps"
                "TIME_WINDOW" -> {
                    val d = if (s.days.isEmpty()) "매일" else "요일:" + s.days.sorted().joinToString(",")
                    "$mode${s.startTime}~${s.endTime} ($d) | $apps"
                }
                "ALWAYS" -> "${mode}항상 차단 | $apps"
                "QUICK" -> "${mode}즉시 차단 | $apps"
                else -> apps
            }
        }
    }
}
