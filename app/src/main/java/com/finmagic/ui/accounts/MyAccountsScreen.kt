package com.finmagic.ui.accounts

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountSummary
import com.finmagic.ui.theme.StatusSafe
import java.util.Locale

private enum class MyAccountsBatchAction {
    ARCHIVE,
    DELETE_PERMANENTLY
}

/**
 * Manage-all-accounts screen: every account in one list with rename, edit,
 * long-press multi-selection (archive / permanent delete), and Archived Accounts navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyAccountsScreen(
    onBack: () -> Unit,
    onAccountClick: (Long) -> Unit = {},
    onNavigateToArchivedAccounts: () -> Unit = {},
    viewModel: MyAccountsViewModel = hiltViewModel()
) {
    val summaries by viewModel.summaries.collectAsStateWithLifecycle()
    val archivedCount by viewModel.archivedCount.collectAsStateWithLifecycle()
    val allAccountTypes by viewModel.allAccountTypes.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var editTarget by remember { mutableStateOf<AccountSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<Account?>(null) }
    var adjustCashTarget by remember { mutableStateOf<AccountSummary?>(null) }
    var selectedAccountIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pendingBatchAction by remember { mutableStateOf<MyAccountsBatchAction?>(null) }

    LaunchedEffect(summaries) {
        val selectableIds = summaries
            .filter { it.account.kind != Account.KIND_CASH }
            .map { it.account.id }
            .toSet()
        if (selectedAccountIds.isNotEmpty()) {
            selectedAccountIds = selectedAccountIds.intersect(selectableIds)
        }
    }

    val isSelectionMode = selectedAccountIds.isNotEmpty()
    BackHandler(enabled = isSelectionMode) {
        selectedAccountIds = emptySet()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (isSelectionMode) {
                val allSelectableIds = remember(summaries) {
                    summaries
                        .filter { it.account.kind != Account.KIND_CASH }
                        .map { it.account.id }
                        .toSet()
                }
                val allSelected = allSelectableIds.isNotEmpty() && selectedAccountIds.size == allSelectableIds.size
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
                            modifier = Modifier.testTag("my_accounts_clear_selection")
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                selectedAccountIds = if (allSelected) emptySet() else allSelectableIds
                            },
                            modifier = Modifier.testTag("my_accounts_select_all")
                        ) {
                            Icon(
                                imageVector = if (allSelected) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                                contentDescription = if (allSelected) "Deselect all" else "Select all"
                            )
                        }
                        IconButton(
                            onClick = { pendingBatchAction = MyAccountsBatchAction.ARCHIVE },
                            modifier = Modifier.testTag("my_accounts_batch_archive")
                        ) {
                            Icon(Icons.Filled.Inventory2, contentDescription = "Archive selected")
                        }
                        IconButton(
                            onClick = { pendingBatchAction = MyAccountsBatchAction.DELETE_PERMANENTLY },
                            modifier = Modifier.testTag("my_accounts_batch_delete")
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
                            text = "My Accounts",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onNavigateToArchivedAccounts,
                            modifier = Modifier.testTag("my_accounts_archived_button")
                        ) {
                            if (archivedCount > 0) {
                                BadgedBox(badge = { Badge { Text("$archivedCount") } }) {
                                    Icon(
                                        imageVector = Icons.Filled.Inventory2,
                                        contentDescription = "Archived Accounts ($archivedCount)"
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Inventory2,
                                    contentDescription = "Archived Accounts"
                                )
                            }
                        }
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0)
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(summaries, key = { "acct_${it.account.id}" }) { summary ->
                val acctId = summary.account.id
                val selectable = summary.account.kind != Account.KIND_CASH
                val isSelected = acctId in selectedAccountIds
                MyAccountRow(
                    summary = summary,
                    selectionMode = isSelectionMode,
                    isSelected = isSelected,
                    selectable = selectable,
                    onClick = {
                        if (isSelectionMode) {
                            if (selectable) {
                                selectedAccountIds = if (isSelected) {
                                    selectedAccountIds - acctId
                                } else {
                                    selectedAccountIds + acctId
                                }
                            } else {
                                Toast.makeText(
                                    context,
                                    "Built-in Cash account cannot be archived or deleted",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else {
                            onAccountClick(acctId)
                        }
                    },
                    onLongClick = {
                        if (selectable) {
                            selectedAccountIds = if (isSelected) {
                                selectedAccountIds - acctId
                            } else {
                                selectedAccountIds + acctId
                            }
                        } else {
                            Toast.makeText(
                                context,
                                "Built-in Cash account cannot be archived or deleted",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onEdit = { editTarget = summary }
                )
            }
        }
    }

    adjustCashTarget?.let { summary ->
        AdjustCashBalanceDialog(
            currentBalance = summary.displayBalance,
            title = "Adjust ${summary.account.displayName} Balance",
            onDismiss = { adjustCashTarget = null },
            onWithAdjustment = { target ->
                viewModel.adjustBalance(target, summary.account.id)
                adjustCashTarget = null
            },
            onWithoutAdjustment = { target ->
                viewModel.setBalanceDirect(target, summary.account.id)
                adjustCashTarget = null
            },
            includeInTotal = summary.account.includeInTotal,
            onSetIncludeInTotal = { value ->
                viewModel.setIncludeInTotal(summary.account.id, value)
                adjustCashTarget = null
            }
        )
    }

    deleteTarget?.let { account ->
        DeleteAccountDialog(
            account = account,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                viewModel.deleteAccount(account.id)
                deleteTarget = null
            }
        )
    }

    editTarget?.let { summary ->
        EditAccountDialog(
            account = summary.account,
            availableTypes = allAccountTypes,
            onDismiss = { editTarget = null },
            onConfirm = { newName, includeInTotal ->
                viewModel.renameAccount(summary.account.id, newName)
                if (includeInTotal != summary.account.includeInTotal) {
                    viewModel.setIncludeInTotal(summary.account.id, includeInTotal)
                }
                editTarget = null
            },
            onConfirmDetails = { newName, accountType, isLiability, includeInTotal ->
                viewModel.updateAccountDetails(summary.account.id, newName, accountType, isLiability, includeInTotal)
                editTarget = null
            },
            onDeleteRequest = {
                editTarget = null
                deleteTarget = summary.account
            },
            onAdjustBalance = {
                adjustCashTarget = summary
                editTarget = null
            }
        )
    }

    pendingBatchAction?.let { action ->
        val isDelete = action == MyAccountsBatchAction.DELETE_PERMANENTLY
        val count = selectedAccountIds.size
        val noun = if (count == 1) "account" else "accounts"
        AlertDialog(
            onDismissRequest = { pendingBatchAction = null },
            icon = {
                Icon(
                    imageVector = if (isDelete) Icons.Filled.DeleteForever else Icons.Filled.Inventory2,
                    contentDescription = null,
                    tint = if (isDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    if (isDelete) "Delete $count $noun permanently?"
                    else "Archive $count $noun?"
                )
            },
            text = {
                Text(
                    if (isDelete) {
                        "This permanently deletes the selected $noun, their transactions, and their stored SMS messages in FinMagic. You cannot undo this."
                    } else {
                        "Selected $noun will be moved to Archived Accounts so SMS rescans do not recreate them."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val ids = selectedAccountIds.toList()
                        when (action) {
                            MyAccountsBatchAction.ARCHIVE -> viewModel.archiveAccounts(ids)
                            MyAccountsBatchAction.DELETE_PERMANENTLY -> viewModel.deleteAccountsPermanentlyWithSms(ids)
                        }
                        selectedAccountIds = emptySet()
                        pendingBatchAction = null
                    },
                    colors = if (isDelete) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag(if (isDelete) "my_accounts_confirm_batch_delete" else "my_accounts_confirm_batch_archive")
                ) {
                    Text(if (isDelete) "Delete Permanently" else "Archive")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingBatchAction = null },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MyAccountRow(
    summary: AccountSummary,
    selectionMode: Boolean,
    isSelected: Boolean,
    selectable: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEdit: () -> Unit
) {
    val isCash = summary.account.kind == Account.KIND_CASH
    val haptic = LocalHapticFeedback.current
    val containerColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        selectionMode && !selectable -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
            .testTag("my_account_row_${summary.account.id}"),
        shape = RoundedCornerShape(16.dp),
        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode && selectable) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.testTag("my_account_checkbox_${summary.account.id}")
                )
            } else {
                Icon(
                    imageVector = accountIcon(summary.account.kind),
                    contentDescription = summary.account.kind,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = summary.account.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                if (!isCash && !summary.account.displayName.contains(summary.account.accountMask)) {
                    Text(
                        text = "•• ${summary.account.accountMask}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = String.format(Locale.US, "Tk %,.2f", summary.displayBalance),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = StatusSafe
            )
            if (!selectionMode) {
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Edit ${summary.account.displayName}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
