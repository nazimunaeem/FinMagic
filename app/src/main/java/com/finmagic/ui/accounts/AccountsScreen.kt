package com.finmagic.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finmagic.data.local.dao.AccountTransactionWithAccount
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.ui.theme.StatusSafe
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    onAccountClick: (Long) -> Unit,
    viewModel: AccountsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val categoryNicknames by viewModel.categoryNicknames.collectAsStateWithLifecycle()
    val settingsViewModel: com.finmagic.ui.settings.SettingsViewModel = hiltViewModel()
    val cashExpenseCategories by settingsViewModel.cashExpenseCategories.collectAsStateWithLifecycle()
    val cashIncomeCategories by settingsViewModel.cashIncomeCategories.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var detailTxn by remember { mutableStateOf<AccountTransactionWithAccount?>(null) }
    var editTxn by remember { mutableStateOf<AccountTransaction?>(null) }
    // Not saveable on purpose: re-entering the section always lands on History.
    var selectedTab by remember { mutableIntStateOf(0) } // 0: History, 1: Accounts

    // History-tab month paging (month is 0-based).
    val nowCal = remember { Calendar.getInstance() }
    var historyYear by rememberSaveable { mutableIntStateOf(nowCal.get(Calendar.YEAR)) }
    var historyMonth by rememberSaveable { mutableIntStateOf(nowCal.get(Calendar.MONTH)) }

    val monthGroups = remember(state.recentTransactions, historyYear, historyMonth) {
        val cal = Calendar.getInstance().apply {
            set(historyYear, historyMonth, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val end = cal.timeInMillis
        state.recentTransactions
            .filter { it.occurredAt >= start && it.occurredAt < end }
            .groupBy { dayStart(it.occurredAt) }
            .toList()
            .sortedByDescending { it.first }
            .map { (dayStartMillis, txns) -> dayLabel(dayStartMillis) to txns }
    }

    LaunchedEffect(Unit) { viewModel.ensureCashAccount() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Accounts",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = 120.dp + padding.calculateBottomPadding()
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // Header (visible in both tabs)
                item {
                    TotalBalanceHeader(
                        totalBalance = state.totalBalance,
                        monthIn = state.monthIn,
                        monthOut = state.monthOut
                    )
                }

                if (selectedTab == 1) {
                    // ACCOUNTS TAB
                    if (state.expensesByCategory.isNotEmpty()) {
                        item {
                                ExpenseBreakdownCard(state.expensesByCategory, categoryNicknames)
                        }
                    }

                    if (state.summaries.isNotEmpty()) {
                        item {
                            Text(
                                text = "MY ACCOUNTS",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(state.summaries, key = { "account_${it.account.id}" }) { summary ->
                            AccountCard(
                                account = summary.account,
                                balance = summary.displayBalance,
                                onClick = { onAccountClick(summary.account.id) },
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    } else {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No accounts yet.\nLedger SMS will create them automatically.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    // HISTORY TAB
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
                    if (monthGroups.isNotEmpty()) {
                        item {
                            Text(
                                text = "RECENT TRANSACTIONS",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 4.dp)
                            )
                        }
                        monthGroups.forEach { (day, txns) ->
                            item(key = "day_$day") {
                                Text(
                                    text = day,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 4.dp)
                                )
                            }
                            items(txns, key = { "txn_${it.id}" }) { txnWithAcct ->
                                val txn = txnWithAcct.toTransaction()
                                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                                        TxnRow(
                                            txn = txn,
                                            accountName = txnWithAcct.accountName,
                                            categoryNicknames = categoryNicknames,
                                            onTap = { detailTxn = txnWithAcct },
                                        onDelete = if (txn.isManual || txn.category == com.finmagic.data.sms.parsers.TxnCategory.ADJUSTMENT) {
                                            { viewModel.deleteTransaction(txn.id) }
                                        } else null,
                                        onUpdateCategory = { newCat ->
                                            viewModel.updateTransactionCategory(txn.id, newCat)
                                        }
                                    )
                                }
                            }
                        }
                    } else {
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
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            // Floating Pill Tab
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .zIndex(1f),
                shape = RoundedCornerShape(50.dp),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // History Icon
                    PillTabItem(
                        selected = selectedTab == 0,
                        icon = if (selectedTab == 0) Icons.Filled.History else Icons.Outlined.History,
                        onClick = { selectedTab = 0 }
                    )

                    // Accounts Icon
                    PillTabItem(
                        selected = selectedTab == 1,
                        icon = if (selectedTab == 1) Icons.Filled.AccountBalance else Icons.Outlined.AccountBalance,
                        onClick = { selectedTab = 1 }
                    )

                    // Add Icon (The Fab in the Pill)
                    FloatingActionButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Entry")
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddEntryDialog(
            viewModel = viewModel,
            summaries = state.summaries,
            expenseCategories = cashExpenseCategories,
            incomeCategories = cashIncomeCategories,
            onDismiss = { showAddDialog = false }
        )
    }

    detailTxn?.let { twA ->
        val txn = twA.toTransaction()
        TxnDetailDialog(
            txn = txn,
            accountName = twA.accountName,
            onDismiss = { detailTxn = null },
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
                detailTxn = null
            },
            onEditDetails = {
                detailTxn = null
                editTxn = txn
            }
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
                    viewModel.deleteTransaction(txn.id)
                    editTxn = null
                }
            } else null
        )
    }
}

/** Month pager for the History tab: ‹ MMMM yyyy ›, next disabled at the current month. */
@Composable
fun MonthPager(
    year: Int,
    month: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    nextEnabled: Boolean
) {
    val label = SimpleDateFormat("MMMM yyyy", Locale.US).format(
        Calendar.getInstance().apply { set(year, month, 1) }.time
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(onClick = onNext, enabled = nextEnabled) {
                Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Next month")
            }
        }
    }
}

@Composable
private fun PillTabItem(
    selected: Boolean,
    icon: ImageVector,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                CircleShape
            )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AddEntryDialog(
    viewModel: AccountsViewModel,
    summaries: List<com.finmagic.data.local.entity.AccountSummary>,
    expenseCategories: List<String>,
    incomeCategories: List<String>,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf("EXPENSE") } // EXPENSE, INCOME, TRANSFER
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    val amount = amountText.replace(",", "").toDoubleOrNull() ?: 0.0
    val activeCategories = if (mode == "INCOME") incomeCategories else expenseCategories

    // For Transfer
    var targetAccountId by remember { mutableStateOf<Long?>(null) }
    var creditItems by remember { mutableStateOf<List<AccountTransaction>>(emptyList()) }
    var selectedCreditId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(mode) { category = null }

    LaunchedEffect(mode, targetAccountId, amount) {
        if (mode == "TRANSFER" && targetAccountId != null && amount > 0) {
            creditItems = viewModel.findPotentialCreditItems(targetAccountId!!, amount)
            if (creditItems.isEmpty()) selectedCreditId = null
        } else {
            creditItems = emptyList()
            selectedCreditId = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Mode Selector
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = mode == "EXPENSE",
                        onClick = { mode = "EXPENSE" },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                    ) { Text("Expense") }
                    SegmentedButton(
                        selected = mode == "INCOME",
                        onClick = { mode = "INCOME" },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                    ) { Text("Income") }
                    SegmentedButton(
                        selected = mode == "TRANSFER",
                        onClick = { mode = "TRANSFER" },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                    ) { Text("Transfer") }
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount") },
                    singleLine = true,
                    placeholder = { Text("0.00") },
                    modifier = Modifier.fillMaxWidth()
                )

                if (mode == "EXPENSE" || mode == "INCOME") {
                    Text("Category", style = MaterialTheme.typography.labelMedium)
                    CategorySelector(
                        categories = activeCategories,
                        selected = category,
                        onSelected = { category = it }
                    )
                }

                if (mode == "TRANSFER") {
                    Text("Transfer Cash to:", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(summaries.filter { it.account.kind != Account.KIND_CASH }) { summary ->
                            FilterChip(
                                selected = targetAccountId == summary.account.id,
                                onClick = { targetAccountId = summary.account.id },
                                label = { Text(summary.account.displayName) }
                            )
                        }
                    }

                    if (creditItems.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Matching SMS found in target account. Link it?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Column {
                            creditItems.forEach { item ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = selectedCreditId == item.id, onClick = { selectedCreditId = item.id })
                                    Text("SMS: Tk ${item.amount} (${dayLabel(item.occurredAt)})", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selectedCreditId == null, onClick = { selectedCreditId = null })
                                Text("Auto Entry (No SMS)", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

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
            TextButton(
                enabled = amount > 0 && (mode != "TRANSFER" || targetAccountId != null),
                onClick = {
                    when (mode) {
                        "EXPENSE" -> viewModel.addCashExpense(amount, note.ifBlank { null }, category)
                        "INCOME" -> viewModel.addCashIncome(amount, note.ifBlank { null }, category)
                        "TRANSFER" -> viewModel.addManualTransfer(targetAccountId!!, amount, selectedCreditId)
                    }
                    onDismiss()
                }
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun TotalBalanceHeader(totalBalance: Double, monthIn: Double, monthOut: Double) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Total Balance",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Text(
                text = String.format(Locale.US, "Tk %,.2f", totalBalance),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row {
                FlowBadge(label = "In", value = monthIn, color = StatusSafe)
                Spacer(modifier = Modifier.width(16.dp))
                FlowBadge(label = "Out", value = monthOut, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun FlowBadge(label: String, value: Double, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = String.format(Locale.US, "Tk %,.2f", value),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun ExpenseBreakdownCard(
    expenses: List<com.finmagic.data.local.dao.CategoryTotal>,
    categoryNicknames: Map<String, String> = emptyMap()
) {
    val totalOut = expenses.filter { it.category != "Credit" }.sumOf { it.total }.takeIf { it > 0.0 } ?: 0.0
    val totalIn = expenses.filter { it.category == "Credit" }.sumOf { it.total }.takeIf { it > 0.0 } ?: 0.0

    if (totalOut <= 0.0 && totalIn <= 0.0) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Monthly Summary",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "This Month",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (totalIn > 0) {
                        Text(
                            text = String.format(Locale.US, "+ Tk %,.2f", totalIn),
                            style = MaterialTheme.typography.titleMedium,
                            color = StatusSafe,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (totalOut > 0) {
                        Text(
                            text = String.format(Locale.US, "− Tk %,.2f", totalOut),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            
            val displayCategories = expenses.take(5)
            displayCategories.forEach { entry ->
                val isCredit = entry.category == "Credit"
                val baseTotal = if (isCredit) totalIn else totalOut
                val share = if (baseTotal > 0) (entry.total / baseTotal).toFloat() else 0f
                val color = if (isCredit) StatusSafe else MaterialTheme.colorScheme.primary

                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(color.copy(alpha = share.coerceIn(0.3f, 1f)), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = categoryLabel(entry.category, categoryNicknames),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = String.format(Locale.US, "Tk %,.2f", entry.total),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { share },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = color,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
            if (expenses.size > 5) {
                Text(
                    text = "+ ${expenses.size - 5} more",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

fun accountIcon(kind: String): ImageVector = when (kind) {
    Account.KIND_BANK -> Icons.Filled.AccountBalance
    Account.KIND_WALLET -> Icons.Filled.AccountBalanceWallet
    else -> Icons.Filled.Payments
}

@Composable
private fun AccountCard(account: Account, balance: Double, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = accountIcon(account.kind),
                contentDescription = account.kind,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                if (account.kind != Account.KIND_CASH &&
                    !account.displayName.contains(account.accountMask)
                ) {
                    Text(
                        text = "•• ${account.accountMask}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = String.format(Locale.US, "Tk %,.2f", balance),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = StatusSafe
            )
        }
    }
}
