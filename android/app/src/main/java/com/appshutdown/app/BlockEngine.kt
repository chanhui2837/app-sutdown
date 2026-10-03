package com.appshutdown.app

import android.app.usage.UsageStatsManager
import android.content.Context
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * AppBlock 클론의 차단 판정 로직 (서버/클라이언트 공용 규칙).
 * - DAILY_LIMIT: 해당 일정 blockedApps의 오늘 누적 사용량이 dailyLimitMinutes를 넘으면 차단
 * - TIME_WINDOW: 요일 + 시간대(자정 넘김 지원, 예 22:00~07:00)에 해당하면 차단
 * - ALWAYS / QUICK: isActive면 무조건 차단
 * - allowlistMode=true면 blockedApps는 허용 목록이 되고, 그 외 전부 차단
 */
object BlockEngine {
    private val TF: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun todayKey(): String = LocalDate.now().format(DateTimeFormatter.ISO_DATE)

    fun isScheduleActiveNow(s: Schedule, now: LocalTime = LocalTime.now(), dayOfWeek: Int = LocalDate.now().dayOfWeek.value): Boolean {
        if (!s.isActive) return false
        // 삭제 대기 중이어도 쿨다운이 끝나기 전에는 차단 유지 (우회 방지)
        return when (s.type) {
            "ALWAYS", "QUICK" -> true
            "TIME_WINDOW" -> matchTimeWindow(s, now, dayOfWeek)
            "DAILY_LIMIT" -> true // 시간 조건 없음. 실제 차단은 사용량으로 판단
            else -> false
        }
    }

    private fun matchTimeWindow(s: Schedule, now: LocalTime, dayOfWeek: Int): Boolean {
        // days가 비어있으면 매일
        if (s.days.isNotEmpty() && !s.days.contains(dayOfWeek)) return false
        val start = runCatching { LocalTime.parse(s.startTime, TF) }.getOrDefault(LocalTime.of(22, 0))
        val end = runCatching { LocalTime.parse(s.endTime, TF) }.getOrDefault(LocalTime.of(7, 0))
        return if (start <= end) {
            !now.isBefore(start) && now.isBefore(end)
        } else {
            // 자정 넘김 (예: 22:00~07:00)
            !now.isBefore(start) || now.isBefore(end)
        }
    }

    /** 오늘이 dayKey와 다르면 사용량 초기화용으로 호출 */
    fun getTodayUsageMinutes(ctx: Context, pkg: String): Int {
        val p = ctx.getSharedPreferences("usage", Context.MODE_PRIVATE)
        val key = "${todayKey()}|$pkg"
        // 날짜 바뀌면 오래된 키 정리 (간단 처리)
        return p.getInt(key, 0)
    }

    fun addUsageMinutes(ctx: Context, pkg: String, minutes: Int) {
        if (minutes <= 0) return
        val p = ctx.getSharedPreferences("usage", Context.MODE_PRIVATE)
        val key = "${todayKey()}|$pkg"
        p.edit().putInt(key, p.getInt(key, 0) + minutes).apply()
    }

    /**
     * @param schedules 서버에서 동기화된 전체 일정
     * @param foregroundPkg 현재 포그라운드 패키지
     * @return 차단해야 하면 그 이유가 된 Schedule, 아니면 null
     */
    fun findBlockingSchedule(
        ctx: Context,
        schedules: List<Schedule>,
        foregroundPkg: String
    ): Schedule? {
        if (foregroundPkg == ctx.packageName) return null // 자기 자신은 차단 안 함
        for (s in schedules) {
            if (!isScheduleActiveNow(s)) continue
            when (s.type) {
                "DAILY_LIMIT" -> {
                    if (!s.blockedApps.contains(foregroundPkg)) continue
                    val used = getTodayUsageMinutes(ctx, foregroundPkg)
                    if (used >= s.dailyLimitMinutes) return s
                }
                "TIME_WINDOW", "ALWAYS", "QUICK" -> {
                    if (s.allowlistMode) {
                        // 허용 목록에 없으면 전부 차단 (단, 런처/전화/문자는 기본 허용)
                        val defaultAllowed = setOf(
                            "com.android.launcher", "com.google.android.apps.nexuslauncher",
                            "com.android.dialer", "com.android.contacts",
                            "com.google.android.dialer", "com.android.mms"
                        )
                        if (!s.blockedApps.contains(foregroundPkg) &&
                            !defaultAllowed.contains(foregroundPkg)
                        ) return s
                    } else {
                        if (s.blockedApps.contains(foregroundPkg)) return s
                    }
                }
            }
        }
        return null
    }

    /** UsageStatsManager로 오늘 앱별 사용 분 집계 (DAILY_LIMIT용, 권한 필요) */
    fun queryUsageMinutes(ctx: Context, pkg: String): Int {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val start = now - 24 * 60 * 60 * 1000L
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
            val total = stats.filter { it.packageName == pkg }.sumOf { it.totalTimeInForeground }
            (total / 60000L).toInt()
        } catch (e: Exception) {
            getTodayUsageMinutes(ctx, pkg)
        }
    }
}
