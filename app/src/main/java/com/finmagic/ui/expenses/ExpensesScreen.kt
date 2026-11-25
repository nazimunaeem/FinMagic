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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finmagic.data.repository.ExpenseItem
import com.finmagic.data.repository.CategoryTotal
import com.finmagic.data.sms.parsers.TxnCategory
import com.finmagic.ui.accounts.categoryLabel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val bdtFormat = { amount: Double -> "৳${String.format(Locale.US, "%,.2f", amount)}" }
private val dayFormat = SimpleDateFormat("dd MMM", Locale.US)
private val dateTimeFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US)
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
fun ExpensesScreen(
    onChartsClick: (() -> Unit)? = null,
    viewModel: ExpensesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val availableYears by viewModel.availableYears.collectAsStateWithLifecycle()
    val categoryNicknames by viewModel.categoryNicknames.collectAsStateWithLifecycle()

    var categoryFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var activeItem by remember { mutableStateOf<ExpenseItem?>(null) }

    val categoryNames = remember(state.stats) {
        state.stats?.categories?.map { it.category } ?: emptyList()
    }
    val filteredItems = remember(state.items, categoryFilter, searchQuery) {
        val q = searchQuery.trim()
        state.items.filter { item ->
            (categoryFilter == null || item.category == categoryFilter) &&
                (q.isEmpty() || listOf(item.title, item.subtitle, item.category).any {
                    it != null && it.contains(q, ignoreCase = true)
                })
        }
    }

    // Refresh data when the screen re-enters composition (e.g. navigating back).
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Expenses") },
                actions = {
                    if (onChartsClick != null) {
                        IconButton(
                            onClick = onChartsClick,
                            modifier = Modifier.testTag("expenses_open_charts_button")
                        ) {
                            Icon(Icons.Filled.BarChart, contentDescription = "Open Charts")
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item { MonthYearSelector(state, viewModel, availableYears) }

            if (state.loading) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(2.dp))
                    )
                }
            }

            state.stats?.let { stats ->
                if (stats.count > 0 || stats.total > 0) {
                    item {
                        StatsCard(
                            stats = stats,
                            categoryNicknames = categoryNicknames
                        )
                    }
                }
                if (stats.categories.isNotEmpty() || searchQuery.isNotEmpty()) {
                    item {
                        ExpenseFilters(
                            categories = categoryNames,
                            selectedCategory = categoryFilter,
                            onCategorySelected = { categoryFilter = it },
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            categoryNicknames = categoryNicknames
                        )
                    }
                }
                if (stats.categories.isNotEmpty()) {
                    item {
                        CategoryBreakdown(
                            categories = stats.categories,
                            prevCategories = stats.prevCategories,
                            total = stats.total,
                            selectedCategory = categoryFilter,
                            onCategoryClick = { categoryFilter = it },
                            categoryNicknames = categoryNicknames
                        )
                    }
                }
            }

            if (filteredItems.isEmpty()) {
                item {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = if (state.items.isEmpty())
                            "No expenses recorded for this period."
                        else
                            "No matching expenses.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                val grouped = filteredItems.groupBy {
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
                        ExpenseRowItem(
                            item = item,
                            categoryNicknames = categoryNicknames,
                            onTap = if (item.accountTxnId != null)
                                { { activeItem = item } }
                            else null
                        )
                    }
                }
            }
        }
    }

    activeItem?.let { item ->
        val id = item.accountTxnId
        if (id != null) {
            ExpenseOptionsDialog(
                item = item,
                onDismiss = { activeItem = null },
                onSetExpense = { include ->
                    viewModel.setExpenseOverride(
                        id,
                        TxnCategory.targetOverride(include, item.direction, item.rawCategory)
                    )
                    activeItem = null
                }
            )
        }
    }
}

