package com.finmagic.ui.expenses

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finmagic.data.repository.ExpenseItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val bdtFormat = { amount: Double -> "Tk ${String.format(Locale.US, "%,.2f", amount)}" }
private val dayFormat = SimpleDateFormat("dd MMM", Locale.US)
private val monthName = { m: Int ->
    val cal = Calendar.getInstance().apply { set(Calendar.MONTH, m - 1) }
    SimpleDateFormat("MMMM", Locale.US).format(cal.time)
}
private val monthShort = { m: Int ->
    val cal = Calendar.getInstance().apply { set(Calendar.MONTH, m - 1) }
    SimpleDateFormat("MMM", Locale.US).format(cal.time)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(viewModel: ExpensesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Expenses") },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { MonthYearSelector(state, viewModel) }

            state.stats?.let { stats ->
                item { StatsCard(stats) }
                if (stats.categories.isNotEmpty()) {
                    item { CategoryBreakdown(stats.categories, stats.total) }
                }
            }

            if (state.items.isEmpty()) {
                item {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "No expenses recorded for this period.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                val grouped = state.items.groupBy {
                    dayFormat.format(Date(it.occurredAt))
                }
                grouped.forEach { (day, list) ->
                    item {
                        Text(
                            day,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    items(list, key = { it.uid }) { item ->
                        ExpenseRowItem(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthYearSelector(state: ExpensesUiState, viewModel: ExpensesViewModel) {
    var showPicker by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = viewModel::stepPrevious) {
                Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
            }
            Text(
                "${monthName(state.month)} ${state.year}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { showPicker = true }
            )
            IconButton(onClick = viewModel::stepNext) {
                Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Next month")
            }
        }
    }

    if (showPicker) {
        MonthYearPickerDialog(
            selectedYear = state.year,
            selectedMonth = state.month,
            minYear = viewModel.availableYears.last(),
            maxYear = viewModel.availableYears.first(),
            onPick = { year, month ->
                viewModel.setMonth(year, month)
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

/** Year stepper plus a 3x4 month grid; future months of the current year are disabled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonthYearPickerDialog(
    selectedYear: Int,
    selectedMonth: Int,
    minYear: Int,
    maxYear: Int,
    onPick: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var viewYear by remember { mutableStateOf(selectedYear) }
    val nowCal = Calendar.getInstance()
    val thisYear = nowCal.get(Calendar.YEAR)
    val thisMonth = nowCal.get(Calendar.MONTH) + 1

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(enabled = viewYear > minYear, onClick = { viewYear-- }) {
                    Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Previous year")
                }
                Text(
                    viewYear.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(enabled = viewYear < maxYear, onClick = { viewYear++ }) {
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Next year")
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..12).chunked(3).forEach { rowMonths ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        rowMonths.forEach { m ->
                            FilterChip(
                                selected = viewYear == selectedYear && m == selectedMonth,
                                enabled = !(viewYear == thisYear && m > thisMonth),
                                onClick = { onPick(viewYear, m) },
                                label = { Text(monthShort(m)) }
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun StatsCard(stats: com.finmagic.data.repository.ExpenseStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Total spent", style = MaterialTheme.typography.labelMedium)
            Text(
                bdtFormat(stats.total),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${stats.count} transactions",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Avg ${bdtFormat(stats.dailyAverage)}/day",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(8.dp))
            val delta = if (stats.prevTotal > 0) {
                (stats.total - stats.prevTotal) / stats.prevTotal * 100.0
            } else null
            delta?.let {
                val up = it >= 0
                Text(
                    "vs last month: ${if (up) "+" else ""}${String.format(Locale.US, "%.1f", it)}% " +
                        "(${bdtFormat(stats.prevTotal)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (up) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            } ?: Text(
                "vs last month: n/a",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun CategoryBreakdown(categories: List<com.finmagic.data.repository.CategoryTotal>, total: Double) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("By category", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            categories.forEach { cat ->
                val fraction = if (total > 0) (cat.total / total).toFloat() else 0f
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(cat.category.ifBlank { "Expense" }, style = MaterialTheme.typography.bodyMedium)
                        Text(bdtFormat(cat.total), style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(2.dp))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpenseRowItem(item: ExpenseItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title.ifBlank { "Expense" }, style = MaterialTheme.typography.bodyLarge)
            item.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                if (item.source == "card") "Card" else item.category.ifBlank { "Expense" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Text(
            bdtFormat(item.amount),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.error
        )
    }
    HorizontalDivider()
}
