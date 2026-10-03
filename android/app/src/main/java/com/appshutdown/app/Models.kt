package com.appshutdown.app

import com.google.gson.annotations.SerializedName

data class User(
    val username: String,
    val displayName: String
)

// 서버 Schedule과 1:1 매칭
data class Schedule(
    @SerializedName("_id") val id: String,
    val name: String,
    // DAILY_LIMIT | TIME_WINDOW | ALWAYS | QUICK
    val type: String,
    val blockedApps: List<String> = emptyList(),
    val allowlistMode: Boolean = false,
    val dailyLimitMinutes: Int = 60,
    val startTime: String = "22:00",
    val endTime: String = "07:00",
    val days: List<Int> = emptyList(), // 1(월)~7(일), 빈 배열=매일
    val isActive: Boolean = true,
    val strictMode: Boolean = true,
    val pendingDeleteAt: String? = null,
    val deleteRemainingSec: Long = 0,
    val deleteCooldownSec: Long = 600
) {
    val isPendingDelete: Boolean get() = pendingDeleteAt != null
    val canConfirmDelete: Boolean get() = isPendingDelete && deleteRemainingSec <= 0
}

// ---- API DTO ----
data class AuthReq(val username: String, val password: String, val displayName: String? = null)
data class AuthRes(val token: String, val user: User)
data class SchedulesRes(val schedules: List<Schedule>)
data class ScheduleRes(val schedule: Schedule, val message: String? = null)
data class UsageItem(val packageName: String, val minutes: Int)
data class UsageReportReq(val dateKey: String, val usages: List<UsageItem>)
data class OkRes(val ok: Boolean = true)
