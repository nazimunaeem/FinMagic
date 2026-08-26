package com.finmagic.ui.smsreview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.SmsRecord
import com.finmagic.data.sms.SmsTeachingEngine
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class TeachAction(val label: String) {
    CREDIT("Credit"),
    DEBIT("Debit"),
    ATM("ATM"),
    IGNORE("Ignore")
}

private data class SmsCandidates(
    val amounts: List<Pair<String, Double>>,
    val dates: List<Pair<String, Long>>,
    val balances: List<Pair<String, Double>>,
    val accountMasks: List<String>
)

private fun extractCandidates(smsTimestamp: Long, smsBody: String): SmsCandidates {
    val amounts = mutableListOf<Pair<String, Double>>()
    val dates = mutableListOf<Pair<String, Long>>()
    val balances = mutableListOf<Pair<String, Double>>()
    val accountMasks = mutableListOf<String>()

    val amountRegex = Regex("""(?:Tk|BDT|Tk\.|BDT\.)\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    amountRegex.findAll(smsBody).forEach { match ->
        val raw = match.groupValues[1]
        val value = raw.replace(",", "").toDoubleOrNull()
        if (value != null && value > 0) {
            amounts.add(match.value to value)
        }
    }

    val balanceRegex = Regex("""(?:Balance|Available balance|Current Balance|C/B|A/C Bal)[:\s]*(?:Tk|BDT)?\.?\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    balanceRegex.findAll(smsBody).forEach { match ->
        val raw = match.groupValues[1]
        val value = raw.replace(",", "").toDoubleOrNull()
        if (value != null) {
            balances.add(match.value to value)
        }
    }

    dates.add("SMS time" to smsTimestamp)
    val dateRegex = Regex("""(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})""")
    dateRegex.findAll(smsBody).forEach { match ->
        val parsed = try {
            val fmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            fmt.parse(match.value)?.time
        } catch (_: Exception) { null }
        if (parsed != null) dates.add(match.value to parsed)
    }

    val maskRegex = Regex("""(?:A/C|A/C#|Account|A/C\s*#?)\s*\*+[\d*]+""", RegexOption.IGNORE_CASE)
    maskRegex.findAll(smsBody).forEach { match ->
        accountMasks.add(match.value)
    }

    return SmsCandidates(amounts, dates, balances, accountMasks)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeachSmsSheet(
    record: SmsRecord,
    accounts: List<Account>,
    onDismiss: () -> Unit,
    onTeachCredit: (accountId: Long, amount: Double, occurredAt: Long, counterparty: String, category: String, saveRule: Boolean, req: String, exc: String) -> Unit,
    onTeachDebit: (accountId: Long, amount: Double, occurredAt: Long, counterparty: String, category: String, saveRule: Boolean, req: String, exc: String) -> Unit,
    onTeachAtm: (accountId: Long, amount: Double, occurredAt: Long, saveRule: Boolean, req: String, exc: String) -> Unit,
    onIgnore: () -> Unit,
    onIgnoreSimilar: () -> Unit,
    onCreateAccount: (
        name: String,
        kind: String,
        bankName: String,
        accountMask: String,
        currency: String,
        includeInTotal: Boolean,
        onCreated: (Long) -> Unit
    ) -> Unit
) {
    val candidates = remember(record) { extractCandidates(record.timestamp, record.body) }
    val extracted = remember(record.body) { SmsTeachingEngine.extractAllFields(record.body) }

    var selectedAction by remember { mutableStateOf(TeachAction.DEBIT) }
    var selectedAccount by remember { mutableStateOf<Long?>(accounts.firstOrNull()?.id) }
    var amountText by remember {
        mutableStateOf(candidates.amounts.firstOrNull()?.second?.let { "%,.2f".format(it) } ?: "")
    }
    var selectedDate by remember { mutableStateOf(extracted.date ?: record.timestamp) }
    var counterparty by remember { mutableStateOf(extracted.counterparty.orEmpty()) }
    var category by remember { mutableStateOf("") }
    var saveRule by remember { mutableStateOf(false) }
    var requiredKeywords by remember { mutableStateOf(record.address ?: "") }
    var excludedKeywords by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var showCreateAccount by remember { mutableStateOf(false) }

    LaunchedEffect(accounts) {
        if (selectedAccount == null || accounts.none { it.id == selectedAccount }) {
            selectedAccount = accounts.firstOrNull()?.id
        }
    }

    val amount = amountText.replace(",", "").replace("%", "").toDoubleOrNull()
    val needsAccountAndAmount = selectedAction != TeachAction.IGNORE
    val isValid = !needsAccountAndAmount || (selectedAccount != null && amount != null && amount > 0)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Teach this SMS",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    val highlights = buildList {
                        extracted.amount?.let { add("Amount: Tk ${"%,.2f".format(it)}") }
                        extracted.date?.let {
                            add("Date: ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(it))}")
                        }
                        extracted.balance?.let { add("Balance: Tk ${"%,.2f".format(it)}") }
                        extracted.accountMask?.let { add("Account: $it") }
                        extracted.counterparty?.let { add("From: $it") }
                    }
                    if (highlights.isNotEmpty()) {
                        Text(
                            text = highlights.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        text = record.body,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Text(
                "From: ${record.address ?: "Unknown"} · ${SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(record.timestamp))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Action", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TeachAction.entries.forEach { action ->
                    FilterChip(
                        selected = selectedAction == action,
                        onClick = { selectedAction = action },
                        label = { Text(action.label) }
                    )
                }
            }

            if (selectedAction != TeachAction.IGNORE) {
                Text("Account", style = MaterialTheme.typography.labelLarge)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    accounts.forEach { account ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedAccount = account.id }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = selectedAccount == account.id,
                                onClick = { selectedAccount = account.id }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(account.displayName)
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showCreateAccount = true }
                            .padding(vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Create new account",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Amount field
            OutlinedTextField(isNotEmpty()) {
                Text(
                    "Detected amounts:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    candidates.amounts.forEach { (label, value) ->
                        AssistChip(
                            onClick = { amountText = "%,.2f".format(value) },
                            label = { Text(label.take(18)) }
                        )
                    }
                }
            }

            if (candidates.dates.size > 1) {
                Text(
                    "Detected dates:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    candidates.dates.forEach { (label, value) ->
                        AssistChip(
                            onClick = { selectedDate = value },
                            label = { Text(label.take(18
                    "Detected amounts:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    candidates.amounts.take(3).forEach { (label, value) ->
                        AssistChip(
                            onClick = { amountText = "%,.2f".format(value) },
                            label = { Text(label.take(15)) }
                        )
                    }
                }
            }

            // Date field
            OutlinedTextField(
                value = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(selectedDate)),
                onValueChange = {},
                label = { Text("Date & Time") },
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDatePicker = true },
                trailingIcon = {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Default.CalendarMonth, "Pick date")
                    }
                }
            )

            OutlinedTextField(
                value = counterparty,
                onValueChange = { counterparty = it },
                label = { Text("Counterparty (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = category,
                onValueChange = { category = it },
                label = { Text("Category (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = saveRule, onCheckedChange = { saveRule = it })
                Text("Save rule for future SMS")
            }

            if (saveRule) {
                OutlinedTextField(
                    requiredKeywords,
                    { requiredKeywords = it },
                    label = { Text("Required keywords (comma separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    excludedKeywords,
                    { excludedKeywords = it },
                    when (selectedAction) {
                        TeachAction.IGNORE -> onIgnore()
                        TeachAction.CREDIT -> {
                            val amt = amount ?: return@Button
                            val accountId = selectedAccount ?: return@Button
                            onTeachCredit(
                                accountId, amt, selectedDate,
                                counterparty, category, saveRule, requiredKeywords, excludedKeywords
                            )
                        }
                        TeachAction.DEBIT -> {
                            val amt = amount ?: return@Button
                            val accountId = selectedAccount ?: return@Button
                            onTeachDebit(
                                accountId, amt, selectedDate,
                                counterparty, category, saveRule, requiredKeywords, excludedKeywords
                            )
                        }
                        TeachAction.ATM -> {
                            val amt = amount ?: return@Button
                            val accountId = selectedAccount ?: return@Button
                            onTeachAtm(
                                accountId, amt, selectedDate,
                                saveRule, requiredKeywords, excludedKeywords
                            )
                        }
                    }
                },
                enabled = isValid,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (selectedAction == TeachAction.IGNORE) "Ignore this SMS" else ck = {
                    if (!isValid || selectedAccount == null) return@Button
                    val amt = amount ?: return@Button
                    when (selectedAction) {
                        TeachAction.CREDIT -> onTeachCredit(
                            selectedAccount!!, amt, selectedDate,
                            counterparty, category, saveRule, requiredKeywords, excludedKeywords
                        )
                        TeachAction.DEBIT -> onTeachDebit(
                            selectedAccount!!, amt, selectedDate,
                            counterparty, category, saveRule, requiredKeywords, excludedKeywords
                        )
                        TeachAction.ATM -> onTeachAtm(
                            selectedAccount!!, amt, selectedDate,
                            saveRule, requiredKeywords, excludedKeywords
                        )
                        TeachAction.IGNORE -> onIgnore()
                    }
                },
                enabled = isValid,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = selectedDate)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { picked ->
                        val original = Calendar.getInstance().apply { timeInMillis = selectedDate }
                        selectedDate = Calendar.getInstance().apply {
                            timeInMillis = picked
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

    if (showCreateAccount) {
        CreateAccountDialog(
            accountMasks = candidates.accountMasks,
            onDismiss = { showCreateAccount = false },
            onConfirm = { name, kind, bankName, mask, currency, includeInTotal ->
                onCreateAccount(name, kind, bankName, mask, currency, includeInTotal) { createdId ->
                    selectedAccount = createdId
                }
                showCreateAccount = false
            }
        )
    }
}

@Composable
private fun CreateAccountDialog(
    accountMasks: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, kind: String, bankName: String, accountMask: String, currency: String, includeInTotal: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(Account.KIND_BANK) }
    var bankName by remember { mutableStateOf("") }
    var accountMask by remember { mutableStateOf(accountMasks.firstOrNull() ?: "") }
    var currency by remember { mutableStateOf("BDT") }
    var includeInTotal by remember { mutableStateOf(true) }

    val isValid = name.isNotBlank() && (kind == Account.KIND_CASH || accountMask.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Account") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Account type", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(Account.KIND_BANK, Account.KIND_WALLET, Account.KIND_CASH).forEach { type ->
                        FilterChip(
                            selected = kind == type,
                            onClick = { kind = type },
                            label = { Text(type.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }

                if (accountMasks.isNotEmpty()) {
                    Text("Detected masks:", style = MaterialTheme.typography.labelSmall)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        accountMasks.forEach { mask ->
                            AssistChip(
                                onClick = { accountMask = mask },
                                label = { Text(mask.take(18)) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = bankName,
                    onValueChange = { bankName = it },
                    label = { Text("Institution (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = accountMask,
                    onValueChange = { accountMask = it },
                    label = { Text("Account mask or identifier") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("***1234") }
                )

                OutlinedTextField(
                    value = currency,
                    onValueChange = { currency = it },
                    label = { Text("Currency") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeInTotal, onCheckedChange = { includeInTotal = it })
                    Text("Include in total balance")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = { onConfirm(name.trim(), kind, bankName.trim(), accountMask.trim(), currency.trim(), includeInTotal) }
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
