package com.finmagic.ui.smsreview

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
import com.finmagic.data.local.entity.SmsTeachingRule
import com.finmagic.data.sms.SmsTeachingEngine
import com.finmagic.data.sms.ExtractedFields
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleTestScreen(
    rules: List<SmsTeachingRule>,
    onDismiss: () -> Unit,
    initialBody: String = "",
    initialSender: String = ""
) {
    var testSmsBody by remember(initialBody) { mutableStateOf(initialBody) }
    var testSmsSender by remember(initialSender) { mutableStateOf(initialSender) }
    var testResult by remember(initialBody, initialSender) { mutableStateOf<TestResult?>(null) }
    var showValidationError by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf("") }

    fun evaluateRules(body: String, senderInput: String): TestResult {
        val normalizedBody = SmsTeachingEngine.normalizeText(body)
        val sender = senderInput.trim().uppercase().takeIf { it.isNotBlank() }
        val match = SmsTeachingEngine.findBestMatch(
            rules.filter { it.enabled },
            sender,
            normalizedBody
        )
        val extracted = SmsTeachingEngine.extractAllFields(body)
        val matching = rules.filter { rule ->
            SmsTeachingEngine.matchesRule(rule, sender, normalizedBody)
        }
        return TestResult(match = match, extractedFields = extracted, matchedRules = matching)
    }

    LaunchedEffect(initialBody, initialSender, rules) {
        if (initialBody.isNotBlank()) {
            testResult = evaluateRules(initialBody, initialSender)
        }
    }

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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Test SMS Rule",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Text(
                "Paste an SMS body or sender to see which rules would match",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // SMS Input
            Text("SMS Body", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = testSmsBody,
                onValueChange = { testSmsBody = it },
                label = { Text("Paste SMS body here") },
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 6
            )

            Text("Sender (optional)", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = testSmsSender,
                onValueChange = { testSmsSender = it },
                label = { Text("e.g. 16216") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    if (testSmsBody.isBlank()) {
                        validationError = "Please enter an SMS body to test"
                        showValidationError = true
                        return@Button
                    }
                    showValidationError = false
                    testResult = evaluateRules(testSmsBody, testSmsSender)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = testSmsBody.isNotBlank()
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Test Rules")
            }

            // Show validation error
            if (showValidationError) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            validationError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showValidationError = false }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // Show test results
            testResult?.let { result ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                Text(
                    "Test Results",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                // Best match result
                when {
                    result.match != null -> {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "✅ Rule matched",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(8.dp))
                                Text("Rule: ${result.match.name}")
                                Text("Action: ${result.match.actionLabel}")
                                Text("Account ID: ${result.match.accountId}")
                            }
                        }
                    }
                    result.matchedRules.isNotEmpty() -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "⚠️ Rule conflict",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                Text("${result.matchedRules.size} rules match but produce different actions/accounts")
                                Text("Result: Needs Review")
                            }
                        }
                    }
                    else -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "❌ No rules matched",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                Text("No enabled rules match this SMS")
                                Text("Result: Unparsed / Needs Teaching")
                            }
                        }
                    }
                }

                // Extracted fields
                if (result.extractedFields.amount != null || 
                    result.extractedFields.date != null || 
                    result.extractedFields.balance != null ||
                    result.extractedFields.accountMask != null ||
                    result.extractedFields.counterparty != null) {
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        "Extracted Fields",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    
                    val fields = mutableListOf<String>()
                    result.extractedFields.amount?.let {
                        fields.add("Amount: ৳${"%,.2f".format(it)}")
                    }
                    result.extractedFields.date?.let {
                        fields.add("Date: ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(it))}")
                    }
                    result.extractedFields.balance?.let {
                        fields.add("Balance: ৳${"%,.2f".format(it)}")
                    }
                    result.extractedFields.accountMask?.let {
                        fields.add("Account: $it")
                    }
                    result.extractedFields.counterparty?.let {
                        fields.add("Counterparty: $it")
                    }
                    
                    if (fields.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                fields.forEach { field ->
                                    Text(field, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }

                // All matching rules (if more than 1)
                if (result.matchedRules.size > 1) {
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        "All Matching Rules (${result.matchedRules.size})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    
                    result.matchedRules.forEach { rule ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(rule.name, style = MaterialTheme.typography.labelLarge)
                                Text("Action: ${rule.actionLabel}", style = MaterialTheme.typography.bodyMedium)
                                Text("Account: ${rule.accountId}", style = MaterialTheme.typography.bodySmall)
                                if (rule.senderKey != null) {
                                    Text("Sender: ${rule.senderKey}", style = MaterialTheme.typography.bodySmall)
                                }
                                if (rule.requiredKeywordsList.isNotEmpty()) {
                                    Text("Keywords: ${rule.requiredKeywordsList.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }

}

private data class TestResult(
    val match: SmsTeachingRule?,
    val extractedFields: ExtractedFields,
    val matchedRules: List<SmsTeachingRule>
)