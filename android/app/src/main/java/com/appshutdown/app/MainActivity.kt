package com.appshutdown.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
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

        adapter = ScheduleAdapter(
            onToggle = { s -> toggle(s) },
            onEdit = { s ->
                startActivity(Intent(this, AddEditScheduleActivity::class.java).apply {
                    putExtra("editId", s.id)
                })
            },
            onRequestDelete = { s -> requestDelete(s, tvStatus) },
            onConfirmDelete = { s -> confirmDelete(s, tvStatus) },
            onCancelDelete = { s -> cancelDelete(s, tvStatus) }
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        btnAdd.setOnClickListener {
            startActivity(Intent(this, AddEditScheduleActivity::class.java))
        }
        btnRefresh.setOnClickListener { load(tvStatus) }
        btnPerm.setOnClickListener { openPermissions() }
        btnLogout.setOnClickListener {
            AuthManager.logout(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        BlockMonitorService.start(this)
        load(tvStatus)
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView?>(R.id.tvStatus)?.let { load(it) }
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
                    tvStatus.text = "일정 ${schedules.size}개 | 삭제는 요청 후 10분 뒤 확정"
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

    private fun toggle(s: Schedule) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { ApiClient.get(this@MainActivity).toggleSchedule(s.id) }
                findViewById<TextView>(R.id.tvStatus)?.let { load(it) }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestDelete(s: Schedule, tvStatus: TextView) {
        if (s.isPendingDelete) return
        AlertDialog.Builder(this)
            .setTitle("삭제 요청?")
            .setMessage("「${s.name}」 삭제 요청 시 10분 뒤에야 완전히 사라집니다.\n10분 안에 취소할 수 있습니다.\n\n(AppBlock Strict Mode의 쿨다운과 같은 원리)")
            .setPositiveButton("삭제 요청(10분 대기 시작)") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val res = withContext(Dispatchers.IO) {
                            ApiClient.get(this@MainActivity).requestDelete(s.id)
                        }
                        if (res.isSuccessful) load(tvStatus)
                        else Toast.makeText(this@MainActivity, "실패", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmDelete(s: Schedule, tvStatus: TextView) {
        if (!s.canConfirmDelete) {
            val m = (s.deleteRemainingSec / 60).toInt()
            val sec = (s.deleteRemainingSec % 60).toInt()
            Toast.makeText(this, "아직 ${m}분 ${sec}초 남음", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    ApiClient.get(this@MainActivity).confirmDelete(s.id)
                }
                if (res.isSuccessful) {
                    Toast.makeText(this@MainActivity, "삭제됨", Toast.LENGTH_SHORT).show()
                    load(tvStatus)
                } else {
                    Toast.makeText(this@MainActivity, "아직 시간 안 됨", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cancelDelete(s: Schedule, tvStatus: TextView) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { ApiClient.get(this@MainActivity).cancelDelete(s.id) }
                load(tvStatus)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- 리스트 어댑터 ----------
    class ScheduleAdapter(
        val onToggle: (Schedule) -> Unit,
        val onEdit: (Schedule) -> Unit,
        val onRequestDelete: (Schedule) -> Unit,
        val onConfirmDelete: (Schedule) -> Unit,
        val onCancelDelete: (Schedule) -> Unit
    ) : RecyclerView.Adapter<ScheduleAdapter.VH>() {
        private var items: List<Schedule> = emptyList()
        fun submit(l: List<Schedule>) { items = l; notifyDataSetChanged() }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
            val tvDetail: TextView = v.findViewById(R.id.tvDetail)
            val btnToggle: Button = v.findViewById(R.id.btnToggle)
            val btnEdit: Button = v.findViewById(R.id.btnEdit)
            val btnDelete: Button = v.findViewById(R.id.btnDelete)
            val tvPending: TextView = v.findViewById(R.id.tvPending)
            val btnConfirm: Button = v.findViewById(R.id.btnConfirmDelete)
            val btnCancel: Button = v.findViewById(R.id.btnCancelDelete)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_schedule, p, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = items[pos]
            h.tvName.text = "${if (s.isActive) "🟢" else "⚪"} ${s.name}"
            h.tvDetail.text = describe(s)
            h.btnToggle.text = if (s.isActive) "끄기" else "켜기"
            h.btnToggle.setOnClickListener { onToggle(s) }
            h.btnEdit.setOnClickListener { onEdit(s) }
            if (s.isPendingDelete) {
                h.btnDelete.visibility = View.GONE
                h.tvPending.visibility = View.VISIBLE
                h.btnConfirm.visibility = View.VISIBLE
                h.btnCancel.visibility = View.VISIBLE
                if (s.canConfirmDelete) {
                    h.tvPending.text = "⏳ 10분 경과! 확정 가능"
                    h.btnConfirm.isEnabled = true
                } else {
                    val m = (s.deleteRemainingSec / 60).toInt()
                    val sec = (s.deleteRemainingSec % 60).toInt()
                    h.tvPending.text = "⏳ 삭제 대기 중... ${m}분 ${sec}초 남음 (새로고침)"
                    h.btnConfirm.isEnabled = false
                }
                h.btnConfirm.setOnClickListener { onConfirmDelete(s) }
                h.btnCancel.setOnClickListener { onCancelDelete(s) }
            } else {
                h.btnDelete.visibility = View.VISIBLE
                h.tvPending.visibility = View.GONE
                h.btnConfirm.visibility = View.GONE
                h.btnCancel.visibility = View.GONE
                h.btnDelete.setOnClickListener { onRequestDelete(s) }
            }
        }

        private fun describe(s: Schedule): String {
            val apps = if (s.blockedApps.isEmpty()) "(앱 미지정)" else s.blockedApps.take(3).joinToString(", ") +
                if (s.blockedApps.size > 3) " 외 ${s.blockedApps.size - 3}개" else ""
            val mode = if (s.allowlistMode) "[허용목록외 전부차단] " else ""
            return when (s.type) {
                "DAILY_LIMIT" -> "$mode하루 ${s.dailyLimitMinutes}분 쓰면 차단 | $apps"
                "TIME_WINDOW" -> {
                    val d = if (s.days.isEmpty()) "매일" else "요일:" + s.days.sorted().joinToString(",")
                    "$mode${s.startTime}~${s.endTime} ($d) | $apps"
                }
                "ALWAYS" -> "${mode}항상 차단 | $apps"
                "QUICK" -> "${mode}즉시 차단(수동 OFF까지) | $apps"
                else -> apps
            }
        }
    }
}
