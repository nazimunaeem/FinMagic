package com.finmagic.ui.smsreview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.data.local.entity.SmsRecord
import com.finmagic.data.local.entity.SmsTeachingRule
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsReviewScreen(
    viewModel: SmsReviewViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val unparsedSms by viewModel.unparsedSms.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val selectedRecord by viewModel.selectedRecord.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

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
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
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
                            items(unparsedSms) { record ->
                                UnparsedSmsItem(
                                    record = record,
                                    onTap = { viewModel.selectRecord(record) },
                                    onIgnore = { viewModel.ignoreSms(record) }
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
                        onDisableAll = { viewModel.disableAllRules() }
                    )
                }
            }
        }
    }

    selectedRecord?.let { record ->
        TeachSmsSheet(
            record = record,
            accounts = accounts,
            onDismiss = { viewModel.selectRecord(null) },
            onTeachCredit = { accountId, amount, occurredAt, counterparty, category, saveRule, req, exc ->
                viewModel.teachSms(record, accountId, AccountTransaction.DIRECTION_IN, amount, occurredAt, counterparty, category, saveRule, req, exc)
            },
            onTeachDebit = { accountId, amount, occurredAt, counterparty, category, saveRule, req, exc ->
                viewModel.teachSms(record, accountId, AccountTransaction.DIRECTION_OUT, amount, occurredAt, counterparty, category, saveRule, req, exc)
            },
            onTeachAtm = { accountId, amount, occurredAt, saveRule, req, exc ->
                viewModel.teachAtmWithdrawal(record, accountId, amount, occurredAt, saveRule, req, exc)
            },
            onIgnore = { viewModel.ignoreSms(record) },
            onIgnoreSimilar = { viewModel.ignoreSimilar(record.address) },
            onCreateAccount = { name, kind, bankName, mask, currency, includeInTotal ->
                viewModel.createAccount(name, kind, bankName, mask, currency, includeInTotal) { }
            }
        )
    }
}

@Composable
private fun UnparsedSmsItem(
    record: SmsRecord,
    onTap: () -> Unit,
    onIgnore: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 12.dp)
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
            text = "No built-in parser matched",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun RulesList(
    rules: List<SmsTeachingRule>,
    onToggle: (SmsTeachingRule) -> Unit,
    onDelete: (SmsTeachingRule) -> Unit,
    onDisableAll: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf<SmsTeachingRule?>(null) }

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
                TextButton(onClick = onDisableAll) {
                    Text("Disable all")
                }
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(rules) { rule ->
                    RuleItem(
                        rule = rule,
                        onToggle = { onToggle(rule) },
                        onDelete = { showDeleteConfirm = rule }
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
}

@Composable
private fun RuleItem(
    rule: SmsTeachingRule,
    onToggle: () -> Unit,
    onDelete: () -> Unit
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
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = rule.actionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Row {
                if (rule.matchCount > 0) {
                    Text(
                        text = "Matched ${rule.matchCount}×",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp)
                    )
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
