package com.finmagic.ui.accounts

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finmagic.data.local.dao.AccountTransactionWithAccount
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountSummary
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.ui.theme.StatusSafe
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.rememberDatePickerState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private enum class BatchAccountAction {
    ARCHIVE,
    DELETE_PERMANENTLY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    initialTab: Int = 0,
    onTabSelected: (Int) -> Unit = {},
    onAccountClick: (Long) -> Unit,
    onCardClick: (Long) -> Unit = {},
    onNavigateToArchivedAccounts: () -> Unit = {},
    scrollToTopTrigger: Int = 0,
    showTopBar: Boolean = true,
    showPill: Boolean = true,
    viewModel: AccountsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val categoryNicknames by viewModel.categoryNicknames.collectAsStateWithLifecycle()
    val historySearchKeys by viewModel.historySearchKeys.collectAsStateWithLifecycle()
    val cashExpenseCategories by viewModel.cashExpenseCategories.collectAsStateWithLifecycle()
    val cashIncomeCategories by viewModel.cashIncomeCategories.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var showAddAccountDialog by remember { mutableStateOf(false) }
    var showManageTypesDialog by remember { mutableStateOf(false) }
    var detailTxn by remember { mutableStateOf<AccountTransactionWithAccount?>(null) }
    var editTxn by remember { mutableStateOf<AccountTransaction?>(null) }
    var selectedAccountIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pendingBatchAction by remember { mutableStateOf<BatchAccountAction?>(null) }
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }
    LaunchedEffect(initialTab) { selectedTab = initialTab }
    LaunchedEffect(selectedTab) {
        if (selectedTab != 1 && selectedAccountIds.isNotEmpty()) {
            selectedAccountIds = emptySet()
        }
    }
    LaunchedEffect(state.summaries) {
        val selectableIds = state.summaries
            .filter { it.account.kind != Account.KIND_CASH }
            .map { it.account.id }
            .toSet()
        if (selectedAccountIds.isNotEmpty()) {
            selectedAccountIds = selectedAccountIds.intersect(selectableIds)
        }
    }
    val isSelectionMode = selectedTab == 1 && selectedAccountIds.isNotEmpty()
    BackHandler(enabled = isSelectionMode) {
        selectedAccountIds = emptySet()
    }

    val listState = rememberLazyListState()
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0 && selectedTab == 0) {
            listState.animateScrollToItem(0)
        }
    }

    val pagerYear by viewModel.selectedYear.collectAsStateWithLifecycle()
    val pagerMonth by viewModel.selectedMonth.collectAsStateWithLifecycle()
    val nowCal = remember { Calendar.getInstance() }
    val nextEnabled = (pagerYear < nowCal.get(Calendar.YEAR)) ||
        (pagerYear == nowCal.get(Calendar.YEAR) && pagerMonth < nowCal.get(Calendar.MONTH))

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var categoryFilter by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.ensureCashAccount() }

    val headerMonthLabel = remember(pagerYear, pagerMonth) {
        SimpleDateFormat("MMMM yyyy", Locale.US).format(
            Calendar.getInstance().apply { set(pagerYear, pagerMonth, 1) }.time
        )
    }

    val monthTxns = remember(state.recentTransactions, pagerYear, pagerMonth) {
        val cal = Calendar.getInstance().apply {
            set(pagerYear, pagerMonth, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val end = cal.timeInMillis
        state.recentTransactions
            .filter { it.occurredAt >= start && it.occurredAt < end }
    }
    val monthCategories = remember(monthTxns) {
        monthTxns.mapNotNull { it.category }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val suggestedSearchKeys = remember(state.recentTransactions, historySearchKeys) {
        val savedLower = historySearchKeys.map { it.lowercase() }.toSet()
        buildList<String> {
            state.recentTransactions.forEach { txn ->
                listOfNotNull(txn.counterparty, txn.category, txn.accountName)
                    .map { it.trim() }
                    .filter { it.length in 2..24 && !savedLower.contains(it.lowercase()) }
                    .forEach { candidate ->
                        if (none { it.equals(candidate, ignoreCase = true) }) {
                            add(candidate)
                        }
                    }
            }
        }.take(6)
    }
    val filteredMonthTxns = remember(monthTxns, state.recentTransactions, searchQuery, categoryFilter) {
        val q = searchQuery.trim()
        val base = if (q.isEmpty()) monthTxns else state.recentTransactions
        base.filter { txn ->
            val matchesCategory = categoryFilter == null || categoryFilter == txn.category
            val matchesSearch = q.isEmpty() ||
                listOf(
                    txn.counterparty,
                    txn.category,
                    txn.accountName,
                    txn.smsBody,
                    String.format(Locale.US, "%.2f", txn.amount),
                    if (txn.direction == AccountTransaction.DIRECTION_IN) "credit in" else "debit out"
                ).any {
                    it != null && it.contains(q, ignoreCase = true)
                }
            matchesCategory && matchesSearch
        }
    }
    val monthGroups = remember(filteredMonthTxns) {
        filteredMonthTxns
            .groupBy { dayStart(it.occurredAt) }
            .toList()
            .sortedByDescending { it.first }
            .map { (dayStartMillis, txns) -> dayLabel(dayStartMillis) to txns }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = if (showTopBar) {
            {
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
        } else {
            {}
        },
        floatingActionButton = {
            if (showPill) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Entry")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (showPill) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    SegmentedButton(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0; onTabSelected(0) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text("History") }
                    SegmentedButton(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1; onTabSelected(1) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text("Accounts") }
                }
            }
            if (isSelectionMode) {
                val allSelectableIds = remember(state.summaries) {
                    state.summaries
                        .filter { it.account.kind != Account.KIND_CASH }
                        .map { it.account.id }
                        .toSet()
                }
                AccountsSelectionActionBar(
                    selectedCount = selectedAccountIds.size,
                    allSelected = allSelectableIds.isNotEmpty() && selectedAccountIds.size == allSelectableIds.size,
                    onClear = { selectedAccountIds = emptySet() },
                    onSelectAll = {
                        selectedAccountIds = if (selectedAccountIds.size == allSelectableIds.size) {
                            emptySet()
                        } else {
                            allSelectableIds
                        }
                    },
                    onArchive = { pendingBatchAction = BatchAccountAction.ARCHIVE },
                    onDeletePermanently = { pendingBatchAction = BatchAccountAction.DELETE_PERMANENTLY },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    top = 2.dp,
                    bottom = 76.dp
                ),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (selectedTab == 1) {
                    // ACCOUNTS TAB
                    item {
                        MonthPager(
                            year = pagerYear,
                            month = pagerMonth,
                            onPrevious = viewModel::stepPreviousMonth,
                            onNext = viewModel::stepNextMonth,
                            nextEnabled = nextEnabled
                        )
                    }

                    item {
                        TotalBalanceHeader(
                            totalBalance = state.totalBalance,
                            monthIn = state.monthIn,
                            monthOut = state.monthOut,
                            monthLabel = headerMonthLabel,
                            assetsTotal = state.assetsTotal,
                            liabilitiesTotal = state.accountLiabilitiesTotal + state.cardLiabilityTotal
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 0.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ACCOUNTS BY TYPE",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { showManageTypesDialog = true },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp).testTag("manage_account_types_button")
                                ) {
                                    Text("Types", style = MaterialTheme.typography.labelSmall)
                                }
                                FilledTonalButton(
                                    onClick = { showAddAccountDialog = true },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp).testTag("add_account_button")
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("Account", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    if (state.summaries.isNotEmpty()) {
                        val defaultTypeOrder = listOf(
                            Account.TYPE_CASH.lowercase(),
                            Account.TYPE_SAVINGS.lowercase(),
                            Account.TYPE_DEPOSIT.lowercase(),
                            Account.TYPE_LOAN.lowercase()
                        )
                        val typeConfigMap = state.allAccountTypes.associateBy { it.name.trim().lowercase() }
                        val grouped = state.summaries
                            .groupBy { it.account.effectiveType.trim().lowercase() }
                            .entries
                            .sortedWith(compareBy<Map.Entry<String, List<AccountSummary>>> {
                                val idx = defaultTypeOrder.indexOf(it.key)
                                if (idx >= 0) idx else 100
                            }.thenBy { it.key })

                        grouped.forEach { (typeKey, groupSummaries) ->
                            val typeName = typeConfigMap[typeKey]?.name
                                ?: groupSummaries.first().account.effectiveType
                            val isTypeLiability = typeConfigMap[typeKey]?.isLiability
                                ?: groupSummaries.all { it.account.isLiability }
                            val includedSummaries = groupSummaries.filter { it.account.includeInTotal }
                            val excludedCount = groupSummaries.size - includedSummaries.size
                            val typeTotal = if (isTypeLiability) {
                                includedSummaries.sumOf { kotlin.math.abs(it.displayBalance) }
                            } else {
                                includedSummaries.sumOf {
                                    if (it.account.isLiability) -kotlin.math.abs(it.displayBalance) else it.displayBalance
                                }
                            }
                            item(key = "type_header_$typeKey") {
                                AccountTypeGroupHeader(
                                    typeName = typeName,
                                    typeTotal = typeTotal,
                                    count = groupSummaries.size,
                                    excludedCount = excludedCount,
                                    isLiability = isTypeLiability
                                )
                            }
                            items(
                                groupSummaries.sortedByDescending { it.displayBalance },
                                key = { "account_${it.account.id}" }
                            ) { summary ->
                                val acctId = summary.account.id
                                val selectable = summary.account.kind != Account.KIND_CASH
                                val isSelected = acctId in selectedAccountIds
                                AccountCard(
                                    summary = summary,
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
                                    selectionMode = isSelectionMode,
                                    isSelected = isSelected,
                                    selectable = selectable,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }
                        }
                    } else {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No accounts yet.\nTap + Account or ledger SMS will create them automatically.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    if (state.cardOutstandingItems.isNotEmpty() || state.cardLiabilityTotal > 0.0) {
                        item(key = "cards_liability_section") {
                            CardsLiabilitySummaryCard(
                                cardTotal = state.cardLiabilityTotal,
                                items = state.cardOutstandingItems,
                                onCardClick = onCardClick,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (state.archivedAccounts.isNotEmpty()) {
                        item(key = "archived_accounts_nav_card") {
                            ArchivedAccountsNavCard(
                                count = state.archivedAccounts.size,
                                onClick = onNavigateToArchivedAccounts,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                } else {
                    // HISTORY TAB
                    item {
                        MonthPager(
                            year = pagerYear,
                            month = pagerMonth,
                            onPrevious = viewModel::stepPreviousMonth,
                            onNext = viewModel::stepNextMonth,
                            nextEnabled = nextEnabled
                        )
                    }
                    item {
                        TotalBalanceHeader(
                            totalBalance = state.totalBalance,
                            monthIn = state.monthIn,
                            monthOut = state.monthOut,
                            monthLabel = headerMonthLabel,
                            assetsTotal = state.assetsTotal,
                            liabilitiesTotal = state.accountLiabilitiesTotal + state.cardLiabilityTotal
                        )
                    }

                    item {
                        val trimmedQuery = searchQuery.trim()
                        val canSaveSearchKey = trimmedQuery.isNotEmpty() &&
                            historySearchKeys.none { it.equals(trimmedQuery, ignoreCase = true) }
                        SearchField(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onSaveKey = if (canSaveSearchKey) {
                                { viewModel.addHistorySearchKey(trimmedQuery) }
                            } else null,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    if (historySearchKeys.isNotEmpty() || suggestedSearchKeys.isNotEmpty()) {
                        item(key = "history_search_keys_row") {
                            HistorySearchKeyListRow(
                                savedKeys = historySearchKeys,
                                suggestedKeys = suggestedSearchKeys,
                                activeQuery = searchQuery,
                                onSelectKey = { key ->
                                    searchQuery = if (searchQuery.trim().equals(key, ignoreCase = true)) "" else key
                                },
                                onRemoveKey = { key -> viewModel.removeHistorySearchKey(key) }
                            )
                        }
                    }
                    if (monthCategories.isNotEmpty()) {
                        item {
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                item {
                                    FilterChip(
                                        selected = categoryFilter == null,
                                        onClick = { categoryFilter = null },
                                        label = { Text("All") }
                                    )
                                }
                                items(monthCategories) { cat ->
                                    FilterChip(
                                        selected = categoryFilter == cat,
                                        onClick = { categoryFilter = if (categoryFilter == cat) null else cat },
                                        label = { Text(categoryLabel(cat, categoryNicknames)) }
                                    )
                                }
                            }
                        }
                    }
                    if (monthGroups.isNotEmpty()) {
                        item {
                            Text(
                                text = "RECENT TRANSACTIONS",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp).padding(top = 3.dp, bottom = 0.dp)
                            )
                        }
                        monthGroups.forEach { (day, txns) ->
                            item(key = "day_$day") {
                                Text(
                                    text = day,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp).padding(top = 3.dp, bottom = 0.dp)
                                )
                            }
                            items(txns, key = { "txn_${it.id}" }) { txnWithAcct ->
                                val txn = txnWithAcct.toTransaction()
                                Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                                    TxnRow(
                                        txn = txn,
                                        balanceTrail = state.balanceTrails[txn.id],
                                        accountName = txnWithAcct.accountName,
                                        categoryNicknames = categoryNicknames,
                                        onTap = { detailTxn = txnWithAcct },
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
                                    text = if (searchQuery.isBlank() && categoryFilter == null)
                                        "No transactions found for this month."
                                    else
                                        "No matching transactions.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddAccountDialog) {
        AddAccountDialog(
            availableTypes = state.allAccountTypes,
            onDismiss = { showAddAccountDialog = false },
            onCreateCustomType = { typeName, isLiab ->
                viewModel.addCustomAccountType(typeName, isLiab)
            },
            onConfirm = { name, accountType, isLiability, bankName, accountMask, initialBalance, includeInTotal ->
                viewModel.createAccount(
                    name = name,
                    accountType = accountType,
                    isLiability = isLiability,
                    bankName = bankName,
                    accountMask = accountMask,
                    initialBalance = initialBalance,
                    includeInTotal = includeInTotal
                )
                showAddAccountDialog = false
            }
        )
    }

    if (showManageTypesDialog) {
        ManageAccountTypesDialog(
            allTypes = state.allAccountTypes,
            customTypes = state.customAccountTypes,
            onDismiss = { showManageTypesDialog = false },
            onAddType = { typeName, isLiab ->
                viewModel.addCustomAccountType(typeName, isLiab)
            },
            onDeleteCustomType = { typeName ->
                viewModel.removeCustomAccountType(typeName)
            }
        )
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
            alreadyIncome = com.finmagic.data.sms.parsers.TxnCategory.countsAsIncome(
                txn.isExpenseOverride, txn.direction
            ),
            onSetIncome = { include ->
                viewModel.setExpenseOverride(
                    txn.id,
                    if (include) AccountTransaction.EXPENSE_INCLUDED else AccountTransaction.EXPENSE_AUTO
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
            onDelete = {
                viewModel.deleteTransaction(txn.id)
                editTxn = null
            }
        )
    }

    pendingBatchAction?.let { action ->
        BatchAccountsActionDialog(
            action = action,
            selectedCount = selectedAccountIds.size,
            onDismiss = { pendingBatchAction = null },
            onConfirm = {
                val ids = selectedAccountIds.toList()
                when (action) {
                    BatchAccountAction.ARCHIVE -> viewModel.archiveAccounts(ids)
                    BatchAccountAction.DELETE_PERMANENTLY -> viewModel.deleteAccountsPermanentlyWithSms(ids)
                }
                selectedAccountIds = emptySet()
                pendingBatchAction = null
            }
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
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 0.dp),
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
@OptIn(ExperimentalMaterial3Api::class)
internal fun AddEntryDialog(
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
    var occurredAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val initialUtcMillis = remember(occurredAt) {
        val local = Calendar.getInstance().apply { timeInMillis = occurredAt }
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialUtcMillis)

    // For Transfer
    var targetAccountId by remember { mutableStateOf<Long?>(null) }
    var creditItems by remember { mutableStateOf<List<AccountTransaction>>(emptyList()) }
    var selectedCreditId by remember { mutableStateOf<Long?>(null) }

    var postedCount by remember { mutableIntStateOf(0) }
    var lastPostedMessage by remember { mutableStateOf<String?>(null) }

    val canSubmit = amount > 0 && (mode != "TRANSFER" || targetAccountId != null)

    fun submitCurrentEntry() {
        val enteredAmount = amount
        val enteredNote = note.ifBlank { null }
        val enteredCategory = category
        val enteredDate = occurredAt
        val enteredTargetId = targetAccountId
        val enteredCreditId = selectedCreditId
        when (mode) {
            "EXPENSE" -> viewModel.addCashExpense(enteredAmount, enteredNote, enteredCategory, enteredDate)
            "INCOME" -> viewModel.addCashIncome(enteredAmount, enteredNote, enteredCategory, enteredDate)
            "TRANSFER" -> if (enteredTargetId != null) {
                viewModel.addManualTransfer(enteredTargetId, enteredAmount, enteredCreditId, enteredDate)
            }
        }
    }

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
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
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
                                text = "${lastPostedMessage} ($postedCount saved)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = StatusSafe
                            )
                        }
                    }
                }
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )

                val dateText = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(occurredAt))
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Date") },
                        trailingIcon = {
                            IconButton(onClick = { showDatePicker = true }) {
                                Icon(Icons.Default.CalendarMonth, contentDescription = "Pick date")
                            }
                        },
                        placeholder = { Text("Tap to pick") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { showDatePicker = true }
                    )
                }

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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = canSubmit,
                    onClick = {
                        val label = when (mode) {
                            "EXPENSE" -> "Expense"
                            "INCOME" -> "Income"
                            else -> "Transfer"
                        }
                        val savedAmount = amount
                        submitCurrentEntry()
                        postedCount += 1
                        lastPostedMessage = "$label Tk ${String.format(Locale.US, "%,.0f", savedAmount)} posted"
                        amountText = ""
                        note = ""
                        selectedCreditId = null
                    },
                    modifier = Modifier.testTag("add_entry_continue_button")
                ) {
                    Text("Continue")
                }
                Button(
                    enabled = canSubmit,
                    onClick = {
                        submitCurrentEntry()
                        onDismiss()
                    },
                    modifier = Modifier.testTag("add_entry_confirm_button")
                ) {
                    Text("Confirm")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("add_entry_cancel_button")
            ) {
                Text(if (postedCount > 0) "Done" else "Cancel")
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
                        val original = Calendar.getInstance().apply { timeInMillis = occurredAt }
                        occurredAt = Calendar.getInstance().apply {
                            set(Calendar.YEAR, utc.get(Calendar.YEAR))
                            set(Calendar.MONTH, utc.get(Calendar.MONTH))
                            set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
                            set(Calendar.HOUR_OF_DAY, original.get(Calendar.HOUR_OF_DAY))
                            set(Calendar.MINUTE, original.get(Calendar.MINUTE))
                            set(Calendar.SECOND, original.get(Calendar.SECOND))
                            set(Calendar.MILLISECOND, original.get(Calendar.MILLISECOND))
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
internal fun TotalBalanceHeader(
    totalBalance: Double,
    monthIn: Double,
    monthOut: Double,
    monthLabel: String,
    assetsTotal: Double = totalBalance,
    liabilitiesTotal: Double = 0.0
) {
    val net = monthIn - monthOut
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (liabilitiesTotal > 0.0) "Net Worth" else "Total Balance",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                )
                Text(
                    text = monthLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f),
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                text = String.format(Locale.US, "Tk %,.2f", totalBalance),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (totalBalance < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (liabilitiesTotal > 0.0) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Assets: ${formatTkAmount(assetsTotal)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusSafe,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Liabilities: −${formatTkAmount(liabilitiesTotal)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "In",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusSafe,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = String.format(Locale.US, "Tk %,.0f", monthIn),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Out",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = String.format(Locale.US, "Tk %,.0f", monthOut),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Net",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (net >= 0) StatusSafe else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = String.format(Locale.US, "Tk %,.0f", net),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
internal fun ExpenseBreakdownCard(
    expenses: List<com.finmagic.data.local.dao.CategoryTotal>,
    income: List<com.finmagic.data.local.dao.CategoryTotal>,
    monthLabel: String,
    categoryNicknames: Map<String, String> = emptyMap()
) {
    val totalOut = expenses.sumOf { it.total }.takeIf { it > 0.0 } ?: 0.0
    val totalIn = income.sumOf { it.total }.takeIf { it > 0.0 } ?: 0.0

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
                        text = monthLabel,
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

            if (totalOut > 0) {
                Text(
                    text = "Spending",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                expenses.take(5).forEach { entry ->
                    BreakdownBar(
                        label = categoryLabel(entry.category, categoryNicknames),
                        total = entry.total,
                        baseTotal = totalOut,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (expenses.size > 5) {
                    Text(
                        text = "+ ${expenses.size - 5} more",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp).align(Alignment.CenterHorizontally)
                    )
                }
            }

            if (totalIn > 0 && totalOut > 0) {
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (totalIn > 0) {
                Text(
                    text = "Income",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = StatusSafe
                )
                income.take(3).forEach { entry ->
                    BreakdownBar(
                        label = categoryLabel(entry.category, categoryNicknames),
                        total = entry.total,
                        baseTotal = totalIn,
                        color = StatusSafe
                    )
                }
                if (income.size > 3) {
                    Text(
                        text = "+ ${income.size - 3} more",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp).align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}

@Composable
private fun BreakdownBar(
    label: String,
    total: Double,
    baseTotal: Double,
    color: Color
) {
    val share = if (baseTotal > 0) (total / baseTotal).toFloat() else 0f

    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color.copy(alpha = share.coerceIn(0.3f, 1f)), CircleShape)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = String.format(Locale.US, "Tk %,.2f", total),
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

fun accountIcon(kind: String): ImageVector = when (kind) {
    Account.KIND_BANK -> Icons.Filled.AccountBalance
    Account.KIND_WALLET -> Icons.Filled.AccountBalanceWallet
    else -> Icons.Filled.Payments
}

internal fun formatTkAmount(amount: Double): String {
    val absVal = kotlin.math.abs(amount)
    val hasCents = kotlin.math.abs(absVal - kotlin.math.round(absVal)) >= 0.005
    return if (hasCents) {
        String.format(Locale.US, "Tk %,.2f", absVal)
    } else {
        String.format(Locale.US, "Tk %,.0f", absVal)
    }
}

@Composable
private fun AccountTypeGroupHeader(
    typeName: String,
    typeTotal: Double,
    count: Int,
    excludedCount: Int = 0,
    isLiability: Boolean
) {
    val countText = if (excludedCount > 0) "$count · $excludedCount excl." else "$count"
    val isNegativeDisplay = if (isLiability) typeTotal > 0.005 else typeTotal < -0.005
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 3.dp, bottom = 0.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "${typeName.uppercase()} ($countText)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (isLiability) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            if (isLiability) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = "LIABILITY",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
        }
        Text(
            text = (if (isNegativeDisplay) "−" else "") + formatTkAmount(typeTotal),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (isLiability || typeTotal < -0.005) MaterialTheme.colorScheme.error else StatusSafe
        )
    }
}

@Composable
private fun CardsLiabilitySummaryCard(
    cardTotal: Double,
    items: List<CardOutstandingItem>,
    onCardClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CreditCard,
                        contentDescription = "Credit Card Liability",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "CARD TOTAL",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = "LIABILITY",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                Text(
                    text = String.format(Locale.US, "−Tk %,.0f", cardTotal),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("accounts_card_total_liability")
                )
            }
            if (items.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
                items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCardClick(item.card.id) }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.card.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = when {
                                    item.unpaidBillCount > 0 -> String.format(
                                        Locale.US,
                                        "%d unpaid bill%s · Bill Tk %,.0f",
                                        item.unpaidBillCount,
                                        if (item.unpaidBillCount == 1) "" else "s",
                                        item.unpaidBillAmount
                                    )
                                    item.unpaidAmount > 0.0 -> "No unpaid bills · Unbilled outstanding"
                                    else -> "All bills paid"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = String.format(Locale.US, "Tk %,.0f", item.unpaidAmount),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (item.unpaidAmount > 0.0) MaterialTheme.colorScheme.error else StatusSafe
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AccountCard(
    summary: AccountSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    selectable: Boolean = true
) {
    val account = summary.account
    val balance = summary.displayBalance
    val isLiability = account.isLiability
    val haptic = LocalHapticFeedback.current

    val kindLabel = buildString {
        append(account.effectiveType)
        if (isLiability) append(" · Liability")
        if (!account.includeInTotal) append(" · Excluded")
        if (selectionMode && !selectable) append(" · Built-in")
    }

    val containerColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        selectionMode && !selectable -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    if (onLongClick != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                }
            )
            .testTag("account_card_${account.id}"),
        shape = RoundedCornerShape(14.dp),
        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (selectionMode && selectable) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.testTag("account_select_checkbox_${account.id}")
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (isLiability) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = accountIcon(account.kind),
                        contentDescription = kindLabel,
                        tint = if (isLiability) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = kindLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isLiability) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val isNegativeDisplay = if (isLiability) balance > 0.005 else balance < -0.005
            Text(
                text = (if (isNegativeDisplay) "−" else "") + formatTkAmount(balance),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isLiability || balance < -0.005) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun AccountsSelectionActionBar(
    selectedCount: Int,
    allSelected: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onArchive: () -> Unit,
    onDeletePermanently: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("accounts_selection_bar"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClear,
                modifier = Modifier.testTag("accounts_clear_selection")
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Clear selection")
            }
            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onSelectAll,
                modifier = Modifier.testTag("accounts_select_all")
            ) {
                Icon(
                    imageVector = if (allSelected) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                    contentDescription = if (allSelected) "Deselect all" else "Select all"
                )
            }
            FilledTonalButton(
                onClick = onArchive,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("accounts_batch_archive_button")
            ) {
                Icon(Icons.Filled.Inventory2, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Archive", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onDeletePermanently,
                modifier = Modifier.testTag("accounts_batch_delete_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteForever,
                    contentDescription = "Delete selected permanently",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ArchivedAccountsNavCard(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("open_archived_accounts_button"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Inventory2,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Archived Accounts ($count)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Manage stored SMS, restore, or delete permanently",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "Open Archived Accounts",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BatchAccountsActionDialog(
    action: BatchAccountAction,
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val isDelete = action == BatchAccountAction.DELETE_PERMANENTLY
    val noun = if (selectedCount == 1) "account" else "accounts"
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (isDelete) Icons.Filled.DeleteForever else Icons.Filled.Inventory2,
                contentDescription = null,
                tint = if (isDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                if (isDelete) "Delete $selectedCount $noun permanently?"
                else "Archive $selectedCount $noun?"
            )
        },
        text = {
            Text(
                if (isDelete) {
                    "This permanently deletes the selected $noun, their transactions, and their stored SMS messages in FinMagic. You cannot undo this."
                } else {
                    "Selected $noun and their transactions will be moved to Archived Accounts so SMS rescans will not recreate them. You can restore them or clear SMS later."
                }
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
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
                    .testTag(if (isDelete) "confirm_batch_delete_accounts" else "confirm_batch_archive_accounts")
            ) {
                Text(if (isDelete) "Delete Permanently" else "Archive")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun HistorySearchKeyListRow(
    savedKeys: List<String>,
    suggestedKeys: List<String>,
    activeQuery: String,
    onSelectKey: (String) -> Unit,
    onRemoveKey: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = "SEARCH KEY LIST",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("history_search_key_list"),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(savedKeys, key = { "saved_key_${it.lowercase()}" }) { key ->
                val isSelected = activeQuery.trim().equals(key, ignoreCase = true)
                InputChip(
                    selected = isSelected,
                    onClick = { onSelectKey(key) },
                    label = { Text(key, style = MaterialTheme.typography.labelSmall) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Remove $key from search key list",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onRemoveKey(key) }
                                .testTag("history_remove_search_key_${key.lowercase()}")
                        )
                    },
                    modifier = Modifier.testTag("history_search_key_chip_${key.lowercase()}")
                )
            }
            items(suggestedKeys, key = { "suggested_key_${it.lowercase()}" }) { key ->
                val isSelected = activeQuery.trim().equals(key, ignoreCase = true)
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectKey(key) },
                    label = { Text(key, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("history_search_key_chip_${key.lowercase()}")
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSaveKey: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .testTag("history_search_input"),
        placeholder = { Text("Search merchant, account, SMS, amount…", style = MaterialTheme.typography.bodySmall, maxLines = 1) },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        textStyle = MaterialTheme.typography.bodySmall,
        leadingIcon = {
            Icon(Icons.Filled.Search, contentDescription = "Search", modifier = Modifier.size(18.dp))
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onSaveKey != null) {
                    IconButton(
                        onClick = onSaveKey,
                        modifier = Modifier.testTag("history_save_search_key_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BookmarkAdd,
                            contentDescription = "Save to Search Key List",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.testTag("history_search_clear_button")
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            }
        }
    )
}
