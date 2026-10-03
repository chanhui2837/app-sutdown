package com.appshutdown.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 포그라운드 앱 변경을 감지해 차단 대상이면 차단 화면을 띄운다.
 * AppBlock의 "스케줄 + Strict Mode" 개념을 단순화한 구현.
 */
class BlockAccessibilityService : AccessibilityService() {

    private var lastPkg: String? = null
    private var lastTime = 0L

    override fun onServiceConnected() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return

        // 너무 잦은 이벤트 스로틀 (500ms)
        val now = System.currentTimeMillis()
        if (pkg == lastPkg && now - lastTime < 500) return
        lastPkg = pkg
        lastTime = now

        val schedules = loadCachedSchedules()
        val hit = BlockEngine.findBlockingSchedule(this, schedules, pkg) ?: return

        // 차단 화면 실행
        val i = Intent(this, BlockedScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("pkg", pkg)
            putExtra("scheduleName", hit.name)
            putExtra("scheduleType", hit.type)
        }
        startActivity(i)
    }

    override fun onInterrupt() {}

    private fun loadCachedSchedules(): List<Schedule> {
        return try {
            val json = getSharedPreferences("cache", MODE_PRIVATE)
                .getString("schedules", "[]") ?: "[]"
            val type = object : TypeToken<List<Schedule>>() {}.type
            Gson().fromJson<List<Schedule>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        fun cacheSchedules(ctx: android.content.Context, list: List<Schedule>) {
            ctx.getSharedPreferences("cache", MODE_PRIVATE).edit()
                .putString("schedules", Gson().toJson(list))
                .apply()
        }
    }
}
