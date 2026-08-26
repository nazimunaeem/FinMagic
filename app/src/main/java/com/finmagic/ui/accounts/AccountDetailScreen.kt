package com.finmagic.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.ui.theme.StatusSafe
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    onBack: () -> Unit,
    viewModel: AccountDetailViewModel = hiltViewModel()
) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val categoryNicknames by viewModel.categoryNicknames.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    val settingsViewModel: com.finmagic.ui.settings.SettingsViewModel = hiltViewModel()
    val cashExpenseCategories by settingsViewModel.cashExpenseCategories.collectAsStateWithLifecycle()
    val cashIncomeCategories by settingsViewModel.cashIncomeCategories.collectAsStateWithLifecycle()

    var showEntryDialog by remember { mutableStateOf(false) }
    var showTransferDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showAdjustCashDialog by remember { mutableStateOf(false) }
    var selectedTxn by remember { mutableStateOf<AccountTransaction?>(null) }
    var editTxn by remember { mutableStateOf<AccountTransaction?>(null) }
    var correctTxn by remember { mutableStateOf<AccountTransaction?>(null) }

    // Month paging for the transaction list (month is 0-based).
    val nowCal = remember { Calendar.getInstance() }
    var historyYear by remember { mutableIntStateOf(nowCal.get(Calendar.YEAR)) }
    var historyMonth by remember { mutableIntStateOf(nowCal.get(Calendar.MONTH)) }

    val runningBalances = remember(transactions) { computeRunningBalances(transactions) }
    val dayGroups = remember(transactions, historyYear, historyMonth) {
        val cal = Calendar.getInstance().apply {
            set(historyYear, historyMonth, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val end = cal.timeInMillis
        transactions.filter { it.category != "DirectSet" && it.occurredAt >= start && it.occurredAt < end }
            .groupBy { dayStart(it.occurredAt) }
            .toList()
            .sortedByDescending { it.first }
            .map { (dayStartMillis, txns) -> dayLabel(dayStartMillis) to txns }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = account?.displayName ?: "Account",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (account?.kind != Account.KIND_CASH &&
                                account?.displayName?.contains(account?.accountMask.orEmpty()) != true
                            ) {
                                Text(
                                    text = "•• ${account?.accountMask.orEmpty()} ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val displayBalance = if (account?.kind == Account.KIND_CASH) {
                                transactions.sumOf {
                                    if (it.direction == AccountTransaction.DIRECTION_IN) it.amount else -it.amount
                                }
                            } else {
                                runningBalances.values.lastOrNull() ?: 0.0
                            }
                            Text(
                                text = String.format(Locale.US, "Tk %,.2f", displayBalance),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = StatusSafe
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        showEditDialog = true
                    }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit Account")
                    }
                    IconButton(onClick = { if (exportState !is ExportState.Loading) viewModel.exportCsv() }) {
                        Icon(Icons.Filled.Download, contentDescription = "Export CSV")
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        },
        floatingActionButton = {
            if (account?.kind == Account.KIND_CASH) {
                FloatingActionButton(onClick = { showEntryDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add entry")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (transactions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No transactions yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        MonthPager(
                            year = historyYear,
                            month = historyMonth,
                            onPrevious = {
                                var m = historyMonth - 1
                                var y = historyYear
                                if (m < 0) { m = 11; y -= 1 }
                                historyMonth = m
                                historyYear = y
                            },
                            onNext = {
                                var m = historyMonth + 1
                                var y = historyYear
                                if (m > 11) { m = 0; y += 1 }
                                historyMonth = m
                                historyYear = y
                            },
                            nextEnabled = !(historyYear == nowCal.get(Calendar.YEAR) &&
                                historyMonth == nowCal.get(Calendar.MONTH))
                        )
                    }
                    if (dayGroups.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No transactions found for this month.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    dayGroups.forEach { (day, txns) ->
                        item(key = "day_$day") {
                            Text(
                                text = day,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(txns, key = { it.id }) { txn ->
                            TxnRow(
                                txn = txn,
                                categoryNicknames = categoryNicknames,
                                runningBalance = runningBalances[txn.id],
                                onTap = { selectedTxn = txn },
                                onDelete = if (txn.isManual || txn.category == com.finmagic.data.sms.parsers.TxnCategory.ADJUSTMENT) {
                                    { viewModel.deleteEntry(txn.id) }
                                } else null,
                                onUpdateCategory = { newCategory ->
                                    viewModel.updateTransactionCategory(txn.id, newCategory)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showEntryDialog) {
        CashEntryDialog(
            expenseCategories = cashExpenseCategories,
            incomeCategories = cashIncomeCategories,
            onDismiss = { showEntryDialog = false },
            onAddExpense = { amount, note, category, occurredAt ->
                viewModel.addCashEntry(AccountTransaction.DIRECTION_OUT, amount, note, category, occurredAt)
                showEntryDialog = false
            },
            onAddIncome = { amount, note, category, occurredAt ->
                viewModel.addCashEntry(AccountTransaction.DIRECTION_IN, amount, note, category, occurredAt)
                showEntryDialog = false
            },
            onTransfer = {
                showEntryDialog = false
                showTransferDialog = true
            }
        )
    }

    if (showTransferDialog) {
        TransferDialog(
            viewModel = viewModel,
            onDismiss = { showTransferDialog = false }
        )
    }

    if (showEditDialog && account != null) {
        EditAccountDialog(
            account = account!!,
            onDismiss = { showEditDialog = false },
            onConfirm = { newName, includeInTotal ->
                viewModel.renameAccount(newName)
                if (includeInTotal != account!!.includeInTotal) {
                    viewModel.setIncludeInTotal(includeInTotal)
                }
                showEditDialog = false
            },
            onDeleteRequest = {
                showEditDialog = false
                showDeleteConfirm = true
            },
            onAdjustBalance = {
                showEditDialog = false
                showAdjustCashDialog = true
            }
        )
    }

    if (showDeleteConfirm && account != null) {
        DeleteAccountDialog(
            account = account!!,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                viewModel.deleteAccount {
                    showDeleteConfirm = false
                    onBack()
                }
            }
        )
    }

    if (showAdjustCashDialog && account != null) {
        val currentCashBalance = transactions.sumOf {
            if (it.direction == AccountTransaction.DIRECTION_IN) it.amount else -it.amount
        }
        AdjustCashBalanceDialog(
            currentBalance = currentCashBalance,
            onDismiss = { showAdjustCashDialog = false },
            onWithAdjustment = { target ->
                viewModel.adjustCashBalance(target)
                showAdjustCashDialog = false
            },
            onWithoutAdjustment = { target ->
                viewModel.setCashBalanceDirect(target)
                showAdjustCashDialog = false
            },
            includeInTotal = account!!.includeInTotal,
            onSetIncludeInTotal = { value ->
                viewModel.setIncludeInTotal(value)
                showAdjustCashDialog = false
            }
        )
    }

    selectedTxn?.let { txn ->
        TxnDetailDialog(
            txn = txn,
            accountName = account?.displayName ?: "Account",
            onDismiss = { selectedTxn = null },
            alreadyExpense = com.finmagic.data.sms.parsers.TxnCategory.countsAsExpense(
                txn.isExpenseOverride, txn.direction, txn.category
            ),
            onSetExpense = { include ->
                viewModel.setExpenseOverride(
                    txn.id,
                    com.finmagic.data.sms.parsers.TxnCategory.targetOverride(
                        include, txn.direction, txn.category
                    )
                )
                selectedTxn = null
            },
            onEditDetails = {
                selectedTxn = null
                editTxn = txn
            },
            onCorrect = if (txn.smsBody != null) {
                {
                    selectedTxn = null
                    correctTxn = txn
                }
            } else null
        )
    }

    editTxn?.let { txn ->
        EditTransactionDetailsDialog(
            txn = txn,
            onDismiss = { editTxn = null },
            onConfirm = { counterparty, category, amount ->
                viewModel.updateTransactionDetails(txn.id, counterparty, category, amount)
                editTxn = null
            },
            onDelete = if (txn.category == com.finmagic.data.sms.parsers.TxnCategory.ADJUSTMENT) {
                {
                    viewModel.deleteEntry(txn.id)
                    editTxn = null
                }
            } else null
        )
    }

    correctTxn?.let { txn ->
        val allAccounts by viewModel.allAccounts.collectAsStateWithLifecycle()
        CorrectTransactionDialog(
            txn = txn,
            accounts = allAccounts,
            onDismiss = { correctTxn = null },
            onConfirm = { direction, amount, accountId, category, counterparty ->
                viewModel.correctTransaction(txn.id, direction, amount, accountId, category, counterparty)
                correctTxn = null
            }
        )
    }

    when (val state = exportState) {
        is ExportState.Success -> {
            androidx.compose.runtime.LaunchedEffect(state.file) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", state.file
                )
                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(android.content.Intent.createChooser(shareIntent, "Export Ledger"))
                viewModel.resetExportState()
            }
        }
        else -> Unit
    }
}

@Composable
private fun CashEntryDialog(
    expenseCategories: List<String>,
    incomeCategories: List<String>,
    onDismiss: () -> Unit,
    onAddExpense: (Double, String?, String?, Long) -> Unit,
    onAddIncome: (Double, String?, String?, Long) -> Unit,
    onTransfer: () -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var expenseCategory by remember { mutableStateOf<String?>(null) }
    var incomeCategory by remember { mutableStateOf<String?>(null) }
    var occurredAt by remember {
        mutableStateOf(Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val amount = amountText.replace(",", "").toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Cash Entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(occurredAt)),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Date") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            Calendar.getInstance().apply { timeInMillis = occurredAt }.let { selected ->
                                android.app.DatePickerDialog(
                                    context,
                                    { _, year, month, day ->
                                        occurredAt = Calendar.getInstance().apply {
                                            set(year, month, day, 12, 0, 0)
                                            set(Calendar.MILLISECOND, 0)
                                        }.timeInMillis
                                    },
                                    selected.get(Calendar.YEAR),
                                    selected.get(Calendar.MONTH),
                                    selected.get(Calendar.DAY_OF_MONTH)
                                ).show()
                            }
                        }
                )
                Text("Expense category", style = MaterialTheme.typography.labelMedium)
                CategorySelector(
                    categories = expenseCategories,
                    selected = expenseCategory,
                    onSelected = { expenseCategory = it }
                )
                Text("Income category", style = MaterialTheme.typography.labelMedium)
                CategorySelector(
                    categories = incomeCategories,
                    selected = incomeCategory,
                    onSelected = { incomeCategory = it }
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = amount != null && amount > 0, onClick = { onAddExpense(amount!!, note.ifBlank { null }, expenseCategory, occurredAt) }) {
                    Text("Expense", color = MaterialTheme.colorScheme.error)
                }
                TextButton(enabled = amount != null && amount > 0, onClick = { onAddIncome(amount!!, note.ifBlank { null }, incomeCategory, occurredAt) }) {
                    Text("Income", color = StatusSafe)
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onTransfer) { Text("Transfer") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun TransferDialog(
    viewModel: AccountDetailViewModel,
    onDismiss: () -> Unit
) {
    val accounts = produceAccounts(viewModel)
    var amountText by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var fromCash by remember { mutableStateOf(true) }
    val amount = amountText.replace(",", "").toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transfer Cash") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount") },
                    singleLine = true
                )
                Text("Direction", style = MaterialTheme.typography.labelMedium)
                Row {
                    androidx.compose.material3.FilterChip(
                        selected = fromCash,
                        onClick = { fromCash = true },
                        label = { Text("Cash → Account") }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    androidx.compose.material3.FilterChip(
                        selected = !fromCash,
                        onClick = { fromCash = false },
                        label = { Text("Account → Cash") }
                    )
                }
                Text("Account", style = MaterialTheme.typography.labelMedium)
                LazyColumn(modifier = Modifier.size(width = 280.dp, height = 160.dp)) {
                    items(accounts.filter { it.kind != Account.KIND_CASH && it.id != viewModel.accountId }) { acct ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = selectedId == acct.id,
                                onClick = { selectedId = acct.id }
                            )
                            Text("${acct.displayName} •• ${acct.accountMask}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = amount != null && amount > 0 && selectedId != null,
                onClick = {
                    viewModel.addTransfer(selectedId!!, fromCash, amount!!)
                    onDismiss()
                }
            ) { Text("Transfer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun produceAccounts(viewModel: AccountDetailViewModel): List<Account> {
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        accounts = viewModel.getAllAccountsOnce()
    }
    return accounts
}
