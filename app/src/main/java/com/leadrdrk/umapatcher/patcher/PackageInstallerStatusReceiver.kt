package com.leadrdrk.umapatcher.patcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.leadrdrk.umapatcher.R
import com.leadrdrk.umapatcher.ui.patcher.PatcherLauncher
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class PackageInstallerStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmationIntent = IntentCompat.getParcelableExtra(
                    intent, Intent.EXTRA_INTENT, Intent::class.java
                )
                if (confirmationIntent != null) {
                    context.startActivity(confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                contList.forEach { it.resume(true) }
                contList.clear()
            }
            else -> {
                val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                if (!statusMessage.isNullOrEmpty())
                    PatcherLauncher.log(context.getString(R.string.install_failed) + ": $statusMessage")
                contList.forEach { it.resume(false) }
                contList.clear()
            }
        }
    }

    companion object {
        val contList: MutableList<Continuation<Boolean>> = mutableListOf()
        suspend fun waitForInstallFinish(): Boolean {
            return suspendCoroutine { cont ->
                contList.add(cont)
            }
        }
    }
}
