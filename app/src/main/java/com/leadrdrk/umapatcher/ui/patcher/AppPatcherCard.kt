package com.leadrdrk.umapatcher.ui.patcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import com.leadrdrk.umapatcher.R
import com.leadrdrk.umapatcher.MainActivity
import com.leadrdrk.umapatcher.core.PrefKey
import com.leadrdrk.umapatcher.core.dataStore
import com.leadrdrk.umapatcher.core.getPrefValue
import com.leadrdrk.umapatcher.patcher.AppPatcher
import com.leadrdrk.umapatcher.shizuku.ShizukuState
import com.leadrdrk.umapatcher.ui.component.RadioGroupOption
import com.leadrdrk.umapatcher.ui.component.SimpleOkCancelDialog
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

private const val INSTALL_METHOD_SAVE_DELAY_MILLIS = 5000L

@Composable
fun AppPatcherCard(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    var showShizukuRationaleDialog by remember { mutableStateOf(false) }
    var showShizukuNotAvailableDialog by remember { mutableStateOf(false) }

    // Options
    // 0=Save, 1=Normal, 2=Direct, 3=Shizuku, 4=Legacy
    val installMethod = rememberSaveable { mutableIntStateOf(1) }
    var fileUris by rememberSaveable { mutableStateOf<Array<Uri>>(arrayOf()) }
    var mergeApksPref by remember { mutableStateOf(false) }
    var useLatestVersion by remember { mutableStateOf(true) }
    var customSoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var customSoFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var customSoError by remember { mutableStateOf<String?>(null) }
    var deepLinkMissingFiles by remember { mutableStateOf<String?>(null) }
    var stateLoaded by remember { mutableStateOf(false) }
    var installMethodLoaded by rememberSaveable { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val installMethodSaveScope = remember { CoroutineScope(SupervisorJob()) }

    // Restore options and the persisted file URIs from preferences
    LaunchedEffect(true) {
        mergeApksPref = context.getPrefValue(PrefKey.MERGE_APKS) as Boolean
        useLatestVersion = context.getPrefValue(PrefKey.USE_LATEST_VERSION) as Boolean

        if (!installMethodLoaded) {
            val savedInstallMethod = context.getPrefValue(PrefKey.INSTALL_METHOD) as Int
            if (installMethod.intValue == 1) installMethod.intValue = savedInstallMethod
            installMethodLoaded = true
        }

        if (fileUris.isEmpty()) {
            val savedFileUris = (context.getPrefValue(PrefKey.FILE_URIS) as String)
                .split('\n')
                .filter { it.isNotEmpty() }
                .map { uri -> Uri.parse(uri) }
            val existingFileUris = mutableListOf<Uri>()
            for (uri in savedFileUris) {
                if (getFileName(context, uri) != null) existingFileUris.add(uri)
            }
            if (existingFileUris.size != savedFileUris.size)
                saveFileUris(context, existingFileUris.toTypedArray())
            if (existingFileUris.isNotEmpty())
                fileUris = existingFileUris.toTypedArray()
        }

        if (customSoUri == null) {
            val savedSoUri = (context.getPrefValue(PrefKey.CUSTOM_SO_URI) as String)
                .takeIf { it.isNotEmpty() }
                ?.let { Uri.parse(it) }
            if (savedSoUri != null) {
                val savedSoFileName = getFileName(context, savedSoUri)
                if (savedSoFileName != null) {
                    customSoUri = savedSoUri
                    customSoFileName = savedSoFileName
                } else {
                    saveCustomSoUri(context, null)
                }
            }
        }

        stateLoaded = true
    }

    LaunchedEffect(installMethodLoaded) {
        if (!installMethodLoaded) return@LaunchedEffect
        snapshotFlow { installMethod.intValue }
            .drop(1)
            .collectLatest { method ->
                delay(INSTALL_METHOD_SAVE_DELAY_MILLIS)
                saveInstallMethod(context, method)
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (installMethodLoaded)
                installMethodSaveScope.launch {
                    saveInstallMethod(context, installMethod.intValue)
                }
        }
    }

    val fileSelectLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val data = it.data ?: return@rememberLauncherForActivityResult

        val clipData = data.clipData
        val newFileUris = if (clipData != null) {
            Array(clipData.itemCount) { i ->
                clipData.getItemAt(i).uri
            }
        } else {
            val uri = data.data ?: return@rememberLauncherForActivityResult
            Array(1) { uri }
        }

        for (uri in fileUris) releasePersistableUriPermission(context, uri)
        for (uri in newFileUris) tryTakePersistableUriPermission(context, uri)
        fileUris = newFileUris
        coroutineScope.launch { saveFileUris(context, newFileUris) }
    }

    // Custom .so file picker launcher (supports external file managers)
    val customSoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val data = it.data ?: return@rememberLauncherForActivityResult

        val clipData = data.clipData
        val uri = when {
            clipData == null -> data.data ?: return@rememberLauncherForActivityResult
            clipData.itemCount == 1 -> clipData.getItemAt(0).uri
            else -> {
                customSoError = context.getString(R.string.custom_so_multiple_selected)
                return@rememberLauncherForActivityResult
            }
        }

        val fileName = getFileName(context, uri)
        if (fileName != null && !fileName.endsWith(".so", ignoreCase = true)) {
            customSoError = context.getString(R.string.custom_so_not_so_file)
            return@rememberLauncherForActivityResult
        }

        val oldSoUri = customSoUri
        if (oldSoUri != null) releasePersistableUriPermission(context, oldSoUri)
        tryTakePersistableUriPermission(context, uri)
        customSoUri = uri
        customSoFileName = fileName ?: uri.lastPathSegment
        coroutineScope.launch { saveCustomSoUri(context, uri) }
    }
    val isShizukuAvailable by ShizukuState.isAvailable

    LaunchedEffect(navigator, installMethod.intValue, fileUris) {
        MainActivity.onShizukuPermissionResult = { grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                PatcherLauncher.launch(
                    navigator,
                    AppPatcher(
                        fileUris = fileUris,
                        install = true,
                        directInstall = false,
                        shizukuInstall = true,
                        customSoUri = if (!useLatestVersion) customSoUri else null
                    )
                )
            }
        }
    }

    if(showShizukuRationaleDialog) {
        SimpleOkCancelDialog(
            title = stringResource(R.string.shizuku_permission_required),
            onClose = { ok ->
                showShizukuRationaleDialog = false
                if (ok) {
                    Shizuku.requestPermission(MainActivity.SHIZUKU_PERMISSION_REQUEST_CODE)
                }
            }
        ) {
            Text(stringResource(R.string.shizuku_permission_required))
        }
    }

    val uriHandler = LocalUriHandler.current
    if(showShizukuNotAvailableDialog) {
        SimpleOkCancelDialog(
            title = stringResource(R.string.shizuku_unavailable),
            onClose = { ok ->
                showShizukuNotAvailableDialog = false
                if (ok) {
                    uriHandler.openUri("https://shizuku.rikka.app/download")
                }
            }
        ) {
            Text(stringResource(R.string.shizuku_unavailable_info))
        }
    }

    if (customSoError != null) {
        SimpleOkCancelDialog(
            title = stringResource(R.string.custom_so_invalid_selection),
            onClose = { customSoError = null }
        ) {
            Text(customSoError!!)
        }
    }

    if (deepLinkMissingFiles != null) {
        SimpleOkCancelDialog(
            title = stringResource(R.string.deep_link_missing_files),
            onClose = { deepLinkMissingFiles = null }
        ) {
            Text(deepLinkMissingFiles!!)
        }
    }

    fun startPatching() {
        val isShizukuOptionSelected = installMethod.intValue == 3
        if(!isShizukuAvailable && isShizukuOptionSelected) {
            showShizukuNotAvailableDialog = true
            return
        }

        if(isShizukuOptionSelected) {
            if(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                PatcherLauncher.launch(
                    navigator,
                    AppPatcher(
                        fileUris,
                        install = true,
                        directInstall = false,
                        shizukuInstall = true,
                        customSoUri = if (!useLatestVersion) customSoUri else null
                    )
                )
            }else if (Shizuku.shouldShowRequestPermissionRationale()) {
                showShizukuRationaleDialog = true
            }else {
                Shizuku.requestPermission(MainActivity.SHIZUKU_PERMISSION_REQUEST_CODE)
            }
        }else {
            PatcherLauncher.launch(
                navigator,
                AppPatcher(
                    fileUris = if (installMethod.intValue == 2) arrayOf() else fileUris,
                    install = installMethod.intValue == 1,
                    directInstall = installMethod.intValue == 2,
                    shizukuInstall = false,
                    customSoUri = if (!useLatestVersion) customSoUri else null,
                    mergeApks = mergeApksPref && installMethod.intValue == 0,
                    legacyInstall = installMethod.intValue == 4
                )
            )
        }
    }

    // umapatcher-edge://update deep link. start patching with the last selected files
    val pendingUpdateDeepLink = MainActivity.pendingUpdateDeepLink
    LaunchedEffect(pendingUpdateDeepLink, stateLoaded) {
        if (!pendingUpdateDeepLink || !stateLoaded) return@LaunchedEffect

        MainActivity.pendingUpdateDeepLink = false

        val apksMissing = installMethod.intValue != 2 && fileUris.isEmpty()
        val soMissing = !useLatestVersion && customSoUri == null
        deepLinkMissingFiles = when {
            apksMissing && soMissing -> context.getString(R.string.deep_link_missing_apks_and_so)
            apksMissing -> context.getString(R.string.deep_link_missing_apks)
            soMissing -> context.getString(R.string.deep_link_missing_so)
            else -> null
        }
        if (deepLinkMissingFiles == null) startPatching()
    }

    PatcherCard(
        label = stringResource(R.string.app_patcher_label),
        icon = { Icon(painterResource(R.drawable.ic_apk_install), null) },
        buttons = {
            val isButtonEnabled = when {
                !useLatestVersion && customSoUri == null -> false
                installMethod.intValue == 2 -> true
                else -> fileUris.isNotEmpty()
            }

            Button(
                enabled = isButtonEnabled,
                onClick = { startPatching() }
            ) {
                Text(stringResource(R.string.patch))
            }
        }
    ) {
        // Custom .so file picker (shown when "use latest version" is disabled in settings)
        if (!useLatestVersion) {
            Spacer(Modifier.height(16.dp))
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = Intent(Intent.ACTION_GET_CONTENT)
                            .apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }
                        customSoLauncher.launch(intent)
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Icon(painterResource(R.drawable.ic_file_open), null)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.select_custom_so),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (customSoFileName != null)
                                customSoFileName!!
                            else
                                stringResource(R.string.no_custom_so_selected),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.custom_so_supported_files),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Divider()
        }

        val shizukuStatusText = if (isShizukuAvailable) stringResource(R.string.shizuku_install_available) else stringResource(R.string.shizuku_install_unavailable)
        val shizukuStatusColor = if (isShizukuAvailable) Color(0xFF388E3C) else MaterialTheme.colorScheme.error

        RadioGroupOption(
            title = stringResource(R.string.install_method),
            desc = stringResource(R.string.install_method_desc),
            choices = arrayOf(
                stringResource(R.string.save_patched_file),
                stringResource(R.string.normal_install),
                stringResource(R.string.direct_install),
                stringResource(R.string.shizuku_install),
                stringResource(R.string.legacy_install)
            ),
            state = installMethod,
            choiceContent = { index, text ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if(index == 3) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = shizukuStatusText,
                            color = shizukuStatusColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        )
        if (installMethod.intValue != 2) {
            Spacer(Modifier.height(16.dp))
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier
                    .clickable {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                type = "*/*"
                            }
                        fileSelectLauncher.launch(intent)
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Icon(painterResource(R.drawable.ic_file_open), null)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.tap_to_select_file),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.n_files_selected).format(fileUris.size),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.app_patcher_supported_files),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun getFileName(context: Context, uri: Uri): String? {
    val cursor = try {
        context.contentResolver.query(uri, null, null, null, null)
    } catch (_: Exception) {
        return null
    }
    return cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) it.getString(nameIndex) else uri.lastPathSegment
        } else null
    }
}

private fun tryTakePersistableUriPermission(context: Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    } catch (_: SecurityException) {
        // No persistable grant was offered for this URI
    }
}

private fun releasePersistableUriPermission(context: Context, uri: Uri) {
    try {
        context.contentResolver.releasePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    } catch (_: SecurityException) {
        // No persisted permission was held for this URI
    }
}

private suspend fun saveFileUris(context: Context, fileUris: Array<Uri>) {
    context.dataStore.edit {
        it[PrefKey.FILE_URIS] = fileUris.joinToString("\n") { uri -> uri.toString() }
    }
}

private suspend fun saveCustomSoUri(context: Context, uri: Uri?) {
    context.dataStore.edit {
        it[PrefKey.CUSTOM_SO_URI] = uri?.toString() ?: ""
    }
}

private suspend fun saveInstallMethod(context: Context, installMethod: Int) {
    context.dataStore.edit {
        it[PrefKey.INSTALL_METHOD] = installMethod
    }
}
