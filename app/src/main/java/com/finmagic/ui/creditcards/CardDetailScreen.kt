package com.finmagic.ui.creditcards

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finmagic.data.local.entity.CardPayment
import com.finmagic.data.local.entity.CreditCard
import com.finmagic.ui.components.BillCard
import com.finmagic.ui.components.SectionHeader
import com.finmagic.ui.theme.StatusSafe
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailScreen(
    onBack: () -> Unit,
    viewModel: CardDetailViewModel = hiltViewModel()
) {
    val card by viewModel.card.collectAsStateWithLifecycle()
    val bills by viewModel.bills.collectAsStateWithLifecycle()
    val paidTotals by viewModel.paidTotals.collectAsStateWithLifecycle()
    val unpaidTotals by viewModel.unpaidTotals.collectAsStateWithLifecycle()
    val payments by viewModel.payments.collectAsStateWithLifecycle()

    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val unpaidBills = remember(bills) { bills.filter { !it.isPaid } }
    val paidBills = remember(bills) { bills.filter { it.isPaid } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = card?.displayName ?: "Credit Card",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        card?.let {
                            Text(
                                text = "•••• ${it.cardLast4} • ${it.bankName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    IconButton(onClick = { showEditDialog = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit Card")
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Summary Header Card
            item {
                CardSummaryHeader(
                    card = card,
                    paidTotals = paidTotals,
                    unpaidTotals = unpaidTotals,
                    totalBills = bills.size
                )
            }

            // Unpaid Active Bills
            if (unpaidBills.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "ACTIVE BILLS",
                        count = unpaidBills.size,
                        color = MaterialTheme.colorScheme.primary
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
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // Paid Bill History
            if (paidBills.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "BILL HISTORY",
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
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // Payment History (SMS payments)
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
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            if (bills.isEmpty() && payments.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No bill history found",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Bills and payments for this card will appear here automatically",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
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
            onDismiss = { showEditDialog = false },
            onConfirm = { nickname, creditLimit, currency ->
                viewModel.updateCardDetails(card!!, nickname, creditLimit, currency)
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
    paidTotals: Map<String, Double>,
    unpaidTotals: Map<String, Double>,
    totalBills: Int
) {
    val totalUnpaid = unpaidTotals.values.sum()
    val utilization = if (card != null && card.creditLimit > 0) {
        (totalUnpaid / card.creditLimit).coerceIn(0.0, 1.0)
    } else null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Total Paid",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = formatCurrencies(paidTotals),
                        style = if (formatCurrencies(paidTotals).length > 14) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CreditCard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Active Unpaid",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = formatCurrencies(unpaidTotals),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (totalUnpaid > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Total Statements",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "$totalBills bills",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            if (card != null && card.creditLimit > 0) {
                val utilizationValue = utilization?.coerceIn(0.0, 1.0) ?: 0.0
                Spacer(modifier = Modifier.height(14.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Credit Limit Usage",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${card.limitSymbol()}${String.format(Locale.US, "%,.0f", card.creditLimit)} • ${(utilizationValue * 100).toInt()}% used",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { utilizationValue.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = if (utilizationValue > 0.8) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (totalUnpaid > 0) {
                                "${card.limitSymbol()}${String.format(Locale.US, "%,.2f", card.creditLimit - totalUnpaid)} available"
                            } else {
                                "Full limit available"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (utilizationValue > 0.8) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }
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
        Column(modifier = Modifier.padding(14.dp)) {
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
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = dateStr,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    if (payment.amountBdt > 0) {
                        Text(
                            text = "+ ৳${String.format(Locale.US, "%,.2f", payment.amountBdt)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = StatusSafe
                        )
                    }
                    if (payment.amountUsd > 0) {
                        Text(
                            text = "+ $${String.format(Locale.US, "%,.2f", payment.amountUsd)}",
                            style = MaterialTheme.typography.titleSmall,
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun EditCardDialog(
    card: CreditCard,
    onDismiss: () -> Unit,
    onConfirm: (nickname: String, creditLimit: Double, currency: String) -> Unit,
    onDeleteRequest: () -> Unit
) {
    var nickname by remember(card.id) { mutableStateOf(card.cardNickname) }
    var limit by remember(card.id) { mutableStateOf(if (card.creditLimit > 0) card.creditLimit.toString() else "") }
    var currency by remember(card.id) { mutableStateOf(card.creditLimitCurrency) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Credit Card") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("Card Nickname") },
                    singleLine = true,
                    placeholder = { Text("${card.bankName} •••• ${card.cardLast4}") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = limit,
                    onValueChange = { limit = it },
                    label = { Text("Credit Limit") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
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
            TextButton(onClick = {
                val parsedLimit = limit.replace(",", "").toDoubleOrNull() ?: 0.0
                onConfirm(nickname, parsedLimit, currency)
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
