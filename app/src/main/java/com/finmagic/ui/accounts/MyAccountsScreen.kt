package com.finmagic.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountSummary
import com.finmagic.ui.theme.StatusSafe
import java.util.Locale

/**
 * Manage-all-accounts screen (entry: wallet icon on the Accounts tab top bar):
 * every account in one list with rename and delete inside.
 * Cash account shows an "Adjust Balance" edit button that opens AdjustCashBalanceDialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyAccountsScreen(
    onBack: () -> Unit,
    onAccountClick: (Long) -> Unit = {},
    viewModel: MyAccountsViewModel = hiltViewModel()
) {
    val summaries by viewModel.summaries.collectAsStateWithLifecycle()
    val allAccountTypes by viewModel.allAccountTypes.collectAsStateWithLifecycle()
    var editTarget by remember { mutableStateOf<AccountSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<Account?>(null) }
    var adjustCashTarget by remember { mutableStateOf<AccountSummary?>(null) }

    Scaffold(
        topBar = {
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
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp, vertical = 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(summaries, key = { "acct_${it.account.id}" }) { summary ->
                MyAccountRow(
                    summary = summary,
                    onClick = { onAccountClick(summary.account.id) },
                    onEdit = {
                        editTarget = summary
                    }
                )
            }
        }
    }

    // Adjust balance dialog (cash + bank/wallet)
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

    // Edit dialog (rename / type / delete / adjust balance)
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
}

@Composable
private fun MyAccountRow(
    summary: AccountSummary,
    onClick: () -> Unit,
    onEdit: () -> Unit
) {
    val isCash = summary.account.kind == Account.KIND_CASH
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = accountIcon(summary.account.kind),
                contentDescription = summary.account.kind,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            androidx.compose.foundation.layout.Column(
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
            // Edit button always shown
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
