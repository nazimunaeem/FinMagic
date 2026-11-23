package com.finmagic.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.finmagic.data.backup.BackupManager
import com.finmagic.data.local.AppThemeMode
import com.finmagic.data.sms.parsers.TxnCategory
import com.finmagic.data.update.UpdateCheckState
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finmagic.ui.theme.StatusSafe
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToCategories: () -> Unit = {},
    onNavigateToSmsReview: () -> Unit = {}
) {
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()
    val alertRules by viewModel.alertRules.collectAsStateWithLifecycle()
    val alertTime by viewModel.alertTime.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val largeDebitAlerts by viewModel.largeDebitAlerts.collectAsStateWithLifecycle()
    val largeDebitThreshold by viewModel.largeDebitThreshold.collectAsStateWithLifecycle()
    val cashExpenseCategories by viewModel.cashExpenseCategories.collectAsStateWithLifecycle()
    val cashIncomeCategories by viewModel.cashIncomeCategories.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val webServerState by viewModel.webServerState.collectAsStateWithLifecycle()
    val webPortalPort by viewModel.webPortalPort.collectAsStateWithLifecycle()
    val webPortalAutoStart by viewModel.webPortalAutoStart.collectAsStateWithLifecycle()
    val updateCheckState by viewModel.updateCheckState.collectAsStateWithLifecycle()
    val autoUpdateEnabled by viewModel.autoUpdateCheckEnabled.collectAsStateWithLifecycle()
    val persistentBackupEnabled by viewModel.persistentBackupEnabled.collectAsStateWithLifecycle()
    val persistentBackupUri by viewModel.persistentBackupUri.collectAsStateWithLifecycle()
    val lastBackupAt by viewModel.lastBackupAt.collectAsStateWithLifecycle()
    val lastBackupLabel by viewModel.lastBackupLabel.collectAsStateWithLifecycle()
    val backupUiState by viewModel.backupUiState.collectAsStateWithLifecycle()
    val restorePreview by viewModel.restorePreview.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var hasSmsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasSmsPermission = isGranted
        if (isGranted) viewModel.triggerRescan()
    }

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

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val quickStatusText = when {
                !hasSmsPermission -> "Action required"
                notificationsEnabled -> "Ready to monitor"
                else -> "Monitoring idle"
            }
            val summaryTone = if (hasSmsPermission) StatusSafe else MaterialTheme.colorScheme.error

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "FinMagic status",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                            Text(
                                text = quickStatusText,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = summaryTone,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        StatusMiniTile(
                            label = "SMS",
                            value = if (hasSmsPermission) "On" else "Off",
                            tint = if (hasSmsPermission) StatusSafe else MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        StatusMiniTile(
                            label = "Alerts",
                            value = if (notificationsEnabled) "On" else "Off",
                            tint = if (notificationsEnabled) StatusSafe else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        StatusMiniTile(
                            label = "Categories",
                            value = "${cashExpenseCategories.size + cashIncomeCategories.size}",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Appearance: Light / Dark / System
            SettingsGroup(title = "Appearance") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Brightness6,
                            contentDescription = "Theme",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Theme",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Current: ${themeMode.label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        AppThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = AppThemeMode.entries.size
                                ),
                                modifier = Modifier.testTag("theme_mode_${mode.name.lowercase()}")
                            ) {
                                Text(mode.label)
                            }
                        }
                    }
                }
            }

            // Group 1: Permissions & Scanning
            SettingsGroup(title = "Permissions & Scanning") {
                SettingsItem(
                    icon = Icons.Default.Sms,
                    title = "SMS Permission",
                    description = if (hasSmsPermission) "Permission granted" else "Required to detect transactions and bills",
                    trailing = {
                        if (!hasSmsPermission) {
                            TextButton(onClick = { permissionLauncher.launch(Manifest.permission.READ_SMS) }) {
                                Text("Grant")
                            }
                        } else {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusSafe)
                        }
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                SettingsItem(
                    icon = Icons.Default.Refresh,
                    title = "Rescan SMS Inbox",
                    description = "Search entire inbox for missed transactions and bills",
                    onClick = {
                        if (hasSmsPermission) viewModel.triggerRescan()
                        else permissionLauncher.launch(Manifest.permission.READ_SMS)
                    }
                )
            }

            // Group 2: Notifications & Alerts
            SettingsGroup(title = "Notifications & Alerts") {
                SettingsItem(
                    icon = Icons.Default.Notifications,
                    title = "Due Date Notifications",
                    description = "Get alerts for upcoming bills",
                    trailing = {
                        Switch(
                            checked = notificationsEnabled,
                            onCheckedChange = { viewModel.toggleNotifications(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = StatusSafe
                            )
                        )
                    }
                )

                if (notificationsEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                    var showTimePicker by remember { mutableStateOf(false) }

                    SettingsItem(
                        icon = Icons.Default.AccessTime,
                        title = "Alert Time",
                        description = "Notify me daily at $alertTime",
                        onClick = { showTimePicker = true },
                        trailing = {
                            Text(
                                text = alertTime,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    )

                    if (showTimePicker) {
                        NotificationTimePickerDialog(
                            initialTime = alertTime,
                            onDismiss = { showTimePicker = false },
                            onConfirm = { hour, minute ->
                                viewModel.setAlertTime("%02d:%02d".format(hour, minute))
                                showTimePicker = false
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                    var showAlertRules by remember { mutableStateOf(false) }
                    SettingsItem(
                        icon = Icons.Default.EventNote,
                        title = "Alert Rules",
                        description = "${alertRules.size} active reminder rule${if (alertRules.size == 1) "" else "s"}",
                        onClick = { showAlertRules = true },
                        trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                    )

                    if (showAlertRules) {
                        AlertRulesModal(
                            activeRules = alertRules,
                            onDismiss = { showAlertRules = false },
                            onToggle = { viewModel.toggleAlertRule(it) }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                SettingsItem(
                    icon = Icons.Default.Notifications,
                    title = "Large Debit Alerts",
                    description = "Notify on account debits over Tk %,.0f".format(largeDebitThreshold),
                    trailing = {
                        Switch(
                            checked = largeDebitAlerts,
                            onCheckedChange = { viewModel.toggleLargeDebitAlerts(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = StatusSafe
                            )
                        )
                    }
                )

                if (largeDebitAlerts) {
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                    var showThresholdDialog by remember { mutableStateOf(false) }
                    SettingsItem(
                        icon = Icons.Default.AccessTime,
                        title = "Large Debit Threshold",
                        description = "Alert threshold amount for account debits",
                        onClick = { showThresholdDialog = true },
                        trailing = {
                            Text(
                                text = "Tk %,.0f".format(largeDebitThreshold),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    )

                    if (showThresholdDialog) {
                        ThresholdDialog(
                            initial = largeDebitThreshold,
                            onDismiss = { showThresholdDialog = false },
                            onConfirm = { value ->
                                viewModel.setLargeDebitThreshold(value)
                                showThresholdDialog = false
                            }
                        )
                    }
                }
            }

            // Group: Categories
            SettingsGroup(title = "Categories") {
                SettingsItem(
                    icon = Icons.Filled.Label,
                    title = "Categories",
                    description = "Manage cash-entry categories and display nicknames",
                    onClick = onNavigateToCategories,
                    trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            SettingsGroup(title = "Privacy & Security") {
                val biometricLock by viewModel.biometricLockEnabled.collectAsStateWithLifecycle()
                SettingsItem(
                    icon = Icons.Default.CheckCircle,
                    title = "Biometric Lock",
                    description = "Require fingerprint or face unlock before opening the app",
                    trailing = {
                        Switch(
                            checked = biometricLock,
                            onCheckedChange = { viewModel.setBiometricLockEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = StatusSafe
                            )
                        )
                    }
                )
            }

            // Group: SMS Recognition
            SettingsGroup(title = "SMS Recognition") {
                SettingsItem(
                    icon = Icons.Default.Sms,
                    title = "SMS Review",
                    description = "Teach unparsed SMS and manage learned rules",
                    onClick = onNavigateToSmsReview,
                    trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // Group: Backup & Persistent File
            SettingsGroup(title = "Backup & Persistent File") {
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

                SettingsItem(
                    icon = Icons.Default.Backup,
                    title = "Auto-Save Persistent Backup",
                    description = backupStatusDesc,
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
                    title = "Restore from Backup",
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

            // Group 3: Data & Export
            SettingsGroup(title = "Data & Export") {
                SettingsItem(
                    icon = Icons.Default.Share,
                    title = "Export All Data",
                    description = "Export accounts, transactions, card spend, and bills to CSV",
                    onClick = { if (exportState !is ExportState.Loading) viewModel.exportData() },
                    trailing = {
                        if (exportState is ExportState.Loading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                )

                if (exportState is ExportState.Success) {
                    Box(modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 12.dp)) {
                        Button(
                            onClick = {
                                val file = (exportState as ExportState.Success).file
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/csv"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Export FinMagic Data"))
                                viewModel.resetExportState()
                            },
                            modifier = Modifier.fillMaxWidth().testTag("settings_share_csv_button"),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Share CSV File")
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
            }

            // Group: Wi-Fi / Hotspot Web Portal
            SettingsGroup(title = "Web Portal (Wi-Fi / Hotspot)") {
                SettingsItem(
                    icon = Icons.Default.Wifi,
                    title = "Enable Web Portal",
                    description = if (webServerState.isRunning) {
                        "Serving on port ${webServerState.port}. Open URL from same Wi-Fi or hotspot"
                    } else {
                        "View, add, and edit accounts, bills, and transactions from a browser"
                    },
                    trailing = {
                        Switch(
                            checked = webServerState.isRunning,
                            onCheckedChange = { viewModel.toggleWebServer(it) },
                            modifier = Modifier.testTag("web_portal_toggle"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = StatusSafe
                            )
                        )
                    }
                )

                if (webServerState.error != null) {
                    Text(
                        text = webServerState.error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 10.dp)
                    )
                }

                if (webServerState.isRunning) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (webServerState.urls.isEmpty()) {
                            Text(
                                text = "No Wi-Fi or Hotspot IPv4 address found. Turn on Wi-Fi or Hotspot and tap Refresh IPs.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            webServerState.urls.forEach { url ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Language,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = url,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = {
                                                clipboard.setText(AnnotatedString(url))
                                                Toast.makeText(context, "Copied $url", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy URL", modifier = Modifier.size(18.dp))
                                        }
                                        IconButton(
                                            onClick = {
                                                runCatching {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                                }
                                            },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in Browser", modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                        TextButton(
                            onClick = { viewModel.refreshWebPortalUrls() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Refresh Network IPs")
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                var showPortDialog by remember { mutableStateOf(false) }
                SettingsItem(
                    icon = Icons.Default.Language,
                    title = "Portal Port",
                    description = "Current port: $webPortalPort",
                    onClick = { showPortDialog = true },
                    trailing = {
                        Text(
                            text = "$webPortalPort",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                )

                if (showPortDialog) {
                    var portText by remember { mutableStateOf(webPortalPort.toString()) }
                    val parsedPort = portText.toIntOrNull()
                    AlertDialog(
                        onDismissRequest = { showPortDialog = false },
                        title = { Text("Web Portal Port") },
                        text = {
                            OutlinedTextField(
                                value = portText,
                                onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
                                label = { Text("Port (1024 - 65535)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        confirmButton = {
                            TextButton(
                                enabled = parsedPort != null && parsedPort in 1024..65535,
                                onClick = {
                                    viewModel.setWebPortalPort(parsedPort!!)
                                    showPortDialog = false
                                }
                            ) { Text("Save") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPortDialog = false }) { Text("Cancel") }
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                SettingsItem(
                    icon = Icons.Default.Refresh,
                    title = "Auto-Start Portal",
                    description = "Start Wi-Fi / Hotspot server automatically when app opens",
                    trailing = {
                        Switch(
                            checked = webPortalAutoStart,
                            onCheckedChange = { viewModel.setWebPortalAutoStart(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = StatusSafe
                            )
                        )
                    }
                )
            }

            // Group: App Updates (GitHub)
            SettingsGroup(title = "App Updates (GitHub)") {
                SettingsItem(
                    icon = Icons.Default.SystemUpdate,
                    title = "Auto-Check Updates",
                    description = "Check nazimunaeem/FinMagic releases on startup",
                    trailing = {
                        Switch(
                            checked = autoUpdateEnabled,
                            onCheckedChange = { viewModel.setAutoUpdateCheckEnabled(it) },
                            modifier = Modifier.testTag("auto_update_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = StatusSafe
                            )
                        )
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                SettingsItem(
                    icon = Icons.Default.Refresh,
                    title = "Check for Updates",
                    description = when (val st = updateCheckState) {
                        is UpdateCheckState.Checking -> "Checking GitHub releases..."
                        is UpdateCheckState.UpdateAvailable -> "New version ${st.release.tagName} available"
                        is UpdateCheckState.UpToDate -> "Up to date (v${st.currentVersion})"
                        is UpdateCheckState.Error -> st.message
                        UpdateCheckState.Idle -> "Current version: v${viewModel.installedVersionName}"
                    },
                    onClick = {
                        if (updateCheckState !is UpdateCheckState.Checking) {
                            viewModel.checkForUpdatesManual()
                        }
                    },
                    trailing = {
                        if (updateCheckState is UpdateCheckState.Checking) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(
                                onClick = { viewModel.checkForUpdatesManual() },
                                modifier = Modifier.testTag("check_updates_button")
                            ) { Text("Check") }
                        }
                    }
                )

                if (updateCheckState is UpdateCheckState.UpdateAvailable) {
                    val release = (updateCheckState as UpdateCheckState.UpdateAvailable).release
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (release.releaseNotes.isNotBlank()) {
                            Text(
                                text = release.releaseNotes.take(240),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = {
                                val targetUrl = release.apkDownloadUrl ?: release.htmlUrl
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("download_update_button")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Download ${release.tagName}")
                        }
                    }
                }
            }

            // App Info
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "FinMagic v${viewModel.installedVersionName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Nazim's Idea developed with AI",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
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
                                    text = "Net Asset: Tk %,.0f".format(summary.netAsset),
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

@Composable
fun ThresholdDialog(
    initial: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var text by remember { mutableStateOf("%.0f".format(initial)) }
    val value = text.replace(",", "").toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = value != null && value > 0, onClick = { onConfirm(value!!) }) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text("Large Debit Threshold") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Amount (Tk)") },
                singleLine = true
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationTimePickerDialog(
    initialTime: String,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    val initialHour = initialTime.substringBefore(":").toIntOrNull() ?: 9
    val initialMinute = initialTime.substringAfter(":").toIntOrNull() ?: 0
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(timePickerState.hour, timePickerState.minute) }) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text("Select Alert Time") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center
            ) {
                TimePicker(state = timePickerState)
            }
        }
    )
}

@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun AlertRuleItem(
    rule: String,
    label: String,
    activeRules: Set<String>,
    onToggle: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(rule) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = activeRules.contains(rule),
            onCheckedChange = { onToggle(rule) },
            colors = CheckboxDefaults.colors(checkedColor = StatusSafe)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

private val ALERT_RULE_OPTIONS = listOf(
    "DAILY" to "Daily (all unpaid bills)",
    "DUE_4_DAILY" to "4 days before: Daily",
    "DUE_3_DAILY" to "3 days before: Daily",
    "DUE_2_12H" to "2 days before: 12 hourly",
    "DUE_2_6H" to "2 days before: 6 hourly",
    "DUE_1_3H" to "1 day before: 3 hourly",
    "DUE_1_1H" to "1 day before: Hourly"
)

private val DETECTED_CATEGORIES = listOf(
    TxnCategory.ATM, TxnCategory.CASH_DEPOSIT, TxnCategory.INTEREST, TxnCategory.DPS,
    TxnCategory.FUND_TRANSFER, TxnCategory.CARD_PAYMENT, TxnCategory.CASH_IN,
    TxnCategory.PAYMENT, TxnCategory.RECEIVED, TxnCategory.BILL_PAY,
    TxnCategory.NPSB, TxnCategory.CREDIT, TxnCategory.MANUAL, TxnCategory.ADJUSTMENT
)

@Composable
fun AlertRulesModal(
    activeRules: Set<String>,
    onDismiss: () -> Unit,
    onToggle: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alert Rules") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(ALERT_RULE_OPTIONS) { (rule, label) ->
                    AlertRuleItem(
                        rule = rule,
                        label = label,
                        activeRules = activeRules,
                        onToggle = onToggle
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryNicknamesModal(
    currentNicknames: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: (Pair<String, String>) -> Unit
) {
    var drafts by remember(currentNicknames) {
        mutableStateOf(DETECTED_CATEGORIES.associateWith { currentNicknames[it] ?: "" })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Category Nicknames") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(DETECTED_CATEGORIES) { category ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = category,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(104.dp)
                        )
                        OutlinedTextField(
                            value = drafts[category] ?: "",
                            onValueChange = { drafts = drafts + (category to it) },
                            placeholder = { Text(category) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                drafts.forEach { (cat, nick) ->
                    val trimmed = nick.trim()
                    if (trimmed != (currentNicknames[cat] ?: "")) {
                        onSave(cat to trimmed)
                    }
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) {
            Box(modifier = Modifier.padding(start = 8.dp)) {
                trailing()
            }
        }
    }
}

@Composable
private fun StatusMiniTile(
    label: String,
    value: String,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = tint
            )
        }
    }
}