@Composable
private fun MonthYearSelector(
    state: ExpensesUiState,
    viewModel: ExpensesViewModel,
    availableYears: List<Int>
) {
    var showPicker by remember { mutableStateOf(false) }
    val nowCal = remember { Calendar.getInstance() }
    val canStepNext = state.year < nowCal.get(Calendar.YEAR) ||
        (state.year == nowCal.get(Calendar.YEAR) && state.month < nowCal.get(Calendar.MONTH) + 1)

    Card(
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
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
            IconButton(onClick = viewModel::stepNext, enabled = canStepNext) {
                Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Next month")
            }
        }
    }

    if (showPicker && availableYears.isNotEmpty()) {
        MonthYearPickerDialog(
            selectedYear = state.year,
            selectedMonth = state.month,
            minYear = availableYears.last(),
            maxYear = availableYears.first(),
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
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
private fun StatsCard(
    stats: com.finmagic.data.repository.ExpenseStats,
    categoryNicknames: Map<String, String> = emptyMap()
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
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
            if (stats.daysInMonth > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (stats.daysElapsed.toFloat() / stats.daysInMonth).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${stats.daysElapsed} of ${stats.daysInMonth} days elapsed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
            stats.topCategory?.let { top ->
                val pct = if (stats.total > 0) top.total / stats.total * 100.0 else 0.0
                Spacer(Modifier.height(4.dp))
                Text(
                    "Top: ${categoryLabel(top.category, categoryNicknames)} · ${String.format(Locale.US, "%.0f", pct)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun ExpenseFilters(
    categories: List<String>,
    selectedCategory: String?,
    onCategorySelected: (String?) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    categoryNicknames: Map<String, String> = emptyMap()
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search expenses…", style = MaterialTheme.typography.bodySmall) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        if (categories.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { onCategorySelected(null) },
                        label = { Text("All") }
                    )
                }
                items(categories) { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { onCategorySelected(if (selectedCategory == cat) null else cat) },
                        label = { Text(categoryLabel(cat, categoryNicknames)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryBreakdown(
    categories: List<com.finmagic.data.repository.CategoryTotal>,
    prevCategories: List<CategoryTotal>,
    total: Double,
    selectedCategory: String?,
    onCategoryClick: (String?) -> Unit,
    categoryNicknames: Map<String, String> = emptyMap()
) {
    val prevByCat = prevCategories.associate { it.category to it.total }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("By category", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            categories.forEach { cat ->
                val fraction = if (total > 0) (cat.total / total).toFloat() else 0f
                val selected = selectedCategory == cat.category
                val prev = prevByCat[cat.category]
                val delta = prev?.takeIf { it > 0 }
                    ?.let { (cat.total - it) / it * 100.0 }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onCategoryClick(if (selected) null else cat.category) }
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            categoryLabel(cat.category, categoryNicknames),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
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
                    Spacer(Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val deltaColor = when {
                            delta == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            delta >= 0 -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }
                        val deltaText = when {
                            delta == null -> if (prevByCat.containsKey(cat.category)) "same as last month" else "new this month"
                            delta >= 0 -> "▲ ${String.format(Locale.US, "%.1f", delta)}% vs last mo"
                            else -> "▼ ${String.format(Locale.US, "%.1f", kotlin.math.abs(delta))}% vs last mo"
                        }
                        Text(
                            deltaText,
                            style = MaterialTheme.typography.labelSmall,
                            color = deltaColor
                        )
                        Text(
                            "${cat.count} txn${if (cat.count == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpenseRowItem(
    item: ExpenseItem,
    onTap: (() -> Unit)? = null,
    categoryNicknames: Map<String, String> = emptyMap()
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title.ifBlank { "Expense" }, style = MaterialTheme.typography.bodyLarge)
            item.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                if (item.source == "card") "Card" else categoryLabel(item.category, categoryNicknames),
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

@Composable
private fun ExpenseOptionsDialog(
    item: ExpenseItem,
    onDismiss: () -> Unit,
    onSetExpense: (Boolean) -> Unit
) {
    var include by remember {
        mutableStateOf(TxnCategory.countsAsExpense(item.isExpenseOverride, item.direction, item.rawCategory))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Expense Options") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    item.title.ifBlank { "Expense" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    bdtFormat(item.amount),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    dateTimeFormat.format(Date(item.occurredAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { include = !include },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Count as expense",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = include, onCheckedChange = { include = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSetExpense(include) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}