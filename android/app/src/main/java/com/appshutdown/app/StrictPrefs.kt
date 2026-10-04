package com.appshutdown.app

import android.content.Context

/** 엄격모드: 켜지면 일정 삭제 불가. 끄는 방법은 10분 기다리기뿐. */
object StrictPrefs {
    private const val P = "strict_mode"
    private const val KEY_ON = "on"
    private const val KEY_REQ = "unlock_req_at"
    const val WAIT_MS = 10 * 60 * 1000L

    private fun p(ctx: Context) =
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE)

    /** 잠금 상태. 10분 경과 시 자동 해제 후 false */
    fun isLocked(ctx: Context): Boolean {
        if (!p(ctx).getBoolean(KEY_ON, false)) return false
        val req = p(ctx).getLong(KEY_REQ, 0)
        if (req > 0 && System.currentTimeMillis() - req >= WAIT_MS) {
            p(ctx).edit().putBoolean(KEY_ON, false).putLong(KEY_REQ, 0).apply()
            return false
        }
        return true
    }

    fun hasPendingUnlock(ctx: Context): Boolean =
        p(ctx).getBoolean(KEY_ON, false) && p(ctx).getLong(KEY_REQ, 0) > 0

    fun remainingSec(ctx: Context): Long {
        val req = p(ctx).getLong(KEY_REQ, 0)
        if (req <= 0) return 0
        return ((WAIT_MS - (System.currentTimeMillis() - req)) / 1000).coerceAtLeast(0)
    }

    fun setOn(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_ON, on).putLong(KEY_REQ, 0).apply()
    }

    fun requestUnlock(ctx: Context) {
        p(ctx).edit().putLong(KEY_REQ, System.currentTimeMillis()).apply()
    }
}
