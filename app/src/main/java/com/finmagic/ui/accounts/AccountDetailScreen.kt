package com.finmagic.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    onBack: () -> Unit,
    viewModel: AccountDetailViewModel = hiltViewModel()
) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val accountBalance by viewModel.balance.collectAsStateWithLifecycle()
    val categoryNicknames by viewModel.categoryNicknames.collectAsStateWithLifecycle()
    val allAccountTypes by viewModel.allAccountTypes.collectAsStateWithLifecycle()
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
    var historyYear by rememberSaveable { mutableIntStateOf(nowCal.get(Calendar.YEAR)) }
    var historyMonth by rememberSaveable { mutableIntStateOf(nowCal.get(Calendar.MONTH)) }

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
                            Text(
                                text = String.format(Locale.US, "Tk %,.2f", accountBalance),
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
                                val curY = nowCal.get(Calendar.YEAR)
                                val curM = nowCal.get(Calendar.MONTH)
                                var m = historyMonth + 1
                                var y = historyYear
                                if (m > 11) { m = 0; y += 1 }
                                if (y < curY || (y == curY && m <= curM)) {
                                    historyMonth = m
                                    historyYear = y
                                }
                            },
                            nextEnabled = (historyYear < nowCal.get(Calendar.YEAR)) ||
                                (historyYear == nowCal.get(Calendar.YEAR) && historyMonth < nowCal.get(Calendar.MONTH))
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
            },
            onAddIncome = { amount, note, category, occurredAt ->
                viewModel.addCashEntry(AccountTransaction.DIRECTION_IN, amount, note, category, occurredAt)
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
            availableTypes = allAccountTypes,
            onDismiss = { showEditDialog = false },
            onConfirm = { newName, includeInTotal ->
                viewModel.renameAccount(newName)
                if (includeInTotal != account!!.includeInTotal) {
                    viewModel.setIncludeInTotal(includeInTotal)
                }
                showEditDialog = false
            },
            onConfirmDetails = { newName, accountType, isLiability, includeInTotal ->
                viewModel.updateAccountDetails(newName, accountType, isLiability, includeInTotal)
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
        AdjustCashBalanceDialog(
            currentBalance = accountBalance,
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
            alreadyIncome = false,
            onSetIncome = { include ->
                viewModel.setExpenseOverride(txn.id, if (include) 0 else -1)
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
            onDelete = {
                viewModel.deleteEntry(txn.id)
                editTxn = null
            }
        )
    }

    correctTxn?.let { txn ->
        val allAccounts by viewModel.allAccounts.collectAsStateWithLifecycle()
        CorrectTransactionDialog(
            txn = txn,
            accounts = allAccounts,
            onDismiss = { correctTxn = null },
            onConfirm = { direction, amount, accountId, category, counterparty, balanceAfter ->
                viewModel.correctTransaction(txn.id, direction, amount, accountId, category, counterparty, balanceAfter)
                correctTxn = null
            },
            onIgnore = {
                viewModel.ignoreTransaction(txn.id)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CashEntryDialog(
    expenseCategories: List<String>,
    incomeCategories: List<String>,
    onDismiss: () -> Unit,
    onAddExpense: (Double, String?, String?, Long) -> Unit,
    onAddIncome: (Double, String?, String?, Long) -> Unit,
    onTransfer: () -> Unit
) {
    var mode by remember { mutableStateOf("EXPENSE") }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var postedCount by remember { mutableIntStateOf(0) }
    var lastPostedMessage by remember { mutableStateOf<String?>(null) }
    var occurredAt by remember {
        mutableStateOf(Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis)
    }
    var showDatePicker by remember { mutableStateOf(false) }
    val initialUtcMillis = remember(occurredAt) {
        val local = Calendar.getInstance().apply { timeInMillis = occurredAt }
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialUtcMillis)
    val amount = amountText.replace(",", "").toDoubleOrNull()
    val canSubmit = amount != null && amount > 0
    val activeCategories = if (mode == "INCOME") incomeCategories else expenseCategories

    fun submitCurrent() {
        val value = amount ?: return
        val trimmedNote = note.ifBlank { null }
        if (mode == "EXPENSE") {
            onAddExpense(value, trimmedNote, category, occurredAt)
        } else {
            onAddIncome(value, trimmedNote, category, occurredAt)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Entry") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (postedCount > 0 && lastPostedMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = StatusSafe.copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = StatusSafe,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "$lastPostedMessage ($postedCount saved)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = StatusSafe
                            )
                        }
                    }
                }
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = mode == "EXPENSE",
                        onClick = { mode = "EXPENSE"; category = null },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text("Expense") }
                    SegmentedButton(
                        selected = mode == "INCOME",
                        onClick = { mode = "INCOME"; category = null },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text("Income") }
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(occurredAt)),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Date") },
                        trailingIcon = {
                            IconButton(onClick = { showDatePicker = true }) {
                                Icon(Icons.Default.CalendarMonth, contentDescription = "Pick date")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { showDatePicker = true }
                    )
                }
                Text("Category", style = MaterialTheme.typography.labelMedium)
                CategorySelector(
                    categories = activeCategories,
                    selected = category,
                    onSelected = { category = it }
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = canSubmit,
                    onClick = {
                        val value = amount ?: return@OutlinedButton
                        submitCurrent()
                        postedCount += 1
                        val label = if (mode == "EXPENSE") "Expense" else "Income"
                        lastPostedMessage = "$label Tk ${String.format(Locale.US, "%,.0f", value)} posted"
                        amountText = ""
                        note = ""
                    },
                    modifier = Modifier.testTag("cash_entry_continue_button")
                ) {
                    Text("Continue")
                }
                Button(
                    enabled = canSubmit,
                    onClick = {
                        submitCurrent()
                        onDismiss()
                    },
                    modifier = Modifier.testTag("cash_entry_confirm_button")
                ) {
                    Text("Confirm")
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onTransfer) { Text("Transfer") }
                TextButton(onClick = onDismiss) {
                    Text(if (postedCount > 0) "Done" else "Cancel")
                }
            }
        }
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { picked ->
                        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                            timeInMillis = picked
                        }
                        occurredAt = Calendar.getInstance().apply {
                            timeInMillis = occurredAt
                            set(Calendar.YEAR, utc.get(Calendar.YEAR))
                            set(Calendar.MONTH, utc.get(Calendar.MONTH))
                            set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
                            set(Calendar.HOUR_OF_DAY, 12)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferDialog(
    viewModel: AccountDetailViewModel,
    onDismiss: () -> Unit
) {
    val accounts = produceAccounts(viewModel)
    var amountText by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var fromCash by remember { mutableStateOf(true) }
    var occurredAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val initialUtcMillis = remember(occurredAt) {
        val local = Calendar.getInstance().apply { timeInMillis = occurredAt }
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialUtcMillis)
    val amount = amountText.replace(",", "").toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transfer Cash") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount") },
                    singleLine = true
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(occurredAt)),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Date") },
                        trailingIcon = {
                            IconButton(onClick = { showDatePicker = true }) {
                                Icon(Icons.Default.CalendarMonth, contentDescription = "Pick date")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { showDatePicker = true }
                    )
                }
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
                                .clickable { selectedId = acct.id }
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
                    viewModel.addTransfer(selectedId!!, fromCash, amount!!, occurredAt)
                    onDismiss()
                }
            ) { Text("Transfer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { picked ->
                        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                            timeInMillis = picked
                        }
                        occurredAt = Calendar.getInstance().apply {
                            timeInMillis = occurredAt
                            set(Calendar.YEAR, utc.get(Calendar.YEAR))
                            set(Calendar.MONTH, utc.get(Calendar.MONTH))
                            set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
                        }.timeInMillis
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun produceAccounts(viewModel: AccountDetailViewModel): List<Account> {
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        accounts = viewModel.getAllAccountsOnce()
    }
    return accounts
}
