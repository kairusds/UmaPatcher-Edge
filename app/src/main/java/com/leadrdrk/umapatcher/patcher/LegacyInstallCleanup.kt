package com.leadrdrk.umapatcher.patcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import com.leadrdrk.umapatcher.core.GameChecker
import com.leadrdrk.umapatcher.utils.workDir
import java.io.File

object LegacyInstallCleanup {

    private var registered = false

    fun register(context: Context) {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        registered = true
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val data: Uri = intent.data ?: return
            val packageName = data.schemeSpecificPart ?: return
            if (packageName !in GameChecker.packageNames) return

            val workDir = context.workDir
            File(workDir, "legacy_install.apk").delete()
            File(workDir, "merged.apk").delete()
        }
    }
}

