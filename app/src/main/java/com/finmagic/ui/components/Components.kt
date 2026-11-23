package com.finmagic.ui.components

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.finmagic.data.local.entity.BillWithCard
import com.finmagic.data.local.entity.DueStatus
import com.finmagic.ui.theme.StatusDueSoon
import com.finmagic.ui.theme.StatusDueSoonBg
import com.finmagic.ui.theme.StatusOverdue
import com.finmagic.ui.theme.StatusOverdueBg
import com.finmagic.ui.theme.StatusPaid
import com.finmagic.ui.theme.StatusPaidBg
import com.finmagic.ui.theme.StatusSafe
import com.finmagic.ui.theme.StatusSafeBg
import com.finmagic.ui.theme.StatusUpcoming
import com.finmagic.ui.theme.StatusUpcomingBg

@Composable
fun BillCard(
    modifier: Modifier = Modifier,
    bill: BillWithCard,
    onMarkPaid: () -> Unit,
    onUpdatePayment: (Double) -> Unit = {},
    onMarkUnpaid: () -> Unit = {},
    onEditBill: (totalDue: Double, minDue: Double, dueDate: String) -> Unit = { _, _, _ -> },
    onCardClick: (() -> Unit)? = null
) {
    var showPaymentDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showPaySheet by remember { mutableStateOf(false) }

    val statusColor = when (bill.dueStatus) {
        DueStatus.PAID -> StatusPaid
        DueStatus.OVERDUE -> StatusOverdue
        DueStatus.DUE_SOON -> StatusDueSoon
        DueStatus.UPCOMING -> StatusUpcoming
        DueStatus.SAFE -> StatusSafe
    }

    val bgColor = when (bill.dueStatus) {
        DueStatus.PAID -> StatusPaidBg
        DueStatus.OVERDUE -> StatusOverdueBg
        DueStatus.DUE_SOON -> StatusDueSoonBg
        DueStatus.UPCOMING -> StatusUpcomingBg
        DueStatus.SAFE -> StatusSafeBg
    }

    val remainingAmount = maxOf(0.0, bill.totalDue - bill.paidAmount)
    val isOutstanding = !bill.isPaid && remainingAmount > 0.0
    val dangerColor = MaterialTheme.colorScheme.error

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (onCardClick != null) {
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onCardClick() }
                    } else {
                        Modifier.weight(1f)
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CreditCard,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = bill.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (bill.paidViaSms) "Bill: ${bill.billPeriod} • SMS ✓" else "Bill: ${bill.billPeriod}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                StatusChip(status = bill.dueStatus, statusColor = statusColor, bgColor = bgColor)
            }

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Bill Amount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = bill.formattedTotal,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Outstanding",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (bill.isPaid) "৳0.00" else bill.formattedRemaining,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isOutstanding) dangerColor else StatusSafe
                    )
                }

                Column(
                    modifier = Modifier.weight(0.85f),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "Min Due",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val remainingMin = maxOf(0.0, bill.minDue - bill.paidAmount)
                    val formattedMin = if (!bill.isPaid && remainingMin > 0) {
                        val symbol = when (bill.currency.uppercase()) {
                            "USD" -> "$"
                            else -> "৳"
                        }
                        "$symbol${String.format(java.util.Locale.US, "%,.2f", remainingMin)}"
                    } else "Cleared"

                    Text(
                        text = formattedMin,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (!bill.isPaid && remainingMin > 0) statusColor else StatusSafe
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Due: ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = bill.formattedDueDate,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    if (!bill.isPaid && bill.daysUntilDue >= 0) {
                        Text(
                            text = " (${bill.daysUntilDue}d left)",
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                    }
                }

                if (bill.paidAmount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Paid: ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val symbol = when (bill.currency.uppercase()) {
                            "USD" -> "$"
                            else -> "৳"
                        }
                        Text(
                            text = "$symbol${String.format(java.util.Locale.US, "%,.2f", bill.paidAmount)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = StatusSafe
                        )
                    }
                }
            }

            if (!bill.isPaid && bill.dueStatus == DueStatus.OVERDUE) {
                Spacer(modifier = Modifier.height(4.dp))
                val symbol = when (bill.currency.uppercase()) {
                    "USD" -> "$"
                    else -> "৳"
                }
                val monthlyFee = bill.totalDue * 0.025
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = StatusOverdue,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "≈ $symbol${String.format(java.util.Locale.US, "%,.0f", monthlyFee)}/month late fee estimate",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusOverdue
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!bill.isPaid) {
                        Button(
                            onClick = { showPaySheet = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = dangerColor,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(34.dp)
                                .testTag("bill_pay_button_${bill.billId}")
                        ) {
                            Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pay Bill", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        TextButton(
                            onClick = { showPaymentDialog = true },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Partial", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    IconButton(
                        onClick = { showEditDialog = true },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Default.Create,
                            contentDescription = "Edit bill",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                if (!bill.isPaid) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Mark Paid",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Switch(
                            checked = false,
                            onCheckedChange = { onMarkPaid() },
                            modifier = Modifier.scale(0.7f),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = StatusSafe,
                                checkedTrackColor = StatusSafe.copy(alpha = 0.3f)
                            )
                        )
                    }
                }
            }
        }
    }

    if (showPaymentDialog) {
        PaymentAmountDialog(
            totalDue = bill.totalDue,
            minDue = bill.minDue,
            currentPaid = bill.paidAmount,
            currency = bill.currency,
            onDismiss = { showPaymentDialog = false },
            onConfirm = { amount ->
                onUpdatePayment(amount)
                showPaymentDialog = false
            }
        )
    }

    if (showEditDialog) {
        EditBillDialog(
            totalDue = bill.totalDue,
            minDue = bill.minDue,
            dueDate = bill.dueDate,
            currency = bill.currency,
            isPaid = bill.isPaid,
            onDismiss = { showEditDialog = false },
            onConfirm = { total, min, due ->
                onEditBill(total, min, due)
                showEditDialog = false
            },
            onMarkUnpaid = {
                showEditDialog = false
                onMarkUnpaid()
            }
        )
    }

    if (showPaySheet) {
        BillPaySheet(
            bill = bill,
            onDismiss = { showPaySheet = false }
        )
    }
}

