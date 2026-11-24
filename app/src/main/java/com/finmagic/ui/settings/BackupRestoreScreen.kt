package com.finmagic.ui.settings

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finmagic.data.backup.BackupManager
import com.finmagic.ui.theme.StatusSafe
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val savedCsvBackups by viewModel.savedCsvBackups.collectAsStateWithLifecycle()
    val csvRestoreState by viewModel.csvRestoreState.collectAsStateWithLifecycle()
    val persistentBackupEnabled by viewModel.persistentBackupEnabled.collectAsStateWithLifecycle()
    val persistentBackupUri by viewModel.persistentBackupUri.collectAsStateWithLifecycle()
    val lastBackupAt by viewModel.lastBackupAt.collectAsStateWithLifecycle()
    val lastBackupLabel by viewModel.lastBackupLabel.collectAsStateWithLifecycle()
    val backupUiState by viewModel.backupUiState.collectAsStateWithLifecycle()
    val restorePreview by viewModel.restorePreview.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var pendingCsvSaveFile by remember { mutableStateOf<File?>(null) }

    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            viewModel.saveBackupToUri(uri)
        }
    }

    val openBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.requestRestorePreview(uri)
        }
    }

    val saveCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val fileToSave = pendingCsvSaveFile
        pendingCsvSaveFile = null
        if (uri != null && fileToSave != null) {
            viewModel.saveCsvToUri(fileToSave, uri)
        }
    }

    val openCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.restoreCsvFromUri(uri)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("backup_restore_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val backupStatusDesc = remember(persistentBackupEnabled, lastBackupAt, lastBackupLabel) {
                val pathInfo = lastBackupLabel
                    ?: "${BackupManager.PERSISTENT_RELATIVE_DIR}/${BackupManager.PERSISTENT_FILE_NAME}"
                if (lastBackupAt > 0L) {
                    val fmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US)
                    "Survives uninstall at $pathInfo • Last saved ${fmt.format(Date(lastBackupAt))}"
                } else {
                    "Auto-saves account balances, card outstandings & database to $pathInfo (survives uninstall)"
                }
            }

            // Summary banner card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (persistentBackupEnabled) "Auto-Sync Enabled" else "Manual Backup Only",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (persistentBackupEnabled) StatusSafe.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                        ) {
                            Text(
                                text = if (persistentBackupEnabled) "Active" else "Paused",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (persistentBackupEnabled) StatusSafe else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Text(
                        text = backupStatusDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }

            // Group 1: JSON Backup & Persistent File
            SettingsGroup(title = "Full Backup & Persistent File (JSON)") {
                SettingsItem(
                    icon = Icons.Default.Backup,
                    title = "Auto-Save Persistent Backup",
                    description = "Automatically updates Download/FinMagic backup after ledger changes",
                    trailing = {
                        Switch(
                            checked = persistentBackupEnabled,
                            onCheckedChange = { viewModel.setPersistentBackupEnabled(it) },
                            modifier = Modifier.testTag("persistent_backup_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = StatusSafe
                            )
                        )
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                SettingsItem(
                    icon = Icons.Default.Save,
                    title = "Backup Now",
                    description = "Write full backup JSON (balances + database + preferences) right now",
                    onClick = {
                        if (backupUiState !is BackupUiState.Working) {
                            viewModel.backupNow()
                        }
                    },
                    trailing = {
                        if (backupUiState is BackupUiState.Working) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(
                                onClick = { viewModel.backupNow() },
                                modifier = Modifier.testTag("backup_now_button")
                            ) { Text("Save") }
                        }
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                SettingsItem(
                    icon = Icons.Default.FolderOpen,
                    title = "Save Copy / Set Custom File",
                    description = if (persistentBackupUri != null) {
                        "Custom file linked for auto-sync. Tap to change location"
                    } else {
                        "Choose any folder or cloud drive document to also sync backups"
                    },
                    onClick = {
                        createBackupLauncher.launch(BackupManager.PERSISTENT_FILE_NAME)
                    },
                    trailing = {
                        TextButton(
                            onClick = { createBackupLauncher.launch(BackupManager.PERSISTENT_FILE_NAME) },
                            modifier = Modifier.testTag("backup_choose_file_button")
                        ) { Text(if (persistentBackupUri != null) "Change" else "Choose") }
                    }
                )

                if (persistentBackupUri != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 56.dp, end = 16.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { viewModel.clearCustomBackupUri() },
                            modifier = Modifier.testTag("backup_clear_uri_button")
                        ) {
                            Text("Unlink custom file")
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                SettingsItem(
                    icon = Icons.Default.Restore,
                    title = "Restore from JSON Backup",
                    description = "Load a FinMagic_Backup.json file after reinstall or on a new phone",
                    onClick = {
                        if (backupUiState !is BackupUiState.Working) {
                            openBackupLauncher.launch(arrayOf("application/json", "*/*"))
                        }
                    },
                    trailing = {
                        TextButton(
                            onClick = { openBackupLauncher.launch(arrayOf("application/json", "*/*")) },
                            enabled = backupUiState !is BackupUiState.Working,
                            modifier = Modifier.testTag("restore_backup_button")
                        ) { Text("Restore") }
                    }
                )

                when (val bState = backupUiState) {
                    is BackupUiState.Working -> {
                        Text(
                            text = bState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp)
                        )
                    }
                    is BackupUiState.Message -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 56.dp, end = 12.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = bState.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (bState.isError) MaterialTheme.colorScheme.error else StatusSafe,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.clearBackupMessage() }) {
                                Text("Dismiss")
                            }
                        }
                    }
                    BackupUiState.Idle -> Unit
                }
            }

            // Group 2: CSV Data Export & Restore
            SettingsGroup(title = "CSV Data Export & Restore") {
                SettingsItem(
                    icon = Icons.Default.Share,
                    title = "Export All Data (CSV)",
                    description = "Export accounts, transactions, card spend, and bills to CSV",
                    onClick = { if (exportState !is ExportState.Loading) viewModel.exportData() },
                    trailing = {
                        if (exportState is ExportState.Loading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(
                                onClick = { viewModel.exportData() },
                                modifier = Modifier.testTag("export_csv_button")
                            ) { Text("Export") }
                        }
                    }
                )

                if (exportState is ExportState.Success) {
                    val file = (exportState as ExportState.Success).file
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                pendingCsvSaveFile = file
                                saveCsvLauncher.launch(file.name)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_save_csv_button"),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save")
                        }
                        Button(
                            onClick = {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/csv"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newRawUri("CSV", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Export FinMagic Data"))
                                viewModel.resetExportState()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_share_csv_button"),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Share")
                        }
                    }
                } else if (exportState is ExportState.Error) {
                    Text(
                        text = (exportState as ExportState.Error).message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp)
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                SettingsItem(
                    icon = Icons.Default.Restore,
                    title = "Import / Restore from CSV",
                    description = "Load any previously exported FinMagic CSV backup or ledger CSV",
                    onClick = {
                        if (csvRestoreState !is CsvRestoreState.Working) {
                            openCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*"))
                        }
                    },
                    trailing = {
                        TextButton(
                            onClick = {
                                openCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*"))
                            },
                            enabled = csvRestoreState !is CsvRestoreState.Working,
                            modifier = Modifier.testTag("import_csv_button")
                        ) { Text("Import") }
                    }
                )

                when (val csvState = csvRestoreState) {
                    is CsvRestoreState.Working -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 56.dp, end = 16.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                text = csvState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    is CsvRestoreState.Success -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 56.dp, end = 12.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = csvState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusSafe,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.clearCsvRestoreState() }) {
                                Text("Dismiss")
                            }
                        }
                    }
                    is CsvRestoreState.Error -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 56.dp, end = 12.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = csvState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.clearCsvRestoreState() }) {
                                Text("Dismiss")
                            }
                        }
                    }
                    CsvRestoreState.Idle -> Unit
                }

                if (savedCsvBackups.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    Text(
                        text = "PREVIOUSLY SAVED CSV BACKUPS (${savedCsvBackups.size})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
                    )
                    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US) }
                    savedCsvBackups.forEachIndexed { idx, backup ->
                        SavedCsvBackupRow(
                            backup = backup,
                            formattedTime = dateFmt.format(Date(backup.lastModified)),
                            isBusy = csvRestoreState is CsvRestoreState.Working,
                            onRestore = { viewModel.restoreCsvFile(backup.file) },
                            onSave = {
                                pendingCsvSaveFile = backup.file
                                saveCsvLauncher.launch(backup.name)
                            },
                            onShare = {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    backup.file
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/csv"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newRawUri("CSV", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share ${backup.name}"))
                            },
                            onDelete = { viewModel.deleteCsvBackupFile(backup.file) }
                        )
                        if (idx < savedCsvBackups.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(start = 16.dp, end = 16.dp))
                        }
                    }
                }
            }
        }

        val pendingRestore = restorePreview
        if (pendingRestore != null) {
            val summary = pendingRestore.second
            val dateFmt = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US) }
            AlertDialog(
                onDismissRequest = { viewModel.dismissRestorePreview() },
                title = { Text("Restore Backup?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "This will replace all current accounts, cards, transactions, bills, and SMS rules with the backup snapshot.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Saved: ${dateFmt.format(Date(summary.createdAt))} (v${summary.appVersion})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Net Asset: ৳%,.0f".format(summary.netAsset),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "${summary.accountCount} accounts • ${summary.cardCount} cards • ${summary.transactionCount} txns • ${summary.billCount} bills • ${summary.ruleCount} rules",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.confirmRestoreFromPending() },
                        modifier = Modifier.testTag("confirm_restore_backup_button")
                    ) {
                        Text("Restore Now")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { viewModel.dismissRestorePreview() },
                        modifier = Modifier.testTag("cancel_restore_backup_button")
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
