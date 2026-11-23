package com.finmagic.ui.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finmagic.ui.accounts.categoryLabel
import com.finmagic.ui.theme.StatusSafe
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartsScreen(
    onBack: () -> Unit,
    viewModel: ChartsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Charts & Analytics",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("charts_back_button")
                    ) {
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
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. TOP CARD: Net Asset (Daily / Month / Year) + Overlay Comparison
            item(key = "net_asset_hero_chart") {
                NetAssetHeroChartCard(
                    state = state,
                    onSelectGranularity = viewModel::setGranularity,
                    onStepPrevious = viewModel::stepPrevious,
                    onStepNext = viewModel::stepNext,
                    onToggleCompare = { viewModel.setCompareOverlay(!state.compareOverlayEnabled) }
                )
            }

            // 2. Income vs Expense Bar Chart
            item(key = "cashflow_bar_chart") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Income vs Expense (${state.granularity.label})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = state.periodLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val netFlow = state.totalIncome - state.totalExpense
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = (if (netFlow >= 0) "+" else "") + formatExactTk(netFlow),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (netFlow >= 0) StatusSafe else MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = "Net Cashflow",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        IncomeExpenseGroupedBarChart(points = state.cashflowSeries)
                    }
                }
            }

            // 3. Category Breakdown Donut Chart + Comparison vs Prev Period
            item(key = "category_donut_chart") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Category Breakdown",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "With % change vs previous period",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = !state.categoryShowIncome,
                                    onClick = { viewModel.setCategoryShowIncome(false) },
                                    label = { Text("Expense", style = MaterialTheme.typography.labelSmall) }
                                )
                                FilterChip(
                                    selected = state.categoryShowIncome,
                                    onClick = { viewModel.setCategoryShowIncome(true) },
                                    label = { Text("Income", style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }

                        CategoryDonutChart(
                            slices = state.categorySlices,
                            nicknames = state.categoryNicknames,
                            totalLabel = if (state.categoryShowIncome) "Total In" else "Total Out"
                        )
                    }
                }
            }

            // 4. Net Asset Composition (Assets vs Liabilities)
            item(key = "allocation_mix_chart") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Net Asset Allocation",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        AssetLiabilityMixBars(slices = state.allocationSlices)
                    }
                }
            }

            // 5. Dedicated Period-to-Period Comparator (Month vs Month / Year vs Year)
            item(key = "period_comparator_card") {
                PeriodComparatorCard(
                    state = state,
                    onToggleYearlyMode = viewModel::setCompareYearlyMode,
                    onStepPeriodA = viewModel::stepPeriodA,
                    onStepPeriodB = viewModel::stepPeriodB,
                    onSwap = viewModel::swapComparisonPeriods
                )
            }
        }
    }
}

