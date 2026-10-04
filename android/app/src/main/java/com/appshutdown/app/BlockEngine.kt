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
    fun getTodayUsageSeconds(ctx: Context, pkg: String): Int {
        val p = ctx.getSharedPreferences("usage", Context.MODE_PRIVATE)
        val key = "sec|${todayKey()}|$pkg"
        return p.getInt(key, 0)
    }

    fun addUsageSeconds(ctx: Context, pkg: String, seconds: Int) {
        if (seconds <= 0) return
        val p = ctx.getSharedPreferences("usage", Context.MODE_PRIVATE)
        val key = "sec|${todayKey()}|$pkg"
        p.edit().putInt(key, p.getInt(key, 0) + seconds).apply()
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
                    // 합산 사용량: 일정에 속한 모든 앱의 오늘 사용량 합이
                    // dailyLimitMinutes를 넘으면 일정 전체 앱을 전부 차단
                    // (예: 60분 일정에 인스타+유튜브 → 합쳐서 60분 쓰면 둘 다 차단)
                    val totalSec = s.blockedApps.sumOf { getTodayUsageSeconds(ctx, it).toLong() }
                    if (totalSec >= s.dailyLimitMinutes * 60L) return s
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

    /** UsageStatsManager로 오늘 앱별 사용 초 집계 (DAILY_LIMIT용, 권한 필요) */
    fun queryUsageSeconds(ctx: Context, pkg: String): Int {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val start = now - 24 * 60 * 60 * 1000L
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
            val total = stats.filter { it.packageName == pkg }.sumOf { it.totalTimeInForeground }
            (total / 1000L).toInt()
        } catch (e: Exception) {
            getTodayUsageSeconds(ctx, pkg)
        }
    }

    // ---------- 게이지 표시용 ----------
    data class Gauge(val text: String, val progress: Int) // progress 0~100

    fun usedSecondsFor(ctx: Context, s: Schedule): Long =
        if (s.type == "DAILY_LIMIT") s.blockedApps.sumOf { getTodayUsageSeconds(ctx, it).toLong() }
        else 0L

    private fun fmtMin(mins: Int): String =
        if (mins < 60) "${mins}분" else "${mins / 60}시간 ${mins % 60}분"

    fun gaugeFor(
        s: Schedule,
        usedSec: Long,
        now: LocalTime = LocalTime.now(),
        dayOfWeek: Int = LocalDate.now().dayOfWeek.value
    ): Gauge {
        if (!s.isActive) return Gauge("꺼짐", 0)
        return when (s.type) {
            "DAILY_LIMIT" -> {
                val total = s.dailyLimitMinutes * 60L
                val remain = (total - usedSec).coerceAtLeast(0)
                val remainMin = ((remain + 59) / 60).toInt()
                val prog = if (total <= 0) 100 else (usedSec * 100 / total).coerceIn(0, 100).toInt()
                if (remain <= 0) Gauge("⛔ 오늘 할당량 소진 (차단 중)", 100)
                else Gauge("남은 ${remainMin}분 / ${s.dailyLimitMinutes}분", prog)
            }
            "TIME_WINDOW" -> {
                if (s.days.isNotEmpty() && !s.days.contains(dayOfWeek))
                    return Gauge("오늘은 off (요일 미포함)", 0)
                val start = runCatching { LocalTime.parse(s.startTime, TF) }.getOrDefault(LocalTime.of(22, 0))
                val end = runCatching { LocalTime.parse(s.endTime, TF) }.getOrDefault(LocalTime.of(7, 0))
                val sMin = start.hour * 60 + start.minute
                val eMin = end.hour * 60 + end.minute
                val nMin = now.hour * 60 + now.minute
                val winLen = (eMin - sMin + 1440) % 1440
                if (winLen == 0) return Gauge("시간 설정 확인", 0)
                val inWin = matchTimeWindow(s, now, dayOfWeek)
                if (inWin) {
                    val elapsed = (nMin - sMin + 1440) % 1440
                    val remain = winLen - elapsed
                    val prog = (elapsed * 100 / winLen).coerceIn(0, 100)
                    Gauge("⛔ 차단 중 (종료까지 ${fmtMin(remain)})", prog)
                } else {
                    val untilStart = (sMin - nMin + 1440) % 1440
                    val offLen = 1440 - winLen
                    val sinceEnd = (nMin - eMin + 1440) % 1440
                    val prog = if (offLen <= 0) 0 else (sinceEnd * 100 / offLen).coerceIn(0, 100)
                    Gauge("시작까지 ${fmtMin(untilStart)} 남음", prog)
                }
            }
            "ALWAYS", "QUICK" -> Gauge("⛔ 항상 차단 중", 100)
            else -> Gauge("", 0)
        }
    }

    /** 접근성 서비스·감시 서비스 공용: 서버 동기화된 일정 로컬 캐시 읽기 */
    fun loadCachedSchedules(ctx: Context): List<Schedule> {
        return try {
            val json = ctx.getSharedPreferences("cache", Context.MODE_PRIVATE)
                .getString("schedules", "[]") ?: "[]"
            val type = object : com.google.gson.reflect.TypeToken<List<Schedule>>() {}.type
            com.google.gson.Gson().fromJson<List<Schedule>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