@Composable
fun EditBillDialog(
    totalDue: Double,
    minDue: Double,
    dueDate: String,
    currency: String,
    isPaid: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (totalDue: Double, minDue: Double, dueDate: String) -> Unit,
    onMarkUnpaid: () -> Unit = {}
) {
    var totalText by remember { mutableStateOf(totalDue.toString()) }
    var minText by remember { mutableStateOf(minDue.toString()) }
    var dueText by remember { mutableStateOf(dueDate) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Bill") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = totalText,
                    onValueChange = { totalText = it },
                    label = { Text("Total Due ($currency)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = minText,
                    onValueChange = { minText = it },
                    label = { Text("Minimum Due ($currency)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = dueText,
                    onValueChange = { dueText = it },
                    label = { Text("Due Date (dd/mm/yyyy)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (isPaid) {
                    TextButton(
                        onClick = onMarkUnpaid,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Mark Unpaid", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val total = totalText.replace(",", "").toDoubleOrNull() ?: return@TextButton
                    val min = minText.replace(",", "").toDoubleOrNull() ?: return@TextButton
                    if (dueText.isBlank()) return@TextButton
                    onConfirm(total, min, dueText.trim())
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun PaymentAmountDialog(
    totalDue: Double,
    minDue: Double,
    currentPaid: Double,
    currency: String,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var amountText by remember { mutableStateOf(if (currentPaid > 0) currentPaid.toString() else "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update Paid Amount") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Total: ${currency} $totalDue", style = MaterialTheme.typography.bodySmall)
                Text("Minimum: ${currency} $minDue", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount Paid") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { amountText = minDue.toString() },
                        label = { Text("Min Due") }
                    )
                    AssistChip(
                        onClick = { amountText = totalDue.toString() },
                        label = { Text("Full Due") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val amount = amountText.toDoubleOrNull() ?: 0.0
                    onConfirm(amount)
                }
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun StatusChip(
    status: DueStatus,
    statusColor: Color,
    bgColor: Color
) {
    val text = when (status) {
        DueStatus.PAID -> "Paid"
        DueStatus.OVERDUE -> "Overdue"
        DueStatus.DUE_SOON -> "Soon"
        DueStatus.UPCOMING -> "Upcoming"
        DueStatus.SAFE -> "Safe"
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SummaryCard(
    thisMonthTotal: String,
    thisMonthMinTotal: String,
    prevMonthTotal: String,
    unpaidCount: Int,
    unpaidCardCount: Int,
    overdueCount: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Total Due (This Month)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = thisMonthTotal,
                        style = if (thisMonthTotal.length > 15) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Min Due",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    )
                    Text(
                        text = thisMonthMinTotal,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Previous Months Balance",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
            )
            Text(
                text = prevMonthTotal,
                style = if (prevMonthTotal.length > 15) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
            )

            Spacer(modifier = Modifier.height(16.dp))
            Row {
                Text(
                    text = "$unpaidCount bill(s) • $unpaidCardCount card(s) unpaid",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                )
                if (overdueCount > 0) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "$overdueCount overdue",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF8A80),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "($count)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SmsPermissionCard(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "SMS Permission Required",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Grant SMS permission so FinMagic can automatically scan bank messages and detect your credit card bills.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            androidx.compose.material3.Button(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Grant SMS Permission")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillPaySheet(
    bill: BillWithCard,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val remaining = bill.totalDue - bill.paidAmount
    val remainingMin = maxOf(0.0, bill.minDue - bill.paidAmount)
    val symbol = when (bill.currency.uppercase()) {
        "USD" -> "$"
        "BDT", "TK" -> "৳"
        else -> bill.currency
    }
    val masked = "•••• •••• •••• ${bill.cardLast4}"
    val copyText = buildString {
        appendLine("Credit Card Bill Payment")
        appendLine("Card: ${bill.displayName}")
        appendLine("Card No: $masked")
        appendLine("Amount Due: $symbol${String.format(java.util.Locale.US, "%,.2f", bill.totalDue)}")
        appendLine("Remaining: $symbol${String.format(java.util.Locale.US, "%,.2f", remaining)}")
        appendLine("Min Due: $symbol${String.format(java.util.Locale.US, "%,.2f", remainingMin)}")
        appendLine("Due Date: ${bill.formattedDueDate}")
        if (!bill.smsBody.isNullOrBlank()) {
            appendLine("")
            appendLine("Statement SMS:")
            appendLine(bill.smsBody)
        }
    }

    fun share() {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Credit card payment: ${bill.displayName}")
            putExtra(Intent.EXTRA_TEXT, copyText)
        }
        context.startActivity(Intent.createChooser(sendIntent, "Pay via"))
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Pay ${bill.displayName}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = masked,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    PaySheetRow("Amount Due", "$symbol${String.format(java.util.Locale.US, "%,.2f", bill.totalDue)}", isStrong = true)
                    PaySheetRow("Minimum Due", "$symbol${String.format(java.util.Locale.US, "%,.2f", remainingMin)}")
                    PaySheetRow("Due Date", bill.formattedDueDate)
                    if (bill.paidAmount > 0) {
                        PaySheetRow("Already Paid", "$symbol${String.format(java.util.Locale.US, "%,.2f", bill.paidAmount)}")
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(copyText))
                    android.widget.Toast.makeText(context, "Payment details copied", android.widget.Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Copy Payment Details")
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = {
                    share()
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Share to Payment App")
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Open bkash / Nagad / Rocket or your bank app, then paste or mirror the details.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PaySheetRow(
    label: String,
    value: String,
    isStrong: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = if (isStrong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
            fontWeight = if (isStrong) FontWeight.Bold else FontWeight.Medium
        )
    }
}
