package com.leadrdrk.umapatcher.patcher

import android.app.Activity
import android.content.Context
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

internal object LegacyInstaller {

    const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"

    private fun unwrapActivity(context: Context): Activity? {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return ctx as? Activity
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    fun getApkUri(context: Context, file: File): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val authority = context.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX
            FileProvider.getUriForFile(context, authority, file)
        } else {
            @Suppress("DEPRECATION")
            Uri.fromFile(file)
        }
    }

    fun buildInstallIntent(context: Context, file: File): Intent {
        val uri = getApkUri(context, file)
        val isNonActivityContext = unwrapActivity(context) == null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            if (isNonActivityContext) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun buildUnknownSourcesIntents(context: Context): List<Intent> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()

        val isNonActivityContext = unwrapActivity(context) == null
        val pkgUri = Uri.parse("package:" + context.packageName)

        fun Intent.configure(): Intent {
            if (isNonActivityContext) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return this
        }

        return listOf(
            Intent().apply {
                component = ComponentName("com.android.settings", "com.android.settings.Settings\$ManageAppExternalSourcesActivity")
                data = pkgUri
            }.configure(),

            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .apply { data = pkgUri }
                .configure(),

            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .configure(),

            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .apply { data = pkgUri }
                .configure(),

            Intent(Settings.ACTION_SECURITY_SETTINGS)
                .configure(),

            Intent(Settings.ACTION_SETTINGS)
                .configure()
        )
    }

    fun pickUnknownSourcesIntent(context: Context): Intent? {
        for (intent in buildUnknownSourcesIntents(context)) {
            if (intent.resolveActivity(context.packageManager) != null) {
                return intent
            }
        }
        return buildUnknownSourcesIntents(context).lastOrNull()
    }
}
