package com.finmagic.ui.debug

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finmagic.data.local.entity.AccountTransaction
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    viewModel: DebugViewModel = hiltViewModel()
) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val smsRecords by viewModel.smsRecords.collectAsStateWithLifecycle()
    val smsCount by viewModel.smsCount.collectAsStateWithLifecycle()
    val unparsedSms by viewModel.unparsedSms.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedRecord by remember { mutableStateOf<com.finmagic.data.local.entity.SmsRecord?>(null) }
    val listState = rememberLazyListState()

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisibleItem >= smsRecords.size - 5 && smsRecords.size < smsCount && !isLoadingMore
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && selectedTab == 1) {
            viewModel.loadMoreSms()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Activity Log") },
                actions = {
                    IconButton(onClick = { viewModel.scanAllSmsDebug() }) {
                        Icon(Icons.Default.Search, "Scan All SMS")
                    }
                    IconButton(onClick = {
                        if (selectedTab == 0) viewModel.clearLogs() else viewModel.clearSmsDatabase()
                    }) {
                        Icon(Icons.Default.DeleteSweep, "Clear")
                    }
                    IconButton(onClick = { viewModel.refreshLogs() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Text("Logs", modifier = Modifier.padding(12.dp))
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Text("SMS Database ($smsCount)", modifier = Modifier.padding(12.dp))
                }
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                    Text("Review (${unparsedSms.size})", modifier = Modifier.padding(12.dp))
                }
            }

            if (selectedTab == 0) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(logs) { log ->
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row {
                                Text(
                                    text = "[${log.level}]",
                                    color = if (log.level == "ERROR") Color.Red else Color.Gray,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp)),
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = log.tag ?: "", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            }
                            Text(text = log.message, fontSize = 14.sp)
                            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            } else if (selectedTab == 1) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(smsRecords) { record ->
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row {
                                Text(
                                    text = if (record.isParsed) "PARSED" else "NOT PARSED",
                                    color = if (record.isParsed) Color.Green else Color.Red,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = record.address ?: "Unknown", fontWeight = FontWeight.SemiBold)
                            }
                            Text(
                                text = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(record.timestamp)),
                                fontSize = 11.sp
                            )
                            Text(text = record.body, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                        }
                    }

                    if (isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }

                    if (!isLoadingMore && smsRecords.isNotEmpty() && smsRecords.size >= smsCount) {
                        item {
                            Text(
                                text = "All $smsCount records loaded",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            } else {
                if (unparsedSms.isEmpty()) {
                    Text("No unparsed SMS", modifier = Modifier.padding(24.dp))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(unparsedSms) { record ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedRecord = record }
                                    .padding(12.dp)
                            ) {
                                Text(record.address ?: "Unknown", fontWeight = FontWeight.Bold)
                                Text(record.body, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    selectedRecord?.let { record ->
        TeachSmsDialog(
            record = record,
            accounts = accounts,
            onDismiss = { selectedRecord = null },
            onTeach = { accountId, direction, amount, saveRule, required, excluded ->
                viewModel.teachOnce(record, accountId, direction, amount, saveRule, required, excluded)
                selectedRecord = null
            },
            onIgnore = {
                viewModel.ignoreSms(record)
                selectedRecord = null
            }
        )
    }
}

@Composable
private fun TeachSmsDialog(
    record: com.finmagic.data.local.entity.SmsRecord,
    accounts: List<com.finmagic.data.local.entity.Account>,
    onDismiss: () -> Unit,
    onTeach: (Long, String, Double, Boolean, String, String) -> Unit,
    onIgnore: () -> Unit
) {
    var amountText by remember { mutableStateOf(Regex("""(?:Tk|BDT)\\.?\\s*([\\d,]+(?:\\.\\d+)?)""", RegexOption.IGNORE_CASE).find(record.body)?.groupValues?.get(1) ?: "") }
    var selectedAccount by remember { mutableStateOf<Long?>(accounts.firstOrNull()?.id) }
    var direction by remember { mutableStateOf(com.finmagic.data.local.entity.AccountTransaction.DIRECTION_OUT) }
    var saveRule by remember { mutableStateOf(false) }
    var requiredKeywords by remember { mutableStateOf(record.address ?: "") }
    var excludedKeywords by remember { mutableStateOf("") }
    val amount = amountText.replace(",", "").toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Teach this SMS") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(record.body, maxLines = 6, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(amountText, { amountText = it }, label = { Text("Amount") }, singleLine = true)
                Row {
                    FilterChip(direction == AccountTransaction.DIRECTION_IN, { direction = AccountTransaction.DIRECTION_IN }, label = { Text("Credit") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(direction == AccountTransaction.DIRECTION_OUT, { direction = AccountTransaction.DIRECTION_OUT }, label = { Text("Debit") })
                }
                accounts.forEach { account ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { selectedAccount = account.id }) {
                        RadioButton(selectedAccount == account.id, { selectedAccount = account.id })
                        Text(account.displayName)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = saveRule, onCheckedChange = { saveRule = it })
                    Text("Save rule for future SMS")
                }
                if (saveRule) {
                    OutlinedTextField(
                        requiredKeywords,
                        { requiredKeywords = it },
                        label = { Text("Required keywords (comma separated)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        excludedKeywords,
                        { excludedKeywords = it },
                        label = { Text("Excluded keywords (optional)") },
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedAccount != null && amount != null && amount > 0,
                onClick = {
                    onTeach(selectedAccount!!, direction, amount!!, saveRule, requiredKeywords, excludedKeywords)
                }
            ) { Text("Apply once") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onIgnore) { Text("Ignore") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
