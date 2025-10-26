package com.leadrdrk.umapatcher.ui.patcher

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
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.leadrdrk.umapatcher.R
import com.leadrdrk.umapatcher.MainActivity
import com.leadrdrk.umapatcher.patcher.AppPatcher
import com.leadrdrk.umapatcher.shizuku.ShizukuState
import com.leadrdrk.umapatcher.ui.component.RadioGroupOption
import com.leadrdrk.umapatcher.ui.component.SimpleOkCancelDialog
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import rikka.shizuku.Shizuku

@Composable
fun AppPatcherCard(navigator: DestinationsNavigator) {
    var showShizukuRationaleDialog by remember { mutableStateOf(false) }
    var showShizukuNotAvailableDialog by remember { mutableStateOf(false) }

    // Options
    // 0=Save, 1=Normal, 2=Direct, 3=Shizuku
    val installMethod = rememberSaveable { mutableIntStateOf(0) }
    var fileUris by rememberSaveable { mutableStateOf<Array<Uri>>(arrayOf()) }
    val fileSelectLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val data = it.data ?: return@rememberLauncherForActivityResult

        val clipData = data.clipData
        if (clipData != null) {
            fileUris = Array(clipData.itemCount) { i ->
                clipData.getItemAt(i).uri
            }
            return@rememberLauncherForActivityResult
        }

        val uri = data.data
        if (uri != null) {
            fileUris = Array(1) { uri }
        }
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
                        shizukuInstall = true
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

    PatcherCard(
        label = stringResource(R.string.app_patcher_label),
        icon = { Icon(painterResource(R.drawable.ic_apk_install), null) },
        buttons = {
            val isShizukuOptionSelected = installMethod.intValue == 3
            val isButtonEnabled = when {
                installMethod.intValue == 2 -> true
                else -> fileUris.isNotEmpty()
            }

            Button(
                enabled = isButtonEnabled,
                onClick = {
                    if(!isShizukuAvailable && isShizukuOptionSelected) {
                        showShizukuNotAvailableDialog = true
                        return@Button
                    }

                    if(isShizukuOptionSelected) {
                        if(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                            PatcherLauncher.launch(
                                navigator,
                                AppPatcher(fileUris, install = true, directInstall = false, shizukuInstall = true)
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
                                shizukuInstall = false
                            )
                        )
                    }
                }
            ) {
                Text(stringResource(R.string.patch))
            }
        }
    ) {
        val shizukuStatusText = if (isShizukuAvailable) stringResource(R.string.shizuku_install_available) else stringResource(R.string.shizuku_install_unavailable)
        val shizukuStatusColor = if (isShizukuAvailable) Color(0xFF388E3C) else MaterialTheme.colorScheme.error

        RadioGroupOption(
            title = stringResource(R.string.install_method),
            desc = stringResource(R.string.install_method_desc),
            choices = arrayOf(
                stringResource(R.string.save_patched_file),
                stringResource(R.string.normal_install),
                stringResource(R.string.direct_install),
                stringResource(R.string.shizuku_install)
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