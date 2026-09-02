package com.finmagic.ui.smsreview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.CreditCard
import com.finmagic.data.local.entity.SmsRecord
import com.finmagic.data.sms.SmsTeachingEngine
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class TeachAction(val label: String) {
    CREDIT("Credit"),
    DEBIT("Debit"),
    TRANSFER("Transfer"),
    ATM("ATM Withdrawal"),
    BALANCE_ONLY("Balance Update"),
    BILL("Create Bill"),
    BILL_PAID("Bill Paid"),
    IGNORE("Ignore")
}

private data class SmsCandidates(
    val amounts: List<Pair<String, Double>>,
    val dates: List<Pair<String, Long>>,
    val balances: List<Pair<String, Double>>,
    val accountMasks: List<String>
)

internal fun suggestTeachAction(body: String): TeachAction {
    val text = body.lowercase(Locale.US)
    return when {
        Regex("""\b(?:bill|statement|total due|min due|outstanding)\b""").containsMatchIn(text) -> {
            if (Regex("""\b(?:paid|payment success|settled|cleared)\b""").containsMatchIn(text)) TeachAction.BILL_PAID else TeachAction.BILL
        }
        Regex("""\b(?:atm|cash withdrawal|withdrawn from.*atm|withdraw cash)\b""").containsMatchIn(text) -> TeachAction.ATM
        Regex("""\b(?:transfer(?:red)?|fund transfer|sent to|transferred)\b""").containsMatchIn(text) -> TeachAction.TRANSFER
        Regex("""\b(?:balance|current balance|available balance|a/c bal|cb)\b""").containsMatchIn(text) -> TeachAction.BALANCE_ONLY
        Regex("""\b(?:credit(?:ed)?|received|deposited|top[- ]?up|added)\b""").containsMatchIn(text) -> TeachAction.CREDIT
        Regex("""\b(?:debit(?:ed)?|withdrawn|spent|charged|purchase|payment of|transacted)\b""").containsMatchIn(text) -> TeachAction.DEBIT
        else -> TeachAction.DEBIT
    }
}

private data class ActionInsight(
    val confidence: String,
    val reasons: List<String>
)

