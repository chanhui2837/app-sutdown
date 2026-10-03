package com.appshutdown.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 부팅 후 + 앱 실행 시 차단 감시 포그라운드 서비스 시작용 */
class BlockMonitorService : Service() {
    override fun onCreate() {
        super.onCreate()
        startFg()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        // 주기 동기화: 서버 스케줄을 로컬 캐시에 저장 (로그인 상태일 때)
        Thread {
            try {
                val token = AuthManager.getToken(this)
                if (token != null) {
                    val res = kotlinx.coroutines.runBlocking {
                        withContext(Dispatchers.IO) {
                            runCatching { ApiClient.get(this@BlockMonitorService).getSchedules() }.getOrNull()
                        }
                    }
                    val list = res?.body()?.schedules
                    if (list != null) BlockAccessibilityService.cacheSchedules(this, list)
                }
            } catch (_: Exception) {}
        }.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startFg() {
        val chId = "block_monitor"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(chId, "차단 감시", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, chId)
            .setContentTitle("App Shutdown 감시 중")
            .setContentText("설정한 시간에는 차단이 자동으로 적용됩니다")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .build()
        startForeground(1, n)
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, BlockMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}

/** WorkManager 주기 동기화 Worker */
class SyncWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (AuthManager.getToken(applicationContext) == null) return@withContext Result.success()
            val res = ApiClient.get(applicationContext).getSchedules()
            if (res.isSuccessful) {
                res.body()?.schedules?.let {
                    BlockAccessibilityService.cacheSchedules(applicationContext, it)
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
