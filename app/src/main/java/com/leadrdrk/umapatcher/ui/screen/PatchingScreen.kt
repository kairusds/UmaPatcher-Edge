package com.leadrdrk.umapatcher.ui.screen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.leadrdrk.umapatcher.R
import com.leadrdrk.umapatcher.patcher.LegacyInstaller
import com.leadrdrk.umapatcher.ui.component.BackButton
import com.leadrdrk.umapatcher.ui.component.SimpleOkCancelDialog
import com.leadrdrk.umapatcher.ui.component.TopBar
import com.leadrdrk.umapatcher.ui.patcher.PatcherLauncher
import com.leadrdrk.umapatcher.utils.copyTo
import com.leadrdrk.umapatcher.utils.getActivity
import com.leadrdrk.umapatcher.utils.safeNavigate
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private const val MAX_LOG_LINES = 200

@Destination
@Composable
fun PatchingScreen(navigator: DestinationsNavigator) {
    val workingStr = stringResource(R.string.working)
    val completedStr = stringResource(R.string.completed)

    val log = remember { mutableStateListOf<String>() }
    var currentTask by remember { mutableStateOf(workingStr) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var completed by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Patcher callbacks
    fun onLog(line: String) {
        log.add(line)
        if (log.size > MAX_LOG_LINES)
            log.removeRange(0, log.size - MAX_LOG_LINES)
    }
    fun onProgress(p: Float) { progress = p }
    fun onTask(task: String) {
        currentTask = task
        log.add("-- $task")
    }

    val coroutineScope = rememberCoroutineScope()
    var sfFile by remember { mutableStateOf<File?>(null) }
    var sfCallback: (Boolean) -> Unit by remember { mutableStateOf({}) }
    val sfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val uri = it.data?.data
        if (uri == null) {
            sfCallback(false)
            sfFile = null
            return@rememberLauncherForActivityResult
        }

        coroutineScope.launch(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri).use { output ->
                if (output == null) {
                    sfCallback(false)
                    sfFile = null
                    return@launch
                }

                val file = sfFile!!
                val length = file.length().toFloat()
                onTask(context.getString(R.string.copying_file_name).format(file.name))
                progress = 0f
                file.inputStream().use { input ->
                    input.copyTo(output) { current ->
                        progress = current / length
                    }
                }
            }
            sfCallback(true)
            currentTask = completedStr
            sfFile = null
        }
    }

    fun onSaveFile(filename: String, file: File, callback: (Boolean) -> Unit = {}) {
        sfFile = file
        sfCallback = callback
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_TITLE, filename)
            }
        sfLauncher.launch(intent)
    }

    var legacyFile by remember { mutableStateOf<File?>(null) }
    var legacyCallback by remember { mutableStateOf<(Boolean) -> Unit>({}) }
    var showLegacyPermDialog by remember { mutableStateOf(false) }


    fun launchLegacyInstall(context: Context, file: File, callback: (Boolean) -> Unit) {
        try {
            val activityContext = context.getActivity() ?: context
            val intent = LegacyInstaller.buildInstallIntent(activityContext, file)
            activityContext.startActivity(intent)
            legacyFile = null
            callback(true)
        } catch (_: ActivityNotFoundException) {
            callback(false)
        } catch (_: SecurityException) {
            callback(false)
        } catch (_: Exception) {
            callback(false)
        }
    }

    val legacyPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val file = legacyFile
        if (file == null) {
            legacyCallback(false)
            return@rememberLauncherForActivityResult
        }
        if (LegacyInstaller.canRequestPackageInstalls(context)) {
            launchLegacyInstall(context, file, legacyCallback)
        } else {
            legacyCallback(false)
            legacyFile = null
        }
    }

    fun onInstallLegacy(file: File, callback: (Boolean) -> Unit) {
        legacyFile = file
        legacyCallback = callback
        if (LegacyInstaller.canRequestPackageInstalls(context)) {
            launchLegacyInstall(context, file, callback)
        } else {
            showLegacyPermDialog = true
        }
    }

    if (showLegacyPermDialog) {
        SimpleOkCancelDialog(
            title = stringResource(R.string.legacy_install_unknown_sources_required),
            onClose = { ok ->
                showLegacyPermDialog = false
                if (ok) {
                    val permIntent = LegacyInstaller.pickUnknownSourcesIntent(context)
                    if (permIntent != null) {
                        try {
                            legacyPermLauncher.launch(permIntent)
                        } catch (_: Exception) {
                            legacyCallback(false)
                            legacyFile = null
                        }
                    } else {
                        legacyCallback(false)
                        legacyFile = null
                    }
                } else {
                    legacyCallback(false)
                    legacyFile = null
                }
            }
        ) {
            Text(stringResource(R.string.legacy_install_unknown_sources_info))
        }
    }

    val scrollState = rememberScrollState()
    LaunchedEffect(log.size) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    BackHandler {
        if (completed && sfFile == null && legacyFile == null) {
            safeNavigate(lifecycleOwner) {
                navigator.popBackStack()
            }
        }
    }

    val patchSuccessMsg = stringResource(R.string.patch_success_msg)
    val patchFailedMsg = stringResource(R.string.patch_failed_msg)

    LaunchedEffect(true) {
        if (PatcherLauncher.patching) return@LaunchedEffect
        val patcher = PatcherLauncher.patcher!!
        patcher.setCallbacks(
            onLog = ::onLog,
            onProgress = ::onProgress,
            onTask = ::onTask,
            onSaveFile = ::onSaveFile,
            onInstallLegacy = ::onInstallLegacy
        )
        PatcherLauncher.runPatcher(context) { success ->
            completed = true
            log.add(if (success) patchSuccessMsg else patchFailedMsg)
            progress = 1f
        }
    }

    LaunchedEffect(completed) {
        if (completed) {
            currentTask = completedStr
        }
    }

    Scaffold(
        topBar = {
            TopBar(
                title = if (completed) completedStr else workingStr,
                navigationIcon = { BackButton(navigator, enabled = completed && sfFile == null && legacyFile == null) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxWidth()
        ) {
            TextField(
                value = log.joinToString("\n"),
                onValueChange = {},
                readOnly = true,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = TextUnit(1.4f, TextUnitType.Em)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Unspecified,
                    unfocusedIndicatorColor = Color.Unspecified
                )
            )
            Column(
                modifier = Modifier
                    .padding(all = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Text(
                        text = currentTask,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = if (progress < 0) "¯\\_(ツ)_/¯" else "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (progress < 0) {
                    // Indeterminate
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else {
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}