package com.appshutdown.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            // 로그인 기록이 있으면 감시 서비스 재시작
            if (AuthManager.isLoggedIn(context)) {
                BlockMonitorService.start(context)
            }
        }
    }
}