private fun buildActionInsight(body: String, action: TeachAction): ActionInsight {
    val text = body.lowercase(Locale.US)
    val reasons = mutableListOf<String>()

    when (action) {
        TeachAction.BILL -> {
            if (Regex("""\b(?:bill|statement|total due|min due|outstanding)\b""").containsMatchIn(text)) reasons += "Bill keywords detected"
            if (Regex("""\b(?:card|credit card|visa|amex|mastercard)\b""").containsMatchIn(text)) reasons += "Card target detected"
        }
        TeachAction.BILL_PAID -> {
            if (Regex("""\b(?:paid|payment success|settled|cleared)\b""").containsMatchIn(text)) reasons += "Payment wording detected"
            if (Regex("""\b(?:card|credit card|visa|amex|mastercard)\b""").containsMatchIn(text)) reasons += "Card target detected"
        }
        TeachAction.TRANSFER -> {
            if (Regex("""\b(?:transfer(?:red)?|fund transfer|sent to|transferred)\b""").containsMatchIn(text)) reasons += "Transfer wording detected"
            if (Regex("""\b(?:to\s+.*account|from\s+.*account)\b""").containsMatchIn(text)) reasons += "From/to account pattern detected"
        }
        TeachAction.ATM -> {
            if (Regex("""\b(?:atm|cash withdrawal|withdrawn from.*atm|withdraw cash)\b""").containsMatchIn(text)) reasons += "ATM pattern detected"
        }
        TeachAction.BALANCE_ONLY -> {
            if (Regex("""\b(?:balance|current balance|available balance|a/c bal|cb)\b""").containsMatchIn(text)) reasons += "Balance wording detected"
        }
        TeachAction.CREDIT -> {
            if (Regex("""\b(?:credit(?:ed)?|received|deposited|top[- ]?up|added)\b""").containsMatchIn(text)) reasons += "Credit wording detected"
        }
        TeachAction.DEBIT -> {
            if (Regex("""\b(?:debit(?:ed)?|withdrawn|spent|charged|purchase|payment of|transacted)\b""").containsMatchIn(text)) reasons += "Debit wording detected"
        }
        TeachAction.IGNORE -> {
            if (Regex("""\b(?:otp|offer|promo|marketing|you have won|congratulations)\b""").containsMatchIn(text)) reasons += "Noise pattern detected"
        }
    }

    val confidence = when {
        reasons.size >= 2 -> "High"
        reasons.size == 1 -> "Medium"
        else -> "Low"
    }

    return ActionInsight(confidence, reasons.ifEmpty { listOf("No strong signal found") })
}

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
    creditCards: List<CreditCard>,
    onDismiss: () -> Unit,
    onTeachCredit: (accountId: Long, amount: Double, occurredAt: Long, counterparty: String, category: String, balanceAfter: Double?, saveRule: Boolean, req: String, exc: String) -> Unit,
    onTeachDebit: (accountId: Long, amount: Double, occurredAt: Long, counterparty: String, category: String, balanceAfter: Double?, saveRule: Boolean, req: String, exc: String) -> Unit,
    onTeachTransfer: (fromAccountId: Long, toAccountId: Long, amount: Double, occurredAt: Long, saveRule: Boolean, req: String, exc: String) -> Unit,
    onTeachAtm: (accountId: Long, amount: Double, occurredAt: Long, saveRule: Boolean, req: String, exc: String) -> Unit,
    onTeachBalanceOnly: (accountId: Long, balance: Double, occurredAt: Long, saveRule: Boolean, req: String, exc: String) -> Unit,
    onTeachCreateBill: (cardId: Long, totalDue: Double, minDue: Double, dueDate: String, billPeriod: String, occurredAt: Long, saveRule: Boolean, req: String, exc: String) -> Unit,
    onTeachBillPaid: (cardId: Long, amount: Double, occurredAt: Long, saveRule: Boolean, req: String, exc: String) -> Unit,
    onIgnore: () -> Unit,
    onBlockSender: () -> Unit,
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

    var selectedAction by remember(record.body) { mutableStateOf(suggestTeachAction(record.body)) }
    var fromAccountId by remember { mutableStateOf<Long?>(accounts.firstOrNull()?.id) }
    var toAccountId by remember { mutableStateOf<Long?>(accounts.firstOrNull()?.id) }
    var selectedCardId by remember { mutableStateOf<Long?>(creditCards.firstOrNull()?.id) }
    var amountText by remember {
        mutableStateOf(candidates.amounts.firstOrNull()?.second?.let { "%,.2f".format(it) } ?: "")
    }
    var balanceText by remember {
        mutableStateOf(
            extracted.balance?.let { "%,.2f".format(it) }
                ?: candidates.balances.firstOrNull()?.second?.let { "%,.2f".format(it) }
                ?: ""
        )
    }
    var minText by remember {
        mutableStateOf(candidates.amounts.firstOrNull()?.second?.let { "%,.2f".format(it) } ?: "")
    }
    var selectedDate by remember { mutableStateOf(extracted.date ?: record.timestamp) }
    var counterparty by remember { mutableStateOf(extracted.counterparty.orEmpty()) }
    var category by remember { mutableStateOf("") }
    var saveRule by remember { mutableStateOf(false) }
    var requiredKeywords by remember { mutableStateOf("") }
    var excludedKeywords by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var showCreateAccount by remember { mutableStateOf(false) }
    var showBlockSenderConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(accounts) {
        if (fromAccountId == null || accounts.none { it.id == fromAccountId }) {
            fromAccountId = accounts.firstOrNull()?.id
        }
        if (toAccountId == null || accounts.none { it.id == toAccountId }) {
            toAccountId = accounts.firstOrNull()?.id
        }
    }

    val amount = amountText.replace(",", "").replace("%", "").toDoubleOrNull()
    val balance = balanceText.replace(",", "").toDoubleOrNull()
    val minDue = minText.replace(",", "").toDoubleOrNull() ?: 0.0
    val billDueDate = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date(selectedDate))
    val billPeriod = SimpleDateFormat("MMMM yyyy", Locale.US).format(Date(selectedDate))
    val actionInsight = remember(record.body, selectedAction) { buildActionInsight(record.body, selectedAction) }
    val validationWarnings = remember(selectedAction, fromAccountId, toAccountId, selectedCardId, amountText, balanceText, amount, balance, minDue) {
        buildList {
            if (selectedAction != TeachAction.IGNORE && selectedAction != TeachAction.BALANCE_ONLY && amountText.isBlank()) {
                add("Amount is blank.")
            }
            if (selectedAction == TeachAction.TRANSFER && (fromAccountId == null || toAccountId == null)) {
                add("Choose both transfer accounts.")
            }
            if ((selectedAction == TeachAction.CREDIT || selectedAction == TeachAction.DEBIT || selectedAction == TeachAction.ATM || selectedAction == TeachAction.BALANCE_ONLY) && fromAccountId == null) {
                add("Choose an account target.")
            }
            if ((selectedAction == TeachAction.BILL || selectedAction == TeachAction.BILL_PAID) && selectedCardId == null) {
                add("Choose a credit card target.")
            }
            if (amount != null && amount <= 0.0 && selectedAction != TeachAction.IGNORE && selectedAction != TeachAction.BALANCE_ONLY) {
                add("Amount should be greater than zero.")
            }
            if (selectedAction == TeachAction.BALANCE_ONLY && balance == null) {
                add("A balance value is required for a balance update.")
            }
            if (selectedAction == TeachAction.BILL && minDue > 0.0 && minDue > (amount ?: Double.MAX_VALUE)) {
                add("Minimum due looks larger than the total due.")
            }
            if (selectedAction != TeachAction.IGNORE && amount != null && amount > 1000000.0) {
                add("This amount is unusually large for a single SMS.")
            }
        }
    }

    val needsAccountAndAmount = when (selectedAction) {
        TeachAction.IGNORE, TeachAction.BALANCE_ONLY, TeachAction.BILL, TeachAction.BILL_PAID -> false
        TeachAction.TRANSFER -> true // Needs fromAccount, toAccount, and amount
        else -> true // CREDIT, DEBIT, ATM need account and amount
    }

    val isValid = when (selectedAction) {
        TeachAction.IGNORE -> true
        TeachAction.BALANCE_ONLY -> fromAccountId != null && balance != null
        TeachAction.TRANSFER -> fromAccountId != null && toAccountId != null && fromAccountId != toAccountId && amount != null && amount > 0
        TeachAction.BILL -> selectedCardId != null && amount != null && amount > 0 && minDue >= 0
        TeachAction.BILL_PAID -> selectedCardId != null && amount != null && amount > 0
        else -> fromAccountId != null && amount != null && amount > 0 // CREDIT, DEBIT, ATM
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Teach SMS", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Configure this SMS entry",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (record.isParsed) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "This SMS was already parsed. Saving will replace the previous taught entry.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

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

            when (selectedAction) {
                TeachAction.IGNORE -> {
                    // No account selection needed
                }
                TeachAction.BALANCE_ONLY -> {
                    Text("Account", style = MaterialTheme.typography.labelLarge)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            "Accounts",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        accounts.forEach { account ->
                            TargetChoiceRow(
                                label = account.displayName,
                                tag = "Account",
                                selected = fromAccountId == account.id,
                                onClick = {
                                    fromAccountId = account.id
                                    selectedCardId = null
                                }
                            )
                        }
                        if (creditCards.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Credit Cards",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            creditCards.forEach { card ->
                                TargetChoiceRow(
                                    label = card.displayName,
                                    tag = "Card",
                                    selected = selectedCardId == card.id,
                                    onClick = {
                                        selectedCardId = card.id
                                        fromAccountId = null
                                    }
                                )
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
                TeachAction.TRANSFER -> {
                    Text("From Account", style = MaterialTheme.typography.labelLarge)
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
                                    .clickable { fromAccountId = account.id }
                                    .padding(vertical = 4.dp)
                            ) {
                                RadioButton(
                                    selected = fromAccountId == account.id,
                                    onClick = { fromAccountId = account.id }
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
                    
                    Text("To Account", style = MaterialTheme.typography.labelLarge)
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
                                    .clickable { toAccountId = account.id }
                                    .padding(vertical = 4.dp)
                            ) {
                                RadioButton(
                                    selected = toAccountId == account.id,
                                    onClick = { toAccountId = account.id }
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
                TeachAction.BILL -> {
                    Text("Credit Card (bill to create)", style = MaterialTheme.typography.labelLarge)
                    CreditCardPicker(creditCards = creditCards, selectedCardId = selectedCardId) {
                        selectedCardId = it
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = minText,
                        onValueChange = { minText = it },
                        label = { Text("Minimum Due (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        prefix = { Text("Tk ") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
                TeachAction.BILL_PAID -> {
                    Text("Credit Card (bill was paid)", style = MaterialTheme.typography.labelLarge)
                    CreditCardPicker(creditCards = creditCards, selectedCardId = selectedCardId) {
                        selectedCardId = it
                    }
                }
                else -> { // CREDIT, DEBIT, ATM
                    Text("Account", style = MaterialTheme.typography.labelLarge)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            "Accounts",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        accounts.forEach { account ->
                            TargetChoiceRow(
                                label = account.displayName,
                                tag = "Account",
                                selected = fromAccountId == account.id,
                                onClick = {
                                    fromAccountId = account.id
                                    selectedCardId = null
                                }
                            )
                        }
                        if (creditCards.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Credit Cards",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            creditCards.forEach { card ->
                                TargetChoiceRow(
                                    label = card.displayName,
                                    tag = "Card",
                                    selected = selectedCardId == card.id,
                                    onClick = {
                                        selectedCardId = card.id
                                        fromAccountId = null
                                    }
                                )
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
            }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    prefix = { Text("Tk ") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = amountText.isNotBlank() && (amount == null || amount <= 0)
                )

                if (candidates.amounts.isNotEmpty()) {
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

                OutlinedTextField(
                    value = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(selectedDate)),
                    onValueChange = {},
                    label = { Text("Date & Time") },
                    readOnly = true,
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.CalendarMonth, "Pick date")
                        }
                    }
                )

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
                                label = { Text(label.take(18)) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = balanceText,
                    onValueChange = { balanceText = it },
                    label = { Text("Reported Balance (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    prefix = { Text("Tk ") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )

                if (candidates.balances.isNotEmpty()) {
                    Text(
                        "Detected balances:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        candidates.balances.forEach { (label, value) ->
                            AssistChip(
                                onClick = { balanceText = "%,.2f".format(value) },
                                label = { Text(label.take(18)) }
                            )
                        }
                    }
                }

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
                Text(
                    "Keywords help match future SMS from this sender. Leave empty to match all messages from ${record.address ?: "this sender"}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = requiredKeywords,
                    onValueChange = { requiredKeywords = it },
                    label = { Text("Required keywords (comma separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = excludedKeywords,
                    onValueChange = { excludedKeywords = it },
                    label = { Text("Excluded keywords (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onIgnore,
                    modifier = Modifier.weight(1f)
                ) { Text("Ignore") }
                OutlinedButton(
                    onClick = { showBlockSenderConfirm = true },
                    modifier = Modifier.weight(1f)
                ) { Text("Block Sender") }
            }

            val previewTitle = when (selectedAction) {
                TeachAction.CREDIT -> "Preview: credit to account"
                TeachAction.DEBIT -> "Preview: debit from account"
                TeachAction.TRANSFER -> "Preview: transfer between accounts"
                TeachAction.ATM -> "Preview: ATM withdrawal"
                TeachAction.BALANCE_ONLY -> "Preview: balance update"
                TeachAction.BILL -> "Preview: new bill"
                TeachAction.BILL_PAID -> "Preview: bill payment"
                TeachAction.IGNORE -> "Preview: ignore SMS"
            }
            val previewText = when (selectedAction) {
                TeachAction.CREDIT, TeachAction.DEBIT, TeachAction.ATM, TeachAction.BALANCE_ONLY -> {
                    val target = fromAccountId?.let { id -> accounts.firstOrNull { it.id == id }?.displayName ?: "Selected account" } ?: "Select an account"
                    val amountTextPreview = amountText.ifBlank { "—" }
                    val dateTextPreview = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(selectedDate))
                    val balancePreview = if (selectedAction == TeachAction.BALANCE_ONLY) balanceText.ifBlank { "—" } else balanceText.ifBlank { "No balance" }
                    "$target • Tk $amountTextPreview • $dateTextPreview • Balance: $balancePreview"
                }
                TeachAction.TRANSFER -> {
                    val from = fromAccountId?.let { id -> accounts.firstOrNull { it.id == id }?.displayName ?: "Selected account" } ?: "Select from account"
                    val to = toAccountId?.let { id -> accounts.firstOrNull { it.id == id }?.displayName ?: "Select to account" } ?: "Select to account"
                    "$from → $to • Tk ${amountText.ifBlank { "—" }} • ${SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(selectedDate))}"
                }
                TeachAction.BILL, TeachAction.BILL_PAID -> {
                    val card = selectedCardId?.let { id -> creditCards.firstOrNull { it.id == id }?.displayName ?: "Selected card" } ?: "Select a card"
                    "$card • Tk ${amountText.ifBlank { "—" }} • ${SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(selectedDate))}"
                }
                TeachAction.IGNORE -> "This SMS will be marked as ignored and hidden from review."
            }

            if (selectedAction != TeachAction.IGNORE) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(previewTitle, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(previewText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (validationWarnings.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "${actionInsight.confidence} confidence • ${actionInsight.reasons.firstOrNull() ?: "Review suggested"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        validationWarnings.forEach { warning ->
                            Text(
                                text = "• $warning",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "${actionInsight.confidence} confidence",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        actionInsight.reasons.forEach { reason ->
                            Text(
                                text = "• $reason",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            Button(
                onClick = {
                    if (!isValid) return@Button
                    val parsedBalance = balanceText.replace(",", "").toDoubleOrNull()
                    when (selectedAction) {
                TeachAction.IGNORE -> onIgnore()
                TeachAction.CREDIT -> {
                    val amt = amount ?: return@Button
                    val accountId = fromAccountId ?: return@Button
                    onTeachCredit(
                        accountId, amt, selectedDate,
                        counterparty, category, parsedBalance, saveRule, requiredKeywords, excludedKeywords
                    )
                }
                TeachAction.DEBIT -> {
                    val amt = amount ?: return@Button
                    val accountId = fromAccountId ?: return@Button
                    onTeachDebit(
                        accountId, amt, selectedDate,
                        counterparty, category, parsedBalance, saveRule, requiredKeywords, excludedKeywords
                    )
                }
                TeachAction.TRANSFER -> {
                    val amt = amount ?: return@Button
                    val fromAccId = fromAccountId ?: return@Button
                    val toAccId = toAccountId ?: return@Button
                    onTeachTransfer(
                        fromAccId, toAccId, amt, selectedDate,
                        saveRule, requiredKeywords, excludedKeywords
                    )
                }
                TeachAction.ATM -> {
                    val amt = amount ?: return@Button
                    val accountId = fromAccountId ?: return@Button
                    onTeachAtm(
                        accountId, amt, selectedDate,
                        saveRule, requiredKeywords, excludedKeywords
                    )
                }
                TeachAction.BALANCE_ONLY -> {
                    val bal = balance ?: return@Button
                    val accountId = fromAccountId ?: return@Button
                    onTeachBalanceOnly(
                        accountId, bal, selectedDate,
                        saveRule, requiredKeywords, excludedKeywords
                    )
                }
                TeachAction.BILL -> {
                    val amt = amount ?: return@Button
                    val cardId = selectedCardId ?: return@Button
                    onTeachCreateBill(
                        cardId, amt, minDue, billDueDate, billPeriod, selectedDate,
                        saveRule, requiredKeywords, excludedKeywords
                    )
                }
                TeachAction.BILL_PAID -> {
                    val amt = amount ?: return@Button
                    val cardId = selectedCardId ?: return@Button
                    onTeachBillPaid(
                        cardId, amt, selectedDate,
                        saveRule, requiredKeywords, excludedKeywords
                    )
                }
            }
                },
                enabled = isValid,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (selectedAction == TeachAction.IGNORE) "Ignore this SMS" else "Save")
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

    if (showBlockSenderConfirm) {
        AlertDialog(
            onDismissRequest = { showBlockSenderConfirm = false },
            title = { Text("Block this sender?") },
            text = { Text("All future SMS from \"${record.address ?: "Unknown"}\" will be automatically ignored. You can disable this rule later in the Rules tab.") },
            confirmButton = {
                TextButton(onClick = {
                    onBlockSender()
                    showBlockSenderConfirm = false
                }) { Text("Block") }
            },
            dismissButton = {
                TextButton(onClick = { showBlockSenderConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showCreateAccount) {
        CreateAccountDialog(
            accountMasks = candidates.accountMasks,
            onDismiss = { showCreateAccount = false },
            onConfirm = { name, kind, bankName, mask, currency, includeInTotal ->
                onCreateAccount(name, kind, bankName, mask, currency, includeInTotal) { createdId ->
                    fromAccountId = createdId
                }
                showCreateAccount = false
            }
        )
    }
}

@Composable
private fun TargetChoiceRow(
    label: String,
    tag: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f))
        Surface(
            color = if (tag == "Card") MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(999.dp)
        ) {
            Text(
                text = tag,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = if (tag == "Card") MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun CreditCardPicker(
    creditCards: List<CreditCard>,
    selectedCardId: Long?,
    onSelected: (Long) -> Unit
) {
    if (creditCards.isEmpty()) {
        Text(
            "No credit cards yet. Add one from the Cards tab first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 180.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Credit Cards",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        creditCards.forEach { card ->
            TargetChoiceRow(
                label = card.displayName,
                tag = "Card",
                selected = selectedCardId == card.id,
                onClick = { onSelected(card.id) }
            )
        }
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
