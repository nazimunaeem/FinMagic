package com.finmagic.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.finmagic.data.local.AccountTypeConfig
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.data.repository.AccountRepository
import com.finmagic.data.sms.parsers.TxnCategory
import com.finmagic.ui.theme.StatusSafe
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale


private fun formatTrailBalance(amount: Double): String {
    val sign = if (amount < 0) "−" else ""
    return "${sign}৳${String.format(Locale.US, "%,.2f", kotlin.math.abs(amount))}"
}

@Composable
fun TxnRow(
    txn: AccountTransaction,
    runningBalance: Double? = null,
    balanceTrail: AccountRepository.BalanceTrail? = null,
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
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
                            !txn.counterparty.equals(txn.category, ignoreCase = true)
                        ) {
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
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val categoryChipLabel = if (txn.category != null &&
                            !txn.category.equals(txn.counterparty, ignoreCase = true)
                        ) {
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
                        if (TxnCategory.countsAsIncome(txn.isExpenseOverride, txn.direction)) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = StatusSafe.copy(alpha = 0.14f),
                                modifier = Modifier.padding(end = 6.dp)
                            ) {
                                Text(
                                    text = "Income",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = StatusSafe,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
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
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    if (balanceTrail != null) {
                        val afterColor = if (balanceTrail.after < 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                        Text(
                            text = formatTrailBalance(balanceTrail.after),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            ),
                            fontWeight = FontWeight.SemiBold,
                            color = afterColor,
                            modifier = Modifier.testTag("txn_balance_trail_${txn.id}")
                        )
                        Text(
                            text = (if (isIn) "+" else "−") + String.format(Locale.US, "৳%,.2f", txn.amount),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = amountColor
                        )
                        Text(
                            text = formatTrailBalance(balanceTrail.before),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = (if (isIn) "+" else "−") + String.format(Locale.US, "৳%,.2f", txn.amount),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = amountColor
                        )
                        runningBalance?.let { balance ->
                            Text(
                                text = formatTrailBalance(balance),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
                    label = { Text("Amount (৳)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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

fun computeRunningBalances(transactions: List<AccountTransaction>): Map<Long, Double> =
    AccountRepository.computeBalanceTrails(transactions).mapValues { it.value.after }

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
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Current: ৳${String.format(Locale.US, "%,.2f", currentBalance)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = it },
                    label = { Text("New Balance (৳)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                if (diff != null && kotlin.math.abs(diff) >= 0.01) {
                    val sign = if (diff > 0) "+" else "−"
                    val color = if (diff > 0) StatusSafe else MaterialTheme.colorScheme.error
                    Text(
                        text = "Change: ${sign}৳${String.format(Locale.US, "%,.2f", kotlin.math.abs(diff))}",
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
    alreadyIncome: Boolean = false,
    onSetIncome: ((Boolean) -> Unit)? = null,
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
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
                                    String.format(Locale.US, "৳%,.2f", txn.amount),
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
                        value = "৳${String.format(Locale.US, "%,.2f", it)}",
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
                if (isIn && onSetIncome != null) {
                    Checkbox(
                        checked = alreadyIncome,
                        onCheckedChange = { checked -> onSetIncome(checked) },
                        modifier = Modifier.testTag("txn_income_checkbox")
                    )
                    Text(
                        text = "Income",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clickable { onSetIncome(!alreadyIncome) }
                    )
                } else if (!isIn && onSetExpense != null) {
                    Checkbox(
                        checked = alreadyExpense,
                        onCheckedChange = { checked -> onSetExpense(checked) },
                        modifier = Modifier.testTag("txn_expense_checkbox")
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
    onAdjustBalance: (() -> Unit)? = null,
    availableTypes: List<AccountTypeConfig> = emptyList(),
    onConfirmDetails: ((name: String, accountType: String, isLiability: Boolean, includeInTotal: Boolean) -> Unit)? = null
) {
    var name by remember(account.id) { mutableStateOf(account.displayName) }
    var includeInTotal by remember(account.id) { mutableStateOf(account.includeInTotal) }
    var selectedType by remember(account.id) { mutableStateOf(account.effectiveType) }
    var isLiability by remember(account.id) { mutableStateOf(account.isLiability) }
    val typesToShow = remember(availableTypes, selectedType, isLiability) {
        val defaults = if (availableTypes.isEmpty()) {
            Account.DEFAULT_TYPES.map { AccountTypeConfig(it.first, it.second) }
        } else availableTypes
        if (defaults.any { it.name.equals(selectedType, ignoreCase = true) }) {
            defaults
        } else {
            defaults + AccountTypeConfig(selectedType, isLiability)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Account") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Account name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (account.kind != Account.KIND_CASH) {
                    Text("Account Type", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(typesToShow, key = { it.name }) { typeConfig ->
                            val isSelected = selectedType.equals(typeConfig.name, ignoreCase = true)
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedType = typeConfig.name
                                    isLiability = typeConfig.isLiability
                                },
                                label = {
                                    Text(
                                        if (typeConfig.isLiability) "${typeConfig.name} (Liability)"
                                        else typeConfig.name
                                    )
                                }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isLiability = !isLiability },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isLiability,
                            onCheckedChange = { isLiability = it }
                        )
                        Text(
                            "Liability account (subtracted from net balance)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
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
                onClick = {
                    if (onConfirmDetails != null) {
                        onConfirmDetails(name, selectedType, isLiability, includeInTotal)
                    } else {
                        onConfirm(name, includeInTotal)
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun AddAccountDialog(
    availableTypes: List<AccountTypeConfig>,
    onDismiss: () -> Unit,
    onCreateCustomType: (name: String, isLiability: Boolean) -> Unit,
    onConfirm: (
        name: String,
        accountType: String,
        isLiability: Boolean,
        bankName: String,
        accountMask: String,
        initialBalance: Double,
        includeInTotal: Boolean
    ) -> Unit
) {
    val types = remember(availableTypes) {
        if (availableTypes.isEmpty()) Account.DEFAULT_TYPES.map { AccountTypeConfig(it.first, it.second) }
        else availableTypes
    }
    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(types.firstOrNull { it.name == Account.TYPE_SAVINGS } ?: types.first()) }
    var isLiability by remember { mutableStateOf(selectedType.isLiability) }
    var bankName by remember { mutableStateOf("") }
    var accountMask by remember { mutableStateOf("") }
    var balanceText by remember { mutableStateOf("") }
    var includeInTotal by remember { mutableStateOf(true) }
    var showNewTypeInput by remember { mutableStateOf(false) }
    var newTypeName by remember { mutableStateOf("") }
    var newTypeIsLiability by remember { mutableStateOf(false) }

    val parsedBalance = balanceText.replace(",", "").toDoubleOrNull() ?: 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Account") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Account name") },
                    placeholder = { Text("e.g. Emergency Savings, Home Loan") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_account_name_input")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Account Type", style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { showNewTypeInput = !showNewTypeInput }) {
                        Text(if (showNewTypeInput) "Hide" else "+ New Type")
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(types, key = { it.name }) { typeConfig ->
                        FilterChip(
                            selected = selectedType.name.equals(typeConfig.name, ignoreCase = true),
                            onClick = {
                                selectedType = typeConfig
                                isLiability = typeConfig.isLiability
                            },
                            label = {
                                Text(
                                    if (typeConfig.isLiability) "${typeConfig.name} (Liability)"
                                    else typeConfig.name
                                )
                            }
                        )
                    }
                }
                if (showNewTypeInput) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = newTypeName,
                                onValueChange = { newTypeName = it },
                                label = { Text("Custom type name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { newTypeIsLiability = !newTypeIsLiability }
                            ) {
                                Checkbox(
                                    checked = newTypeIsLiability,
                                    onCheckedChange = { newTypeIsLiability = it }
                                )
                                Text("Treat as Liability (e.g. Loan)", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(
                                enabled = newTypeName.isNotBlank(),
                                onClick = {
                                    val trimmed = newTypeName.trim()
                                    onCreateCustomType(trimmed, newTypeIsLiability)
                                    val created = AccountTypeConfig(trimmed, newTypeIsLiability)
                                    selectedType = created
                                    isLiability = newTypeIsLiability
                                    newTypeName = ""
                                    showNewTypeInput = false
                                }
                            ) { Text("Add & Select Type") }
                        }
                    }
                }
                OutlinedTextField(
                    value = balanceText,
                    onValueChange = { balanceText = it },
                    label = { Text(if (isLiability) "Outstanding Balance (৳)" else "Current Balance (৳)") },
                    placeholder = { Text("0.00") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = bankName,
                    onValueChange = { bankName = it },
                    label = { Text("Bank / Institution (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = accountMask,
                    onValueChange = { accountMask = it },
                    label = { Text("Account Number / Mask (optional)") },
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
                    Text("Include in Net Balance", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onConfirm(
                        name.trim(),
                        selectedType.name,
                        isLiability,
                        bankName.trim(),
                        accountMask.trim(),
                        parsedBalance,
                        includeInTotal
                    )
                },
                modifier = Modifier.testTag("add_account_confirm_button")
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ManageAccountTypesDialog(
    allTypes: List<AccountTypeConfig>,
    customTypes: List<AccountTypeConfig>,
    onDismiss: () -> Unit,
    onAddType: (name: String, isLiability: Boolean) -> Unit,
    onDeleteCustomType: (name: String) -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var isLiability by remember { mutableStateOf(false) }
    val customNames = remember(customTypes) { customTypes.map { it.name.lowercase() }.toSet() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Account Types") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Built-in & Custom Types",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                allTypes.forEach { type ->
                    val isCustom = type.name.lowercase() in customNames
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = type.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (type.isLiability) {
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                                } else {
                                    StatusSafe.copy(alpha = 0.14f)
                                }
                            ) {
                                Text(
                                    text = if (type.isLiability) "Liability" else "Asset",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (type.isLiability) MaterialTheme.colorScheme.error else StatusSafe,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (isCustom) {
                            IconButton(onClick = { onDeleteCustomType(type.name) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete ${type.name}",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Create New Type",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Type name (e.g. DPS, FDR, Mortgage)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_account_type_input")
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isLiability = !isLiability },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isLiability,
                        onCheckedChange = { isLiability = it }
                    )
                    Text("Liability (subtracted from net total)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = newName.isNotBlank(),
                onClick = {
                    onAddType(newName.trim(), isLiability)
                    newName = ""
                    isLiability = false
                },
                modifier = Modifier.testTag("add_account_type_confirm_button")
            ) { Text("Add Type") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
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
 * Allows changing direction, amount, account, category, counterparty, and balance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CorrectTransactionDialog(
    txn: AccountTransaction,
    accounts: List<com.finmagic.data.local.entity.Account>,
    onDismiss: () -> Unit,
    onConfirm: (direction: String, amount: Double, accountId: Long, category: String, counterparty: String, balanceAfter: Double?) -> Unit,
    onIgnore: (() -> Unit)? = null
) {
    var direction by remember { mutableStateOf(txn.direction) }
    var amountText by remember { mutableStateOf(String.format(Locale.US, "%.2f", txn.amount)) }
    var balanceText by remember { mutableStateOf(txn.balanceAfter?.let { String.format(Locale.US, "%.2f", it) } ?: "") }
    var selectedAccountId by remember { mutableStateOf(txn.accountId) }
    var category by remember { mutableStateOf(txn.category ?: "") }
    var counterparty by remember { mutableStateOf(txn.counterparty ?: "") }

    val amount = amountText.replace(",", "").toDoubleOrNull()
    val balanceAfter = balanceText.replace(",", "").toDoubleOrNull()
    val isValid = amount != null && amount > 0

    val detectedBalances = remember(txn.smsBody) {
        txn.smsBody?.let { body ->
            val regex = Regex("""(?:Balance|Available balance|Current Balance|C/B|A/C Bal)[:\s]*(?:Tk|BDT)?\.?\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            regex.findAll(body).mapNotNull { m -> m.groupValues[1].replace(",", "").toDoubleOrNull() }.toList()
        } ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Correct Transaction") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
                    prefix = { Text("৳") }
                )

                OutlinedTextField(
                    value = balanceText,
                    onValueChange = { balanceText = it },
                    label = { Text("Reported Balance (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    prefix = { Text("৳") }
                )

                if (detectedBalances.isNotEmpty()) {
                    Text(
                        "Detected balances:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        detectedBalances.forEach { bal ->
                            AssistChip(
                                onClick = { balanceText = "%,.2f".format(bal) },
                                label = { Text("৳%,.2f".format(bal)) }
                            )
                        }
                    }
                }

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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onIgnore != null) {
                    TextButton(onClick = onIgnore) {
                        Text("Ignore SMS", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(
                    enabled = isValid,
                    onClick = {
                        val amt = amount ?: return@TextButton
                        onConfirm(direction, amt, selectedAccountId, category, counterparty, balanceAfter)
                    }
                ) { Text("Correct") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
