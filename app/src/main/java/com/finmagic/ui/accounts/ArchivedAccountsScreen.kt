package com.finmagic.ui.accounts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finmagic.data.local.entity.Account

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedAccountsScreen(
    onBack: () -> Unit,
    viewModel: ArchivedAccountsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val archivedItems = uiState.items
    var managingArchivedSmsAccountId by remember { mutableStateOf<Long?>(null) }
    var confirmPermanentDeleteAccount by remember { mutableStateOf<Account?>(null) }
    var selectedAccountIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var confirmBatchDelete by remember { mutableStateOf(false) }

    // Keep selection set pruned to currently present archived accounts
    LaunchedEffect(archivedItems) {
        val validIds = archivedItems.map { it.account.id }.toSet()
        selectedAccountIds = selectedAccountIds.intersect(validIds)
    }

    val isSelectionMode = selectedAccountIds.isNotEmpty()
    BackHandler(enabled = isSelectionMode) {
        selectedAccountIds = emptySet()
    }

    val selectedItems = remember(archivedItems, selectedAccountIds) {
        archivedItems.filter { it.account.id in selectedAccountIds }
    }
    val selectedSmsTotal = remember(selectedItems) {
        selectedItems.sumOf { it.matchingSms.size }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = "${selectedAccountIds.size} selected",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { selectedAccountIds = emptySet() },
                            modifier = Modifier.testTag("archived_clear_selection_button")
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                val allIds = archivedItems.map { it.account.id }.toSet()
                                selectedAccountIds = if (selectedAccountIds.size == allIds.size) {
                                    emptySet()
                                } else {
                                    allIds
                                }
                            },
                            modifier = Modifier.testTag("archived_select_all_button")
                        ) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "Select all archived accounts")
                        }
                        IconButton(
                            onClick = {
                                viewModel.restoreAccounts(selectedAccountIds.toList())
                                selectedAccountIds = emptySet()
                            },
                            modifier = Modifier.testTag("archived_batch_restore_button")
                        ) {
                            Icon(Icons.Filled.Restore, contentDescription = "Restore selected")
                        }
                        IconButton(
                            onClick = { confirmBatchDelete = true },
                            modifier = Modifier.testTag("archived_batch_delete_button")
                        ) {
                            Icon(
                                Icons.Filled.DeleteForever,
                                contentDescription = "Delete selected permanently",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    windowInsets = WindowInsets(0, 0, 0, 0)
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = "Archived Accounts",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("archived_accounts_back")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0)
                )
            }
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (archivedItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Inventory2,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "No archived accounts",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Hold any account on the Accounts screen to archive or delete it. Archived accounts appear here so you can manage their stored SMS or permanently remove them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "archived_info_banner") {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Inventory2,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "${archivedItems.size} archived account${if (archivedItems.size == 1) "" else "s"} · Hold any card to select multiple, clear stored SMS, or restore.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                items(archivedItems, key = { "archived_${it.account.id}" }) { item ->
                    val acctId = item.account.id
                    val isSelected = acctId in selectedAccountIds
                    ArchivedAccountRow(
                        item = item,
                        onRestore = { viewModel.restoreAccount(acctId) },
                        onManageSms = { managingArchivedSmsAccountId = acctId },
                        onDeletePermanently = { confirmPermanentDeleteAccount = item.account },
                        isSmsLoading = uiState.isMatchingSms,
                        selectionMode = isSelectionMode,
                        isSelected = isSelected,
                        onToggleSelect = {
                            selectedAccountIds = if (isSelected) {
                                selectedAccountIds - acctId
                            } else {
                                selectedAccountIds + acctId
                            }
                        },
                        onLongClick = {
                            selectedAccountIds = if (isSelected) {
                                selectedAccountIds - acctId
                            } else {
                                selectedAccountIds + acctId
                            }
                        }
                    )
                }
            }
        }
    }

    managingArchivedSmsAccountId?.let { acctId ->
        val liveItem = archivedItems.firstOrNull { it.account.id == acctId }
        if (liveItem != null) {
            ArchivedAccountSmsDialog(
                item = liveItem,
                onDismiss = { managingArchivedSmsAccountId = null },
                onDeleteSms = { smsId -> viewModel.deleteArchivedAccountSms(smsId) },
                onDeleteAllSms = { ids -> viewModel.deleteAllArchivedAccountSms(ids) },
                onRequestPermanentDelete = {
                    managingArchivedSmsAccountId = null
                    confirmPermanentDeleteAccount = liveItem.account
                }
            )
        } else {
            managingArchivedSmsAccountId = null
        }
    }

    confirmPermanentDeleteAccount?.let { acct ->
        PermanentDeleteAccountDialog(
            account = acct,
            onDismiss = { confirmPermanentDeleteAccount = null },
            onConfirm = {
                viewModel.deleteAccountPermanently(acct.id)
                confirmPermanentDeleteAccount = null
            }
        )
    }

    if (confirmBatchDelete && selectedItems.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text("Delete ${selectedItems.size} Account${if (selectedItems.size == 1) "" else "s"} Permanently?")
            },
            text = {
                Text(
                    buildString {
                        append("Selected accounts will be permanently removed from FinMagic")
                        if (selectedSmsTotal > 0) {
                            append(" along with $selectedSmsTotal stored SMS message${if (selectedSmsTotal == 1) "" else "s"}")
                        }
                        append(". Future SMS rescans may re-create accounts if matching SMS still exist on your phone.")
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteAccountsPermanentlyWithSms(selectedAccountIds.toList())
                        selectedAccountIds = emptySet()
                        confirmBatchDelete = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("confirm_archived_batch_delete_button")
                ) {
                    Text("Delete Permanently")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmBatchDelete = false },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
