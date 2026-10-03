package com.appshutdown.app

import android.app.Application
import androidx.work.*
import java.util.concurrent.TimeUnit

class AppShutdownApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 15분마다 서버와 스케줄 동기화 (WorkManager 최소 주기)
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "schedule-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            req
        )
    }
}
