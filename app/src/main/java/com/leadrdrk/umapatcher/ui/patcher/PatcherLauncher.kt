package com.leadrdrk.umapatcher.ui.patcher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.leadrdrk.umapatcher.R
import com.leadrdrk.umapatcher.patcher.Patcher
import com.leadrdrk.umapatcher.ui.screen.destinations.PatchingScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object PatcherLauncher {
    private const val MAX_LOG_LINES = 200

    var patcher: Patcher? = null
        private set

    var patching by mutableStateOf(false)
        private set
    var completed by mutableStateOf(false)
        private set

    var task by mutableStateOf("")
    var progress by mutableFloatStateOf(-1f)
    val logEntries = mutableStateListOf<String>()

    fun launch(navigator: DestinationsNavigator, patcher: Patcher) {
        if (patching) return

        this.patcher = patcher
        completed = false
        task = ""
        progress = -1f
        logEntries.clear()
        navigator.navigate(PatchingScreenDestination)
    }

    fun log(line: String) {
        logEntries.add(line)
        if (logEntries.size > MAX_LOG_LINES)
            logEntries.removeRange(0, logEntries.size - MAX_LOG_LINES)
    }

    fun onTask(task: String) {
        this.task = task
        log("-- $task")
    }

    fun attachCallbacks(
        onSaveFile: (String, File, (Boolean) -> Unit) -> Unit,
        onInstallLegacy: (File, (Boolean) -> Unit) -> Unit
    ) {
        patcher?.setCallbacks(
            onLog = ::log,
            onProgress = { progress = it },
            onTask = ::onTask,
            onSaveFile = onSaveFile,
            onInstallLegacy = onInstallLegacy
        )
    }

    suspend fun runPatcher(context: Context) {
        val patcher = this.patcher ?: return
        if (patching || completed) return

        patching = true
        withContext(Dispatchers.IO) {
            try {
                val success = patcher.safeRun(context)
                progress = 1f
                task = context.getString(R.string.completed)
                log(context.getString(
                    if (success) R.string.patch_success_msg
                    else R.string.patch_failed_msg
                ))
            } finally {
                patching = false
                completed = true
            }
        }
    }
}