@Composable
private fun NetAssetHeroChartCard(
    state: ChartsUiState,
    onSelectGranularity: (ChartGranularity) -> Unit,
    onStepPrevious: () -> Unit,
    onStepNext: () -> Unit,
    onToggleCompare: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Headline Net Asset + delta pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "NET ASSET (${state.periodLabel})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = formatExactTk(state.currentNetAsset),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (state.currentNetAsset < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Assets ${formatExactTk(state.currentAssets)} • Liab −${formatExactTk(state.currentLiabilities)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val delta = state.periodChangeAmount
                    val deltaPositive = delta >= 0.0
                    val deltaColor = if (deltaPositive) StatusSafe else MaterialTheme.colorScheme.error
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = deltaColor.copy(alpha = 0.14f)
                    ) {
                        val pctPart = state.periodChangePercent?.let {
                            String.format(Locale.US, " (%s%.1f%%)", if (it >= 0) "+" else "", it)
                        }.orEmpty()
                        Text(
                            text = (if (deltaPositive) "+" else "") + formatCompactTk(delta) + pctPart,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = deltaColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    if (state.granularity != ChartGranularity.YEARLY) {
                        FilterChip(
                            selected = state.compareOverlayEnabled,
                            onClick = onToggleCompare,
                            leadingIcon = {
                                Icon(
                                    Icons.Default.CompareArrows,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = { Text("Compare", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("charts_compare_overlay_chip")
                        )
                    }
                }
            }

            // Granularity switcher: Daily | Month | Year
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ChartGranularity.entries.forEachIndexed { idx, gran ->
                    SegmentedButton(
                        selected = state.granularity == gran,
                        onClick = { onSelectGranularity(gran) },
                        shape = SegmentedButtonDefaults.itemShape(idx, ChartGranularity.entries.size),
                        modifier = Modifier.testTag("charts_granularity_${gran.name.lowercase()}")
                    ) {
                        Text(gran.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Period stepper (hidden only in Yearly overview where all years are shown together)
            if (state.granularity != ChartGranularity.YEARLY) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onStepPrevious, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Previous period")
                        }
                        Text(
                            text = state.periodLabel,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        IconButton(
                            onClick = onStepNext,
                            enabled = state.canStepNext,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Next period")
                        }
                    }
                }
            }

            NetAssetAreaLineChart(
                primary = state.netAssetSeries,
                comparison = state.netAssetComparisonSeries,
                comparisonLabel = state.comparisonOverlayLabel
            )
        }
    }
}

@Composable
private fun PeriodComparatorCard(
    state: ChartsUiState,
    onToggleYearlyMode: (Boolean) -> Unit,
    onStepPeriodA: (Int) -> Unit,
    onStepPeriodB: (Int) -> Unit,
    onSwap: () -> Unit
) {
    val snapA = state.snapshotA ?: return
    val snapB = state.snapshotB ?: return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Period Comparison",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Compare any two months or years side by side",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = !state.compareYearlyMode,
                        onClick = { onToggleYearlyMode(false) },
                        label = { Text("Month", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = state.compareYearlyMode,
                        onClick = { onToggleYearlyMode(true) },
                        label = { Text("Year", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            // Pickers for Period A vs Period B
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PeriodMiniSelector(
                    title = "Period A",
                    value = snapA.label,
                    onPrev = { onStepPeriodA(-1) },
                    onNext = { onStepPeriodA(1) },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onSwap, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = "Swap periods")
                }
                PeriodMiniSelector(
                    title = "Period B",
                    value = snapB.label,
                    onPrev = { onStepPeriodB(-1) },
                    onNext = { onStepPeriodB(1) },
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            ComparisonMetricRow(
                metric = "Net Asset",
                valA = snapA.netAsset,
                valB = snapB.netAsset,
                higherIsBetter = true
            )
            ComparisonMetricRow(
                metric = "Income",
                valA = snapA.income,
                valB = snapB.income,
                higherIsBetter = true
            )
            ComparisonMetricRow(
                metric = "Expense",
                valA = snapA.expense,
                valB = snapB.expense,
                higherIsBetter = false
            )
            ComparisonMetricRow(
                metric = "Net Savings",
                valA = snapA.netCashflow,
                valB = snapB.netCashflow,
                higherIsBetter = true
            )

            // Top category comparison
            val allTopCats = (snapA.topCategories.map { it.first } + snapB.topCategories.map { it.first }).distinct().take(4)
            if (allTopCats.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(
                    text = "TOP EXPENSE CATEGORIES (${snapA.label} vs ${snapB.label})",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                val mapA = snapA.topCategories.toMap()
                val mapB = snapB.topCategories.toMap()
                allTopCats.forEach { cat ->
                    val aAmt = mapA[cat] ?: 0.0
                    val bAmt = mapB[cat] ?: 0.0
                    ComparisonMetricRow(
                        metric = categoryLabel(cat, state.categoryNicknames),
                        valA = aAmt,
                        valB = bAmt,
                        higherIsBetter = false
                    )
                }
            }
        }
    }
}

@Composable
private fun PeriodMiniSelector(
    title: String,
    value: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onPrev, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Previous $title", modifier = Modifier.size(18.dp))
                }
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onNext, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Next $title", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun ComparisonMetricRow(
    metric: String,
    valA: Double,
    valB: Double,
    higherIsBetter: Boolean
) {
    val diff = valA - valB
    val pct = if (abs(valB) >= 1.0) (diff / abs(valB)) * 100.0 else null
    val isGood = if (higherIsBetter) diff >= 0.0 else diff <= 0.0
    val deltaColor = when {
        abs(diff) < 0.01 -> MaterialTheme.colorScheme.onSurfaceVariant
        isGood -> StatusSafe
        else -> MaterialTheme.colorScheme.error
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = metric,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(86.dp)) {
            Text(
                text = formatCompactTk(valA),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "B: ${formatCompactTk(valB)}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Box(modifier = Modifier.width(82.dp), contentAlignment = Alignment.CenterEnd) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = deltaColor.copy(alpha = 0.13f)
            ) {
                val sign = if (diff > 0) "+" else ""
                val label = pct?.let {
                    String.format(Locale.US, "%s%.0f%%", if (it > 0) "+" else "", it)
                } ?: "$sign${formatCompactTk(diff)}"
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = deltaColor,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}
