package com.finmagic.ui.smsreview

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.data.local.entity.SmsRecord
import com.finmagic.data.local.entity.SmsTeachingRule
import com.finmagic.ui.theme.StatusSafe
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SmsReviewScreen(
    viewModel: SmsReviewViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val unparsedSms by viewModel.unparsedSms.collectAsStateWithLifecycle()
    val allSms by viewModel.allSms.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val creditCards by viewModel.creditCards.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val selectedRecord by viewModel.selectedRecord.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var showRuleTest by remember { mutableStateOf(false) }
    var testInitialBody by remember { mutableStateOf("") }
    var testInitialSender by remember { mutableStateOf("") }

    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SMS Recognition") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("sms_review_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            testInitialBody = ""
                            testInitialSender = ""
                            showRuleTest = true
                        },
                        modifier = Modifier.testTag("sms_review_test_rules")
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Test Rules")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                Tab(
                    selected = selectedTab == ReviewTab.ALL,
                    onClick = { viewModel.selectTab(ReviewTab.ALL) },
                    text = { Text("All (${allSms.size})") }
                )
                Tab(
                    selected = selectedTab == ReviewTab.UNPARSED,
                    onClick = { viewModel.selectTab(ReviewTab.UNPARSED) },
                    text = { Text("Unparsed (${unparsedSms.size})") }
                )
                Tab(
                    selected = selectedTab == ReviewTab.RULES,
                    onClick = { viewModel.selectTab(ReviewTab.RULES) },
                    text = { Text("Rules (${rules.size})") }
                )
            }

            when (selectedTab) {
                ReviewTab.ALL -> {
                    if (allSms.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No SMS records",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(allSms, key = { it.id }) { record ->
                                AllSmsItem(
                                    record = record,
                                    onTap = { viewModel.selectRecord(record) },
                                    onTestRules = {
                                        testInitialBody = record.body
                                        testInitialSender = record.address.orEmpty()
                                        showRuleTest = true
                                    },
                                    onCopyBody = {
                                        clipboard.setText(AnnotatedString(record.body))
                                        scope.launch { snackbarHostState.showSnackbar("SMS body copied") }
                                    }
                                )
                            }
                        }
                    }
                }
                ReviewTab.UNPARSED -> {
                    if (unparsedSms.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No unparsed SMS",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(unparsedSms, key = { it.id }) { record ->
                                UnparsedSmsItem(
                                    record = record,
                                    onTap = { viewModel.selectRecord(record) },
                                    onTestRules = {
                                        testInitialBody = record.body
                                        testInitialSender = record.address.orEmpty()
                                        showRuleTest = true
                                    },
                                    onCopyBody = {
                                        clipboard.setText(AnnotatedString(record.body))
                                        scope.launch { snackbarHostState.showSnackbar("SMS body copied") }
                                    }
                                )
                            }
                        }
                    }
                }
                ReviewTab.RULES -> {
                    RulesList(
                        rules = rules,
                        onToggle = { viewModel.toggleRule(it) },
                        onDelete = { viewModel.deleteRule(it) },
                        onDisableAll = { viewModel.disableAllRules() },
                        onEnableAll = { viewModel.enableAllRules() },
                        onApplyHistorical = { viewModel.applyRuleToHistorical(it) }
                    )
                }
            }
        }
    }

    if (showRuleTest) {
        RuleTestScreen(
            rules = rules,
            initialBody = testInitialBody,
            initialSender = testInitialSender,
            onDismiss = { showRuleTest = false }
        )
    }

    selectedRecord?.let { record ->
        TeachSmsSheet(
            record = record,
            accounts = accounts,
            creditCards = creditCards,
            onDismiss = { viewModel.selectRecord(null) },
            onTeachCredit = { accountId, amount, occurredAt, counterparty, category, balanceAfter, saveRule, req, exc ->
                viewModel.teachSms(record, accountId, AccountTransaction.DIRECTION_IN, amount, occurredAt, counterparty, category, balanceAfter, saveRule, req, exc)
            },
            onTeachDebit = { accountId, amount, occurredAt, counterparty, category, balanceAfter, saveRule, req, exc ->
                viewModel.teachSms(record, accountId, AccountTransaction.DIRECTION_OUT, amount, occurredAt, counterparty, category, balanceAfter, saveRule, req, exc)
            },
            onTeachTransfer = { fromAccountId, toAccountId, amount, occurredAt, saveRule, req, exc ->
                viewModel.teachTransfer(record, fromAccountId, toAccountId, amount, occurredAt, saveRule, req, exc)
            },
            onTeachAtm = { accountId, amount, occurredAt, saveRule, req, exc ->
                viewModel.teachAtmWithdrawal(record, accountId, amount, occurredAt, saveRule, req, exc)
            },
            onTeachBalanceOnly = { accountId, balance, occurredAt, saveRule, req, exc ->
                viewModel.teachBalanceOnly(record, accountId, balance, occurredAt, saveRule, req, exc)
            },
            onTeachCreateBill = { cardId, totalDue, minDue, dueDate, billPeriod, occurredAt, saveRule, req, exc ->
                viewModel.teachCreateBill(record, cardId, totalDue, minDue, dueDate, billPeriod, occurredAt, saveRule, req, exc)
            },
            onTeachBillPaid = { cardId, amount, occurredAt, saveRule, req, exc ->
                viewModel.teachBillPaid(record, cardId, amount, occurredAt, saveRule, req, exc)
            },
            onIgnore = { viewModel.ignoreSms(record) },
            onBlockSender = { viewModel.ignoreSimilar(record.address) },
            onCreateAccount = { name, kind, bankName, mask, currency, includeInTotal, onCreated ->
                viewModel.createAccount(name, kind, bankName, mask, currency, includeInTotal, onCreated)
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UnparsedSmsItem(
    record: SmsRecord,
    onTap: () -> Unit,
    onTestRules: () -> Unit,
    onCopyBody: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onTap,
                    onLongClick = { showMenu = true }
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("sms_unparsed_item_${record.id}")
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = record.address ?: "Unknown",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(record.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = record.body,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Tap to teach • Hold for options",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Test Rules") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                onClick = {
                    showMenu = false
                    onTestRules()
                }
            )
            DropdownMenuItem(
                text = { Text("Copy Body") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                onClick = {
                    showMenu = false
                    onCopyBody()
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AllSmsItem(
    record: SmsRecord,
    onTap: () -> Unit,
    onTestRules: () -> Unit,
    onCopyBody: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onTap,
                    onLongClick = { showMenu = true }
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("sms_all_item_${record.id}")
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (record.isParsed) StatusSafe else MaterialTheme.colorScheme.error
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = record.address ?: "Unknown",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (record.isParsed) StatusSafe.copy(alpha = 0.12f) else MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = if (record.isParsed) "Parsed" else "Unparsed",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (record.isParsed) StatusSafe else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(record.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = record.body,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp)
            )
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Test Rules") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                onClick = {
                    showMenu = false
                    onTestRules()
                }
            )
            DropdownMenuItem(
                text = { Text("Copy Body") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                onClick = {
                    showMenu = false
                    onCopyBody()
                }
            )
        }
    }
}

@Composable
private fun RulesList(
    rules: List<SmsTeachingRule>,
    onToggle: (SmsTeachingRule) -> Unit,
    onDelete: (SmsTeachingRule) -> Unit,
    onDisableAll: () -> Unit,
    onEnableAll: () -> Unit,
    onApplyHistorical: (SmsTeachingRule) -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf<SmsTeachingRule?>(null) }
    var showDisableAllConfirm by remember { mutableStateOf(false) }
    var showApplyHistoricalConfirm by remember { mutableStateOf<SmsTeachingRule?>(null) }

    if (rules.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No saved rules",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        Column {
            val allEnabled = rules.all { it.enabled }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${rules.count { it.enabled }} of ${rules.size} enabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row {
                    if (allEnabled) {
                        TextButton(onClick = { showDisableAllConfirm = true }) {
                            Text("Disable all")
                        }
                    } else {
                        TextButton(onClick = onEnableAll) {
                            Text("Enable all")
                        }
                        TextButton(onClick = { showDisableAllConfirm = true }) {
                            Text("Disable all")
                        }
                    }
                }
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(rules, key = { it.id }) { rule ->
                    RuleItem(
                        rule = rule,
                        onToggle = { onToggle(rule) },
                        onDelete = { showDeleteConfirm = rule },
                        onApplyHistorical = { showApplyHistoricalConfirm = rule }
                    )
                }
            }
        }
    }

    showDeleteConfirm?.let { rule ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete rule?") },
            text = { Text("Delete \"${rule.name}\"? Existing transactions are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(rule)
                    showDeleteConfirm = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancel") }
            }
        )
    }

    if (showDisableAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDisableAllConfirm = false },
            title = { Text("Disable all rules?") },
            text = { Text("All rules will be disabled. No SMS will be automatically matched until rules are re-enabled.") },
            confirmButton = {
                TextButton(onClick = {
                    onDisableAll()
                    showDisableAllConfirm = false
                }) { Text("Disable all") }
            },
            dismissButton = {
                TextButton(onClick = { showDisableAllConfirm = false }) { Text("Cancel") }
            }
        )
    }

    showApplyHistoricalConfirm?.let { rule ->
        AlertDialog(
            onDismissRequest = { showApplyHistoricalConfirm = null },
            title = { Text("Apply to past SMS?") },
            text = { Text("This will create transactions for all unparsed SMS that match \"${rule.name}\". This action cannot be easily undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onApplyHistorical(rule)
                    showApplyHistoricalConfirm = null
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showApplyHistoricalConfirm = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun RuleItem(
    rule: SmsTeachingRule,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onApplyHistorical: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.name.ifBlank { rule.actionLabel },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = rule.summaryKeywords,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(
                checked = rule.enabled,
                onCheckedChange = { onToggle() }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = rule.actionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rule.matchCount > 0) {
                    Text(
                        text = "Matched ${rule.matchCount}×",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                TextButton(onClick = onApplyHistorical) {
                    Text("Apply to past SMS", style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        HorizontalDivider()
    }
}
