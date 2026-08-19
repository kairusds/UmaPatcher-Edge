package com.leadrdrk.umapatcher

import android.app.Application
import com.leadrdrk.umapatcher.patcher.LegacyInstallCleanup
import com.leadrdrk.umapatcher.shizuku.ShizukuState

class UmaPatcherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuState.init()
        LegacyInstallCleanup.register(this)
    }
}
