package com.finmagic.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.data.sms.parsers.TxnCategory
import com.finmagic.ui.theme.StatusSafe
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale


@Composable
fun TxnRow(
    txn: AccountTransaction,
    runningBalance: Double? = null,
    accountName: String? = null,
    onTap: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onUpdateCategory: ((String) -> Unit)? = null,
    categoryNicknames: Map<String, String> = emptyMap()
) {
    var showCategoryDialog by remember { mutableStateOf(false) }
    val isIn = txn.direction == AccountTransaction.DIRECTION_IN
    val amountColor = if (isIn) StatusSafe else MaterialTheme.colorScheme.error
    val displayCategory = categoryLabel(txn.category, categoryNicknames)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (accountName != null) {
                        Text(
                            text = "$accountName • ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    val mainText = if (!txn.counterparty.isNullOrBlank() && 
                        !txn.counterparty.equals(txn.category, ignoreCase = true)) {
                        txn.counterparty
                    } else if (isIn) {
                        "Credit"
                    } else {
                        "Spent"
                    }
                    
                    Text(
                        text = mainText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val categoryChipLabel = if (txn.category != null &&
                        !txn.category.equals(txn.counterparty, ignoreCase = true)) {
                        displayCategory
                    } else if (onUpdateCategory != null && txn.category == null) {
                        "Categorize"
                    } else {
                        null
                    }

                    categoryChipLabel?.let { cat ->
                        val chipColor = categoryColor(txn.category ?: cat)
                        AssistChip(
                            onClick = {
                                if (onUpdateCategory != null) {
                                    showCategoryDialog = true
                                }
                            },
                            label = { Text(cat, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(24.dp),
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = chipColor.copy(alpha = 0.18f),
                                labelColor = chipColor
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    SimpleDateFormat("hh:mm a", Locale.US).format(Date(txn.occurredAt)).let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = (if (isIn) "+" else "−") + String.format(Locale.US, "Tk %,.2f", txn.amount),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = amountColor
                )
                runningBalance?.let { balance ->
                    Text(
                        text = String.format(Locale.US, "Tk %,.2f", balance),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (onDelete != null && txn.category != TxnCategory.ADJUSTMENT) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    if (showCategoryDialog && onUpdateCategory != null) {
        EditCategoryDialog(
            initialCategory = txn.category ?: "",
            onDismiss = { showCategoryDialog = false },
            onConfirm = { newCat ->
                onUpdateCategory(newCat)
                showCategoryDialog = false
            }
        )
    }
}


@Composable
fun EditTransactionDetailsDialog(
    txn: AccountTransaction,
    onDismiss: () -> Unit,
    onConfirm: (counterparty: String?, category: String?, amount: Double) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var note by remember { mutableStateOf(txn.counterparty ?: "") }
    var category by remember { mutableStateOf(txn.category ?: "") }
    var amountText by remember { mutableStateOf(String.format(Locale.US, "%.2f", txn.amount)) }
    val parsedAmount = amountText.replace(",", "").toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (txn.category == TxnCategory.ADJUSTMENT) "Edit Adjustment Entry" else "Edit Entry Details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note / Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount (Tk)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsedAmount != null && parsedAmount > 0,
                onClick = {
                    onConfirm(note.trim().ifEmpty { null }, category.trim().ifEmpty { null }, parsedAmount!!)
                }
            ) { Text("Save") }
        },
        dismissButton = {
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text("Delete") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun EditCategoryDialog(
    initialCategory: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val predefined = listOf("ATM", "Expense", "Shopping", "Food", "Bill", "Fund Transfer", "Interest", "Salary")
    var selected by remember { mutableStateOf(initialCategory) }
    var customText by remember {
        mutableStateOf(if (predefined.contains(initialCategory)) "" else initialCategory)
    }
    var isCustom by remember {
        mutableStateOf(!predefined.contains(initialCategory) && initialCategory.isNotBlank())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Category / Flag") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Select category flag:", style = MaterialTheme.typography.bodyMedium)
                LazyColumn(modifier = Modifier.height(180.dp)) {
                    items(predefined) { cat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = cat
                                    isCustom = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = !isCustom && selected.equals(cat, ignoreCase = true),
                                onClick = {
                                    selected = cat
                                    isCustom = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(cat, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isCustom = true }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isCustom,
                                onClick = { isCustom = true }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Other / Custom", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (isCustom) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        label = { Text("Custom category name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalCategory = if (isCustom) customText.trim() else selected
                    if (finalCategory.isNotBlank()) {
                        onConfirm(finalCategory)
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

fun dayStart(millis: Long): Long =
    Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

/** Categories a user can pick when logging a manual Cash entry. */
val CASH_ENTRY_CATEGORIES = listOf(
    "Food", "Shopping", "Transport", "Bill", "Entertainment",
    "Health", "Education", "Subscription", "Gift", "Other"
)

/** Horizontal chip selector used for picking a category in entry dialogs. */
@Composable
fun CategorySelector(
    categories: List<String>,
    selected: String?,
    onSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(categories) { cat ->
            FilterChip(
                selected = selected == cat,
                onClick = { onSelected(if (selected == cat) null else cat) },
                label = { Text(cat) }
            )
        }
    }
}

/** Resolves the user-facing label for a category, honouring any set nickname. */
fun categoryLabel(category: String?, nicknames: Map<String, String>): String {
    if (category.isNullOrBlank()) return ""
    return nicknames[category]?.takeIf { it.isNotBlank() } ?: category
}

/** Stable per-category tint so chips aren't all the same homogeneous color. */
private val CATEGORY_PALETTE = listOf(
    0xFFE57373.toInt(), 0xFF7986CB.toInt(), 0xFF4DB6AC.toInt(), 0xFFFFB74D.toInt(),
    0xFFBA68C8.toInt(), 0xFF81C784.toInt(), 0xFF4FC3F7.toInt(), 0xFFA1887F.toInt(),
    0xFF90A4AE.toInt(), 0xFFF06292.toInt(), 0xFF9575CD.toInt(), 0xFFAED581.toInt()
)

fun categoryColor(category: String): androidx.compose.ui.graphics.Color {
    val key = if (category.isBlank()) "Other" else category
    return androidx.compose.ui.graphics.Color(CATEGORY_PALETTE[Math.abs(key.hashCode()) % CATEGORY_PALETTE.size])
}

fun dayLabel(dayStartMillis: Long): String =
    SimpleDateFormat("EEEE, dd MMM yyyy", Locale.US).format(Date(dayStartMillis))

fun computeRunningBalances(transactions: List<AccountTransaction>): Map<Long, Double> {
    val result = mutableMapOf<Long, Double>()
    var running: Double? = null
    for (txn in transactions.sortedWith(compareBy({ it.occurredAt }, { it.id }))) {
        running = when {
            txn.balanceAfter != null -> txn.balanceAfter
            running != null -> if (txn.direction == AccountTransaction.DIRECTION_IN) {
                running + txn.amount
            } else {
                running - txn.amount
            }
            else -> null
        }
        running?.let { result[txn.id] = it }
    }
    return result
}

/**
 * Dialog for adjusting the Cash account balance.
 * Offers two modes:
 *  - "With Adjustment Entry": inserts a visible +/- adjustment transaction so the history
 *    reflects the change (good for tracking missing cash).
 *  - "Without Adjustment Entry": silently overrides the balance via a hidden DirectSet entry
 *    that replaces any previous override (good for initial setup / corrections).
 */
@Composable
fun AdjustCashBalanceDialog(
    currentBalance: Double,
    onDismiss: () -> Unit,
    onWithAdjustment: (Double) -> Unit,
    onWithoutAdjustment: (Double) -> Unit,
    title: String = "Adjust Cash Balance",
    /** When provided, shows the "Include in Total Balance" toggle for this account. */
    includeInTotal: Boolean? = null,
    onSetIncludeInTotal: ((Boolean) -> Unit)? = null
) {
    var targetText by remember { mutableStateOf(String.format(Locale.US, "%.2f", currentBalance)) }
    val targetAmount = targetText.replace(",", "").toDoubleOrNull()
    val diff = targetAmount?.minus(currentBalance)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Current: Tk ${String.format(Locale.US, "%,.2f", currentBalance)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = it },
                    label = { Text("New Balance (Tk)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (diff != null && kotlin.math.abs(diff) >= 0.01) {
                    val sign = if (diff > 0) "+" else "−"
                    val color = if (diff > 0) StatusSafe else MaterialTheme.colorScheme.error
                    Text(
                        text = "Change: $sign Tk ${String.format(Locale.US, "%,.2f", kotlin.math.abs(diff))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = color
                    )
                }
                Text(
                    text = "Choose how to record this change:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (includeInTotal != null && onSetIncludeInTotal != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSetIncludeInTotal(!includeInTotal) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = includeInTotal,
                            onCheckedChange = { onSetIncludeInTotal(it) }
                        )
                        Text(
                            "Include in Total Balance",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    enabled = targetAmount != null && targetAmount >= 0 &&
                        diff != null && kotlin.math.abs(diff) >= 0.01,
                    onClick = { onWithAdjustment(targetAmount!!) }
                ) { Text("With Entry") }
                TextButton(
                    enabled = targetAmount != null && targetAmount >= 0,
                    onClick = { onWithoutAdjustment(targetAmount!!) }
                ) { Text("Without Entry") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Fullscreen-style dialog showing the raw SMS body and account info for a transaction. */
@Composable
fun TxnDetailDialog(
    txn: AccountTransaction,
    accountName: String,
    onDismiss: () -> Unit,
    alreadyExpense: Boolean = false,
    onSetExpense: ((Boolean) -> Unit)? = null,
    /** Non-null only for rows that can be edited (manual entries / adjustments). */
    onEditDetails: (() -> Unit)? = null,
    /** Non-null to show a "Correct" action for SMS-derived transactions. */
    onCorrect: (() -> Unit)? = null
) {
    val isIn = txn.direction == AccountTransaction.DIRECTION_IN
    val amountColor = if (isIn) StatusSafe else MaterialTheme.colorScheme.error
    val dateStr = SimpleDateFormat("EEEE, dd MMM yyyy, hh:mm a", Locale.US).format(Date(txn.occurredAt))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "Transaction Detail",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = accountName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = amountColor.copy(alpha = 0.1f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isIn) "Amount Credited" else "Amount Spent",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = (if (isIn) "+" else "−") +
                                    String.format(Locale.US, "Tk %,.2f", txn.amount),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = amountColor
                            )
                        }
                        Icon(
                            imageVector = if (isIn) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                            contentDescription = null,
                            tint = amountColor,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                if (!txn.counterparty.isNullOrBlank()) {
                    DetailItem(label = "Counterparty", value = txn.counterparty)
                }
                
                txn.category?.let {
                    DetailItem(label = "Category", value = it)
                }

                DetailItem(label = "Date & Time", value = dateStr)

                txn.balanceAfter?.let {
                    DetailItem(
                        label = "Running Balance",
                        value = "Tk ${String.format(Locale.US, "%,.2f", it)}",
                        valueColor = StatusSafe
                    )
                }

                if (!txn.smsBody.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "ORIGINAL SMS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = txn.smsBody,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                            lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onSetExpense != null) {
                    Checkbox(
                        checked = alreadyExpense,
                        onCheckedChange = { checked -> onSetExpense(checked) }
                    )
                    Text(
                        text = "Expense",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clickable { onSetExpense(!alreadyExpense) }
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (onEditDetails != null) {
                    IconButton(onClick = onEditDetails) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Edit details",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (onCorrect != null) {
                    IconButton(onClick = onCorrect) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = "Correct transaction",
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Close", fontWeight = FontWeight.Bold)
                }
            }
        }
    )
}

@Composable
private fun DetailItem(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = valueColor,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun EditAccountDialog(
    account: Account,
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean) -> Unit,
    onDeleteRequest: () -> Unit,
    onAdjustBalance: (() -> Unit)? = null
) {
    var name by remember(account.id) { mutableStateOf(account.displayName) }
    var includeInTotal by remember(account.id) { mutableStateOf(account.includeInTotal) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Account") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Account name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { includeInTotal = !includeInTotal },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = includeInTotal,
                        onCheckedChange = { includeInTotal = it }
                    )
                    Text(
                        "Include in Total Balance",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (onAdjustBalance != null) {
                    TextButton(
                        onClick = onAdjustBalance,
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Adjust Balance",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Adjust Balance", color = MaterialTheme.colorScheme.primary)
                    }
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
                    Text("Delete Account", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name, includeInTotal) }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun DeleteAccountDialog(
    account: Account,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Account") },
        text = {
            Text(
                text = "Delete \"${account.displayName}\" and all of its transactions? " +
                    "This cannot be undone."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Correction dialog for misparsed SMS transactions (plan §2.2).
 * Allows changing direction, amount, account, category, and counterparty.
 * For Phase 1, correction is one-time only (no reusable rule).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CorrectTransactionDialog(
    txn: AccountTransaction,
    accounts: List<com.finmagic.data.local.entity.Account>,
    onDismiss: () -> Unit,
    onConfirm: (direction: String, amount: Double, accountId: Long, category: String, counterparty: String) -> Unit
) {
    var direction by remember { mutableStateOf(txn.direction) }
    var amountText by remember { mutableStateOf(String.format(Locale.US, "%.2f", txn.amount)) }
    var selectedAccountId by remember { mutableStateOf(txn.accountId) }
    var category by remember { mutableStateOf(txn.category ?: "") }
    var counterparty by remember { mutableStateOf(txn.counterparty ?: "") }

    val amount = amountText.replace(",", "").toDoubleOrNull()
    val isValid = amount != null && amount > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Correct Transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Direction", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = direction == AccountTransaction.DIRECTION_IN,
                        onClick = { direction = AccountTransaction.DIRECTION_IN },
                        label = { Text("Credit") }
                    )
                    FilterChip(
                        selected = direction == AccountTransaction.DIRECTION_OUT,
                        onClick = { direction = AccountTransaction.DIRECTION_OUT },
                        label = { Text("Debit") }
                    )
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    prefix = { Text("Tk ") }
                )

                Text("Account", style = MaterialTheme.typography.labelMedium)
                accounts.forEach { account ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedAccountId = account.id }
                            .padding(vertical = 2.dp)
                    ) {
                        RadioButton(
                            selected = selectedAccountId == account.id,
                            onClick = { selectedAccountId = account.id }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(account.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = counterparty,
                    onValueChange = { counterparty = it },
                    label = { Text("Counterparty") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "This will replace the current transaction. The original SMS body is preserved.",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = {
                    val amt = amount ?: return@TextButton
                    onConfirm(direction, amt, selectedAccountId, category, counterparty)
                }
            ) { Text("Correct") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
