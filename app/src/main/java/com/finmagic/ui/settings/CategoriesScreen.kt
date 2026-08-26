package com.finmagic.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finmagic.data.sms.parsers.TxnCategory

private val DETECTED_CATEGORIES = listOf(
    TxnCategory.ATM, TxnCategory.CASH_DEPOSIT, TxnCategory.INTEREST, TxnCategory.DPS,
    TxnCategory.FUND_TRANSFER, TxnCategory.CARD_PAYMENT, TxnCategory.CASH_IN,
    TxnCategory.PAYMENT, TxnCategory.RECEIVED, TxnCategory.BILL_PAY,
    TxnCategory.NPSB, TxnCategory.CREDIT, TxnCategory.MANUAL, TxnCategory.ADJUSTMENT
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val expenseCats by viewModel.cashExpenseCategories.collectAsStateWithLifecycle()
    val incomeCats by viewModel.cashIncomeCategories.collectAsStateWithLifecycle()
    val nicknames by viewModel.categoryNicknames.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Categories", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                CategoryManagerSection(
                    title = "Cash Entry — Expense Categories",
                    categories = expenseCats,
                    onAdd = viewModel::addCashExpenseCategory,
                    onRename = viewModel::renameCashExpenseCategory,
                    onDelete = viewModel::removeCashExpenseCategory
                )
            }
            item {
                CategoryManagerSection(
                    title = "Cash Entry — Income Categories",
                    categories = incomeCats,
                    onAdd = viewModel::addCashIncomeCategory,
                    onRename = viewModel::renameCashIncomeCategory,
                    onDelete = viewModel::removeCashIncomeCategory
                )
            }
            item {
                Text(
                    "Category Display Nicknames",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            items(DETECTED_CATEGORIES) { category ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = category,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(120.dp)
                    )
                    OutlinedTextField(
                        value = nicknames[category] ?: "",
                        onValueChange = { viewModel.setCategoryNickname(category, it) },
                        placeholder = { Text(category) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryManagerSection(
    title: String,
    categories: List<String>,
    onAdd: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var addText by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Add")
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(categories) { cat ->
                    AssistChip(
                        onClick = {
                            editing = cat
                            editText = cat
                        },
                        label = { Text(cat) },
                        trailingIcon = {
                            IconButton(onClick = { onDelete(cat) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete $cat",
                                    modifier = Modifier
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add Category") },
            text = {
                OutlinedTextField(
                    value = addText,
                    onValueChange = { addText = it },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onAdd(addText)
                    addText = ""
                    showAdd = false
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Cancel") }
            }
        )
    }

    if (editing != null) {
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Rename Category") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    label = { Text("Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(editing!!, editText)
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onDelete(editing!!)
                    editing = null
                }) { Text("Delete") }
            }
        )
    }
}
