package com.finmagic.ui.creditcards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finmagic.data.local.entity.CardPayment
import com.finmagic.data.local.entity.CardTransaction
import com.finmagic.data.local.entity.CreditCard
import com.finmagic.ui.components.BillCard
import com.finmagic.ui.components.SectionHeader
import com.finmagic.ui.theme.StatusSafe
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailScreen(
    onBack: () -> Unit,
    viewModel: CardDetailViewModel = hiltViewModel()
) {
    val card by viewModel.card.collectAsStateWithLifecycle()
    val bills by viewModel.bills.collectAsStateWithLifecycle()
    val billedTotals by viewModel.billedTotals.collectAsStateWithLifecycle()
    val paidTotals by viewModel.paidTotals.collectAsStateWithLifecycle()
    val unpaidTotals by viewModel.unpaidTotals.collectAsStateWithLifecycle()
    val outstanding by viewModel.outstanding.collectAsStateWithLifecycle()
    val payments by viewModel.payments.collectAsStateWithLifecycle()
    val cardTransactions by viewModel.cardTransactions.collectAsStateWithLifecycle()
    val spentInPeriod by viewModel.spentInPeriod.collectAsStateWithLifecycle()
    val periodMode by viewModel.periodMode.collectAsStateWithLifecycle()
    val selectedYear by viewModel.selectedYear.collectAsStateWithLifecycle()
    val selectedMonth by viewModel.selectedMonth.collectAsStateWithLifecycle()
    val canStepNext by viewModel.canStepNext.collectAsStateWithLifecycle()
    val expenseCategories by viewModel.expenseCategories.collectAsStateWithLifecycle()

    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showAddTxnDialog by remember { mutableStateOf(false) }

    val unpaidBills = remember(bills) {
        bills.filter { !it.isPaid && !it.isSuperseded }.sortedBy { billHistoryTimestamp(it) }
    }
    val paidBills = remember(bills) {
        bills.filter { it.isPaid }.sortedByDescending { billHistoryTimestamp(it) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = card?.displayName ?: "Credit Card",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        card?.let {
                            Text(
                                text = "•••• ${it.cardLast4} • ${it.bankName}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("card_detail_back")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showEditDialog = true },
                        modifier = Modifier.testTag("card_detail_edit")
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit Card")
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        },
        floatingActionButton = {
            if (card != null) {
                ExtendedFloatingActionButton(
                    onClick = { showAddTxnDialog = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = "Add card spend") },
                    text = { Text("Add Spend") },
                    modifier = Modifier.testTag("card_detail_add_spend")
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(top = 2.dp, bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // Summary Header Card
            item {
                CardSummaryHeader(
                    card = card,
                    outstanding = outstanding,
                    billedTotals = billedTotals,
                    paidTotals = paidTotals,
                    unpaidTotals = unpaidTotals,
                    spentAmount = spentInPeriod,
                    transactionCount = cardTransactions.size,
                    totalBills = bills.count { !it.isSuperseded },
                    periodLabel = formatCardPeriodLabel(periodMode, selectedYear, selectedMonth)
                )
            }

            // Period Selector (All / Month / Year)
            item {
                CardPeriodSelector(
                    mode = periodMode,
                    selectedYear = selectedYear,
                    selectedMonth = selectedMonth,
                    canStepNext = canStepNext,
                    onModeSelected = { viewModel.setPeriodMode(it) },
                    onPrevious = { viewModel.stepPreviousPeriod() },
                    onNext = { viewModel.stepNextPeriod() },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            // 1. Unpaid Active Bills FIRST
            if (unpaidBills.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "UNPAID BILLS",
                        count = unpaidBills.size,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                items(unpaidBills, key = { "unpaid_${it.billId}" }) { bill ->
                    BillCard(
                        bill = bill,
                        onMarkPaid = { viewModel.markBillAsPaid(bill.billId) },
                        onUpdatePayment = { viewModel.updatePartialPayment(bill.billId, it) },
                        onMarkUnpaid = { viewModel.markBillAsUnpaid(bill.billId) },
                        onEditBill = { total, min, due ->
                            viewModel.editBill(bill.billId, total, min, due)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }

            // 2. Card Transactions (POS / Spend entries) & Payments Received
            if (cardTransactions.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "CARD TRANSACTIONS",
                        count = cardTransactions.size,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                items(cardTransactions, key = { "card_tx_${it.id}" }) { tx ->
                    CardTransactionRow(
                        txn = tx,
                        onDeleteManual = if (tx.smsBody.startsWith("manual:")) {
                            { viewModel.deleteManualTransaction(tx.id) }
                        } else null,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }

            if (payments.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "PAYMENTS RECEIVED",
                        count = payments.size,
                        color = StatusSafe
                    )
                }
                items(payments, key = { "payment_${it.id}" }) { payment ->
                    PaymentHistoryCard(
                        payment = payment,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }

            // 3. Paid Bills LAST
            if (paidBills.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "PAID BILLS",
                        count = paidBills.size,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(paidBills, key = { "paid_${it.billId}" }) { bill ->
                    BillCard(
                        bill = bill,
                        onMarkPaid = { viewModel.markBillAsPaid(bill.billId) },
                        onUpdatePayment = { viewModel.updatePartialPayment(bill.billId, it) },
                        onMarkUnpaid = { viewModel.markBillAsUnpaid(bill.billId) },
                        onEditBill = { total, min, due ->
                            viewModel.editBill(bill.billId, total, min, due)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }

            if (bills.isEmpty() && payments.isEmpty() && cardTransactions.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No activity found",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Transactions, bills, and payments for this card will appear here automatically",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showEditDialog && card != null) {
        EditCardDialog(
            card = card!!,
            currentOutstanding = outstanding,
            onDismiss = { showEditDialog = false },
            onConfirm = { nickname, creditLimit, currency, newOutstanding, clearAnchor ->
                viewModel.updateCardDetails(
                    card = card!!,
                    nickname = nickname,
                    creditLimit = creditLimit,
                    currency = currency,
                    newOutstanding = newOutstanding,
                    clearOutstandingAnchor = clearAnchor
                )
                showEditDialog = false
            },
            onDeleteRequest = {
                showEditDialog = false
                showDeleteConfirm = true
            }
        )
    }

    if (showDeleteConfirm && card != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Card") },
            text = {
                Text(
                    text = "Are you sure you want to delete ${card!!.displayName}? All associated bills will also be permanently removed."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteCard(card!!) {
                            showDeleteConfirm = false
                            onBack()
                        }
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAddTxnDialog && card != null) {
        AddCardTransactionDialog(
            cardName = card!!.displayName,
            categories = expenseCategories,
            onDismiss = { showAddTxnDialog = false },
            onSubmit = { merchant, amount, occurredAt, category, keepOpen ->
                viewModel.addManualTransaction(merchant, amount, occurredAt, category)
                if (!keepOpen) {
                    showAddTxnDialog = false
                }
            }
        )
    }
}

private fun formatCurrencies(totals: Map<String, Double>): String {
    if (totals.isEmpty()) return "৳0.00"
    return totals.entries.joinToString(" + ") { (currency, amount) ->
        val symbol = when (currency.uppercase()) {
            "USD" -> "$"
            "BDT", "TK" -> "৳"
            else -> currency
        }
        "$symbol${String.format(Locale.US, "%,.2f", amount)}"
    }
}

@Composable
private fun CardSummaryHeader(
    card: CreditCard?,
    outstanding: Double,
    billedTotals: Map<String, Double>,
    paidTotals: Map<String, Double>,
    unpaidTotals: Map<String, Double>,
    spentAmount: Double,
    transactionCount: Int,
    totalBills: Int,
    periodLabel: String
) {
    val limitSym = card?.limitSymbol() ?: "৳"
    val outstandingFormatted = "$limitSym${String.format(Locale.US, "%,.2f", outstanding)}"
    val utilization = if (card != null && card.creditLimit > 0) {
        (outstanding / card.creditLimit).coerceIn(0.0, 1.0)
    } else null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (card?.outstandingAnchor != null) "Outstanding (edited)" else "Outstanding",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                    )
                    Text(
                        text = outstandingFormatted,
                        style = if (outstandingFormatted.length > 14) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (outstanding > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
                    ) {
                        Text(
                            text = "$transactionCount txns",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            if (card != null && card.creditLimit > 0) {
                val utilizationValue = utilization?.coerceIn(0.0, 1.0) ?: 0.0
                val available = card.creditLimit - outstanding
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Credit Limit Usage",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${card.limitSymbol()}${String.format(Locale.US, "%,.0f", card.creditLimit)} • ${(utilizationValue * 100).toInt()}% used",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { utilizationValue.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = if (utilizationValue > 0.8) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (outstanding > 0) {
                                "${card.limitSymbol()}${String.format(Locale.US, "%,.2f", available)} available"
                            } else {
                                "Full limit available"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (utilizationValue > 0.8) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "Unpaid Bills ${formatCurrencies(unpaidTotals)} • Spent ৳${String.format(Locale.US, "%,.2f", spentAmount)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Billed ${formatCurrencies(billedTotals)} • Paid ${formatCurrencies(paidTotals)} ($periodLabel)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$totalBills bills",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
private fun CardTransactionRow(
    txn: CardTransaction,
    onDeleteManual: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val dateStr = remember(txn.occurredAt) {
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date(txn.occurredAt))
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ReceiptLong,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = txn.merchant,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (!txn.category.isNullOrBlank()) "$dateStr • ${txn.category}" else dateStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "৳${String.format(Locale.US, "%,.2f", txn.amount)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (onDeleteManual != null) {
                IconButton(
                    onClick = onDeleteManual,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("card_txn_delete_${txn.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete manual transaction",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddCardTransactionDialog(
    cardName: String,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSubmit: (merchant: String, amount: Double, occurredAt: Long, category: String?, keepOpen: Boolean) -> Unit
) {
    var merchant by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(categories.firstOrNull() ?: "Shopping") }
    var occurredAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var postedCount by remember { mutableIntStateOf(0) }

    val parsedAmount = amountText.replace(",", "").toDoubleOrNull()
    val canSubmit = merchant.isNotBlank() && parsedAmount != null && parsedAmount > 0.0
    val dateLabel = remember(occurredAt) {
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(occurredAt))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Card Spend • $cardName") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (postedCount > 0) {
                    Surface(
                        color = StatusSafe.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "$postedCount spend ${if (postedCount == 1) "entry" else "entries"} added",
                            style = MaterialTheme.typography.labelMedium,
                            color = StatusSafe,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("Merchant / Description") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("card_txn_merchant_input")
                )

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount (৳)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("card_txn_amount_input")
                )

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = dateLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Date") },
                        trailingIcon = {
                            Icon(Icons.Default.CalendarToday, contentDescription = "Select date")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { showDatePicker = true }
                            .testTag("card_txn_date_picker_trigger")
                    )
                }

                if (categories.isNotEmpty()) {
                    Text(
                        text = "Category",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        categories.forEach { cat ->
                            FilterChip(
                                selected = selectedCategory == cat,
                                onClick = { selectedCategory = cat },
                                label = { Text(cat) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = {
                        val amt = parsedAmount ?: return@TextButton
                        onSubmit(merchant, amt, occurredAt, selectedCategory, true)
                        merchant = ""
                        amountText = ""
                        postedCount += 1
                    },
                    enabled = canSubmit,
                    modifier = Modifier.testTag("card_txn_continue_button")
                ) {
                    Text("Continue")
                }
                Button(
                    onClick = {
                        val amt = parsedAmount ?: return@Button
                        onSubmit(merchant, amt, occurredAt, selectedCategory, false)
                    },
                    enabled = canSubmit,
                    modifier = Modifier.testTag("card_txn_confirm_button")
                ) {
                    Text("Add")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (postedCount > 0) "Done" else "Cancel")
            }
        }
    )

    if (showDatePicker) {
        val localCal = remember(occurredAt) {
            Calendar.getInstance().apply { timeInMillis = occurredAt }
        }
        val utcMidnightMillis = remember(occurredAt) {
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(localCal.get(Calendar.YEAR), localCal.get(Calendar.MONTH), localCal.get(Calendar.DAY_OF_MONTH))
            }.timeInMillis
        }
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = utcMidnightMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { selectedUtc ->
                            val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                                timeInMillis = selectedUtc
                            }
                            val updatedLocal = Calendar.getInstance().apply {
                                timeInMillis = occurredAt
                                set(Calendar.YEAR, utcCal.get(Calendar.YEAR))
                                set(Calendar.MONTH, utcCal.get(Calendar.MONTH))
                                set(Calendar.DAY_OF_MONTH, utcCal.get(Calendar.DAY_OF_MONTH))
                            }
                            occurredAt = updatedLocal.timeInMillis
                        }
                        showDatePicker = false
                    }
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun PaymentHistoryCard(
    payment: CardPayment,
    modifier: Modifier = Modifier
) {
    val dateStr = payment.paidAt?.let {
        SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(it))
    } ?: "Date unknown"

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(StatusSafe.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Payments,
                            contentDescription = null,
                            tint = StatusSafe,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Payment Received",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = dateStr,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    if (payment.amountBdt > 0) {
                        Text(
                            text = "+ ৳${String.format(Locale.US, "%,.2f", payment.amountBdt)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = StatusSafe
                        )
                    }
                    if (payment.amountUsd > 0) {
                        Text(
                            text = "+ $${String.format(Locale.US, "%,.2f", payment.amountUsd)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = StatusSafe
                        )
                    }
                }
            }

            if (!payment.smsBody.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = payment.smsBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
fun EditCardDialog(
    card: CreditCard,
    currentOutstanding: Double = 0.0,
    onDismiss: () -> Unit,
    onConfirm: (
        nickname: String,
        creditLimit: Double,
        currency: String,
        newOutstanding: Double?,
        clearOutstandingAnchor: Boolean
    ) -> Unit,
    onDeleteRequest: () -> Unit
) {
    var nickname by remember(card.id) { mutableStateOf(card.cardNickname) }
    var limit by remember(card.id) { mutableStateOf(if (card.creditLimit > 0) card.creditLimit.toString() else "") }
    var currency by remember(card.id) { mutableStateOf(card.creditLimitCurrency) }
    var outstandingText by remember(card.id, currentOutstanding) {
        mutableStateOf(
            if (currentOutstanding > 0.0) String.format(Locale.US, "%.2f", currentOutstanding) else "0"
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Credit Card") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("Card Nickname") },
                    singleLine = true,
                    placeholder = { Text("${card.bankName} •••• ${card.cardLast4}") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_card_nickname_input")
                )

                OutlinedTextField(
                    value = outstandingText,
                    onValueChange = { outstandingText = it },
                    label = { Text("Current Outstanding (${card.limitSymbol()})") },
                    supportingText = {
                        Text(
                            if (card.outstandingAnchor != null) {
                                "Manual baseline active. Future card spend adds & payments subtract automatically."
                            } else {
                                "Auto-calculated from unpaid bills & recent card spend."
                            }
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_card_outstanding_input")
                )

                if (card.outstandingAnchor != null) {
                    TextButton(
                        onClick = {
                            val parsedLimit = limit.replace(",", "").toDoubleOrNull() ?: 0.0
                            onConfirm(nickname, parsedLimit, currency, null, true)
                        },
                        modifier = Modifier
                            .align(Alignment.Start)
                            .testTag("edit_card_reset_outstanding")
                    ) {
                        Text("Reset outstanding to auto-calc")
                    }
                }

                OutlinedTextField(
                    value = limit,
                    onValueChange = { limit = it },
                    label = { Text("Credit Limit") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_card_limit_input")
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Limit Currency",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = currency == "BDT",
                        onClick = { currency = "BDT" },
                        label = { Text("৳ BDT") }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = currency == "USD",
                        onClick = { currency = "USD" },
                        label = { Text("$ USD") }
                    )
                }

                TextButton(
                    onClick = onDeleteRequest,
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Delete Card", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedLimit = limit.replace(",", "").toDoubleOrNull() ?: 0.0
                    val parsedOutstanding = outstandingText.replace(",", "").toDoubleOrNull()
                    val outstandingChanged = parsedOutstanding != null &&
                        kotlin.math.abs(parsedOutstanding - currentOutstanding) > 0.005
                    onConfirm(
                        nickname,
                        parsedLimit,
                        currency,
                        if (outstandingChanged) parsedOutstanding else null,
                        false
                    )
                },
                modifier = Modifier.testTag("edit_card_save_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
