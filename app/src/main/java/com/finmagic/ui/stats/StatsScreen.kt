package com.finmagic.ui.stats

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finmagic.ui.accounts.AccountsViewModel
import com.finmagic.ui.accounts.ExpenseBreakdownCard
import com.finmagic.ui.accounts.monthTrendPercent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen() {
    val accountsVm: AccountsViewModel = hiltViewModel()
    val aState by accountsVm.uiState.collectAsStateWithLifecycle()
    val selectedYear by accountsVm.selectedYear.collectAsStateWithLifecycle()
    val selectedMonth by accountsVm.selectedMonth.collectAsStateWithLifecycle()

    val monthLabel = remember(selectedYear, selectedMonth) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, selectedYear)
            set(Calendar.MONTH, selectedMonth)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        SimpleDateFormat("MMMM yyyy", Locale.US).format(cal.time)
    }

    val net = aState.monthIn - aState.monthOut
    val previousNet = aState.prevMonthIn - aState.prevMonthOut
    val savingsRate = if (aState.monthIn > 0.0) {
        ((aState.monthIn - aState.monthOut) / aState.monthIn * 100.0).coerceIn(-100.0, 100.0)
    } else {
        0.0
    }
    val netTrend = monthTrendPercent(net, previousNet)

    val topExpense = aState.expensesByCategory.firstOrNull()
    val topIncome = aState.incomeByCategory.firstOrNull()
    val overviewTone = when {
        net > 0.0 -> "Healthy"
        net < 0.0 -> "Tight"
        else -> "Balanced"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Stats",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = 120.dp + padding.calculateBottomPadding()
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = {
                                val cal = Calendar.getInstance().apply {
                                    set(Calendar.YEAR, selectedYear)
                                    set(Calendar.MONTH, selectedMonth)
                                    set(Calendar.DAY_OF_MONTH, 1)
                                }
                                cal.add(Calendar.MONTH, -1)
                                accountsVm.setSelectedMonth(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
                        }

                        Text(
                            text = monthLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        IconButton(
                            onClick = {
                                val cal = Calendar.getInstance().apply {
                                    set(Calendar.YEAR, selectedYear)
                                    set(Calendar.MONTH, selectedMonth)
                                    set(Calendar.DAY_OF_MONTH, 1)
                                }
                                cal.add(Calendar.MONTH, 1)
                                accountsVm.setSelectedMonth(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
                        }
                    }
                }
            }

            item {
                SectionTitle("Overview")
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(
                            label = "Income",
                            value = String.format(Locale.US, "Tk %,.0f", aState.monthIn),
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.weight(1f)
                        )
                        StatTile(
                            label = "Expenses",
                            value = String.format(Locale.US, "Tk %,.0f", aState.monthOut),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(
                            label = "Net",
                            value = String.format(Locale.US, "Tk %,.0f", net),
                            tint = if (net >= 0.0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        StatTile(
                            label = "Savings",
                            value = String.format(Locale.US, "%.0f%%", savingsRate),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Month snapshot",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            val trendLabel = when {
                                netTrend > 0 -> "▲ ${String.format(Locale.US, "+%.1f%%", netTrend)} vs last month"
                                netTrend < 0 -> "▼ ${String.format(Locale.US, "%.1f%%", netTrend)} vs last month"
                                else -> "→ Flat vs last month"
                            }
                            Text(
                                text = "$overviewTone cash flow • total balance Tk ${String.format(Locale.US, "%,.0f", aState.totalBalance)}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = trendLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (aState.expensesByCategory.isNotEmpty() || aState.incomeByCategory.isNotEmpty()) {
                item {
                    SectionTitle("Spending breakdown")
                    ExpenseBreakdownCard(
                        expenses = aState.expensesByCategory,
                        income = aState.incomeByCategory,
                        monthLabel = monthLabel
                    )
                }
            }

            if (topExpense != null) {
                item {
                    SectionTitle("Top spending category")
                    CategorySummaryCard(
                        title = topExpense.category.ifBlank { "Uncategorized" },
                        subtitle = "${aState.expensesByCategory.size} categories tracked",
                        amount = topExpense.total,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (topIncome != null) {
                item {
                    SectionTitle("Top income source")
                    CategorySummaryCard(
                        title = topIncome.category.ifBlank { "Uncategorized" },
                        subtitle = "Largest inflow this month",
                        amount = topIncome.total,
                        tint = Color(0xFF2E7D32)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = tint
            )
        }
    }
}

@Composable
private fun CategorySummaryCard(
    title: String,
    subtitle: String,
    amount: Double,
    tint: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = String.format(Locale.US, "Tk %,.0f", amount),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = tint
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}
