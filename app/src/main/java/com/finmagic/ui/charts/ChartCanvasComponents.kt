package com.finmagic.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finmagic.ui.accounts.categoryColor
import com.finmagic.ui.accounts.categoryLabel
import com.finmagic.ui.theme.StatusSafe
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal fun formatCompactTk(amount: Double): String {
    val sign = if (amount < -0.005) "−" else ""
    val v = abs(amount)
    return when {
        v >= 10_000_000 -> "${sign}৳${String.format(Locale.US, "%.1fM", v / 1_000_000.0)}"
        v >= 1_000_000 -> "${sign}৳${String.format(Locale.US, "%.2fM", v / 1_000_000.0)}"
        v >= 10_000 -> "${sign}৳${String.format(Locale.US, "%.1fk", v / 1_000.0)}"
        v >= 1_000 -> "${sign}৳${String.format(Locale.US, "%.1fk", v / 1_000.0)}"
        else -> "${sign}৳${String.format(Locale.US, "%.0f", v)}"
    }
}

internal fun formatExactTk(amount: Double): String {
    val sign = if (amount < -0.005) "−" else ""
    val v = abs(amount)
    val hasCents = abs(v - kotlin.math.round(v)) >= 0.005
    return if (hasCents) {
        "${sign}৳${String.format(Locale.US, "%,.2f", v)}"
    } else {
        "${sign}৳${String.format(Locale.US, "%,.0f", v)}"
    }
}

@Composable
fun NetAssetAreaLineChart(
    primary: List<NetAssetPoint>,
    comparison: List<NetAssetPoint>,
    comparisonLabel: String,
    modifier: Modifier = Modifier
) {
    var selectedIdx by remember(primary) { mutableIntStateOf((primary.size - 1).coerceAtLeast(0)) }

    if (primary.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(170.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No Net Asset history for this period yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val safeIdx = selectedIdx.coerceIn(0, primary.lastIndex)
    val activePoint = primary[safeIdx]
    val compPoint = comparison.getOrNull(safeIdx)

    val allValues = buildList {
        primary.forEach { add(it.netAsset) }
        comparison.forEach { add(it.netAsset) }
    }
    val rawMin = allValues.minOrNull() ?: 0.0
    val rawMax = allValues.maxOrNull() ?: 0.0
    val span = (rawMax - rawMin).let { if (it < 1.0) max(100.0, abs(rawMax) * 0.2 + 100.0) else it }
    val minVal = rawMin - span * 0.12
    val maxVal = rawMax + span * 0.12
    val valueRange = (maxVal - minVal).coerceAtLeast(1.0)

    val primaryColor = MaterialTheme.colorScheme.primary
    val compColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val surfaceColor = MaterialTheme.colorScheme.surface

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Selected point inspector pill
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activePoint.fullLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor
                    )
                    Text(
                        text = "Assets ${formatCompactTk(activePoint.assets)} • Liab ${formatCompactTk(activePoint.liabilities)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatExactTk(activePoint.netAsset),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (activePoint.netAsset < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    if (compPoint != null && comparisonLabel.isNotBlank()) {
                        val diff = activePoint.netAsset - compPoint.netAsset
                        val diffColor = if (diff >= 0) StatusSafe else MaterialTheme.colorScheme.error
                        val prefix = if (diff >= 0) "+" else ""
                        Text(
                            text = "vs ${compPoint.fullLabel}: $prefix${formatCompactTk(diff)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                            color = diffColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Chart canvas + Y labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .width(58.dp)
                    .height(172.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatCompactTk(maxVal),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    text = formatCompactTk((maxVal + minVal) / 2.0),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    text = formatCompactTk(minVal),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            val maxPointsCount = max(primary.size, comparison.size).coerceAtLeast(1)
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(172.dp)
                    .pointerInput(primary) {
                        detectTapGestures { offset ->
                            if (primary.size == 1) {
                                selectedIdx = 0
                            } else {
                                val step = size.width.toFloat() / (maxPointsCount - 1).coerceAtLeast(1)
                                val tapped = (offset.x / step).roundToInt().coerceIn(0, primary.lastIndex)
                                selectedIdx = tapped
                            }
                        }
                    }
            ) {
                val w = size.width
                val h = size.height
                val topPad = 10.dp.toPx()
                val bottomPad = 10.dp.toPx()
                val plotH = (h - topPad - bottomPad).coerceAtLeast(1f)

                fun xFor(index: Int): Float =
                    if (maxPointsCount <= 1) w / 2f else (index.toFloat() / (maxPointsCount - 1)) * w

                fun yFor(value: Double): Float {
                    val ratio = ((value - minVal) / valueRange).toFloat().coerceIn(0f, 1f)
                    return topPad + plotH * (1f - ratio)
                }

                // Grid lines
                for (g in 0..2) {
                    val gy = topPad + (plotH * g / 2f)
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, gy),
                        end = Offset(w, gy),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // Zero reference line if crossing 0
                if (minVal < 0.0 && maxVal > 0.0) {
                    val zy = yFor(0.0)
                    drawLine(
                        color = gridColor.copy(alpha = 0.9f),
                        start = Offset(0f, zy),
                        end = Offset(w, zy),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                    )
                }

                // Comparison overlay line (dashed)
                if (comparison.size >= 2) {
                    val compPath = Path()
                    comparison.forEachIndexed { idx, pt ->
                        val px = xFor(idx)
                        val py = yFor(pt.netAsset)
                        if (idx == 0) compPath.moveTo(px, py) else compPath.lineTo(px, py)
                    }
                    drawPath(
                        path = compPath,
                        color = compColor.copy(alpha = 0.85f),
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                        )
                    )
                }

                // Primary area & line
                if (primary.size >= 2) {
                    val linePath = Path()
                    val areaPath = Path()
                    primary.forEachIndexed { idx, pt ->
                        val px = xFor(idx)
                        val py = yFor(pt.netAsset)
                        if (idx == 0) {
                            linePath.moveTo(px, py)
                            areaPath.moveTo(px, topPad + plotH)
                            areaPath.lineTo(px, py)
                        } else {
                            linePath.lineTo(px, py)
                            areaPath.lineTo(px, py)
                        }
                    }
                    areaPath.lineTo(xFor(primary.lastIndex), topPad + plotH)
                    areaPath.close()

                    drawPath(
                        path = areaPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                primaryColor.copy(alpha = 0.28f),
                                primaryColor.copy(alpha = 0.02f)
                            ),
                            startY = topPad,
                            endY = topPad + plotH
                        )
                    )
                    drawPath(
                        path = linePath,
                        color = primaryColor,
                        style = Stroke(
                            width = 2.6.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // Vertical cursor + active dot
                val selX = xFor(safeIdx)
                val selY = yFor(activePoint.netAsset)
                drawLine(
                    color = primaryColor.copy(alpha = 0.4f),
                    start = Offset(selX, topPad),
                    end = Offset(selX, topPad + plotH),
                    strokeWidth = 1.2.dp.toPx()
                )
                if (compPoint != null) {
                    val cy = yFor(compPoint.netAsset)
                    drawCircle(color = compColor, radius = 4.dp.toPx(), center = Offset(selX, cy))
                }
                drawCircle(color = primaryColor, radius = 6.dp.toPx(), center = Offset(selX, selY))
                drawCircle(color = surfaceColor, radius = 2.5.dp.toPx(), center = Offset(selX, selY))
            }
        }

        // X-axis tick labels
        val tickIndices = remember(primary.size) {
            when {
                primary.size <= 7 -> primary.indices.toList()
                else -> {
                    val step = max(1, (primary.size - 1) / 5)
                    buildList {
                        var i = 0
                        while (i < primary.size) {
                            add(i)
                            i += step
                        }
                        if (lastOrNull() != primary.lastIndex) add(primary.lastIndex)
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 58.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            tickIndices.forEach { idx ->
                Text(
                    text = primary[idx].label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (idx == safeIdx) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (idx == safeIdx) FontWeight.Bold else FontWeight.Normal
                )
            }
        }

        // Legend if comparison active
        if (comparison.isNotEmpty() && comparisonLabel.isNotBlank()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendDot(color = primaryColor, label = "Selected")
                LegendDot(color = compColor, label = "Prev ($comparisonLabel, dashed)")
            }
        }
    }
}

@Composable
fun IncomeExpenseGroupedBarChart(
    points: List<CashflowPoint>,
    modifier: Modifier = Modifier
) {
    var selectedIdx by remember(points) { mutableIntStateOf((points.size - 1).coerceAtLeast(0)) }

    if (points.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(150.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No cashflow activity in this period.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val safeIdx = selectedIdx.coerceIn(0, points.lastIndex)
    val active = points[safeIdx]
    val maxBar = points.maxOfOrNull { max(it.income, it.expense) }?.coerceAtLeast(1.0) ?: 1.0
    val incomeColor = StatusSafe
    val expenseColor = MaterialTheme.colorScheme.error
    val highlightBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Tooltip bar
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = active.fullLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "+${formatCompactTk(active.income)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = incomeColor
                    )
                    Text(
                        text = "−${formatCompactTk(active.expense)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = expenseColor
                    )
                }
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                .pointerInput(points) {
                    detectTapGestures { offset ->
                        val groupW = size.width.toFloat() / points.size.coerceAtLeast(1)
                        selectedIdx = (offset.x / groupW).toInt().coerceIn(0, points.lastIndex)
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val groupW = w / points.size
            val barPairW = min(groupW * 0.72f, 28.dp.toPx())
            val singleBarW = (barPairW - 2.dp.toPx()).coerceAtLeast(2.dp.toPx()) / 2f

            // Baseline and mid line
            drawLine(gridColor, Offset(0f, h), Offset(w, h), strokeWidth = 1.dp.toPx())
            drawLine(gridColor, Offset(0f, h * 0.5f), Offset(w, h * 0.5f), strokeWidth = 0.8.dp.toPx())

            points.forEachIndexed { idx, pt ->
                val gx = idx * groupW
                if (idx == safeIdx) {
                    drawRect(
                        color = highlightBg,
                        topLeft = Offset(gx, 0f),
                        size = Size(groupW, h)
                    )
                }
                val startX = gx + (groupW - barPairW) / 2f
                val incH = ((pt.income / maxBar).toFloat() * (h - 8.dp.toPx())).coerceAtLeast(if (pt.income > 0) 3.dp.toPx() else 0f)
                val expH = ((pt.expense / maxBar).toFloat() * (h - 8.dp.toPx())).coerceAtLeast(if (pt.expense > 0) 3.dp.toPx() else 0f)

                if (incH > 0f) {
                    drawRoundRect(
                        color = incomeColor,
                        topLeft = Offset(startX, h - incH),
                        size = Size(singleBarW, incH),
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                    )
                }
                if (expH > 0f) {
                    drawRoundRect(
                        color = expenseColor,
                        topLeft = Offset(startX + singleBarW + 2.dp.toPx(), h - expH),
                        size = Size(singleBarW, expH),
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                    )
                }
            }
        }

        // X labels
        val step = max(1, points.size / 6)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            points.forEachIndexed { idx, pt ->
                if (points.size <= 12 || idx % step == 0 || idx == points.lastIndex) {
                    Text(
                        text = pt.label,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = if (idx == safeIdx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (idx == safeIdx) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot(color = incomeColor, label = "Income")
            LegendDot(color = expenseColor, label = "Expense")
        }
    }
}

@Composable
fun CategoryDonutChart(
    slices: List<CategoryChartSlice>,
    nicknames: Map<String, String>,
    totalLabel: String,
    modifier: Modifier = Modifier
) {
    val topSlices = remember(slices) { slices.take(7) }
    var selectedIdx by remember(topSlices) { mutableIntStateOf(0) }

    if (topSlices.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(140.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No categorized transactions for this period.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val safeIdx = selectedIdx.coerceIn(0, topSlices.lastIndex)
    val totalAmount = slices.sumOf { it.amount }
    val selectedSlice = topSlices[safeIdx]

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier.size(132.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier
                        .size(132.dp)
                        .pointerInput(topSlices) {
                            detectTapGestures { offset ->
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val dx = offset.x - cx
                                val dy = offset.y - cy
                                val deg = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90.0 + 360.0) % 360.0).toFloat()
                                var acc = 0f
                                val sumTop = topSlices.sumOf { it.amount }.coerceAtLeast(1.0)
                                topSlices.forEachIndexed { idx, s ->
                                    val sweep = ((s.amount / sumTop) * 360.0).toFloat()
                                    if (deg in acc..(acc + sweep)) {
                                        selectedIdx = idx
                                        return@detectTapGestures
                                    }
                                    acc += sweep
                                }
                            }
                        }
                ) {
                    val sumTop = topSlices.sumOf { it.amount }.coerceAtLeast(1.0)
                    var startAngle = -90f
                    topSlices.forEachIndexed { idx, slice ->
                        val sweep = ((slice.amount / sumTop) * 360.0).toFloat()
                        val strokeW = if (idx == safeIdx) 24.dp.toPx() else 18.dp.toPx()
                        val inset = 14.dp.toPx()
                        drawArc(
                            color = categoryColor(slice.category),
                            startAngle = startAngle,
                            sweepAngle = (sweep - 2f).coerceAtLeast(1f),
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - inset * 2, size.height - inset * 2),
                            style = Stroke(width = strokeW, cap = StrokeCap.Butt)
                        )
                        startAngle += sweep
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = totalLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatCompactTk(totalAmount),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${(selectedSlice.percent * 100).roundToInt()}% ${categoryLabel(selectedSlice.category, nicknames)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                        color = categoryColor(selectedSlice.category),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                topSlices.forEachIndexed { idx, slice ->
                    val color = categoryColor(slice.category)
                    val delta = slice.amount - slice.prevAmount
                    val deltaPct = if (slice.prevAmount >= 1.0) ((delta / slice.prevAmount) * 100.0).roundToInt() else null
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (idx == safeIdx) color.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { selectedIdx = idx }
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .background(color, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = categoryLabel(slice.category, nicknames),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (idx == safeIdx) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = formatCompactTk(slice.amount),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (deltaPct != null) {
                                val prefix = if (deltaPct > 0) "+" else ""
                                Text(
                                    text = "$prefix$deltaPct% vs prev",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AssetLiabilityMixBars(
    slices: List<AllocationSlice>,
    modifier: Modifier = Modifier
) {
    if (slices.isEmpty()) {
        Text(
            text = "No account balances yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val totalAbs = slices.sumOf { it.amount }.coerceAtLeast(1.0)
    val palette = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        Color(0xFF26A69A),
        Color(0xFF42A5F5)
    )
    val liabilityColor = MaterialTheme.colorScheme.error

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Proportional stacked horizontal bar
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
        ) {
            var x = 0f
            var assetIdx = 0
            slices.forEach { slice ->
                val sliceW = ((slice.amount / totalAbs) * size.width).toFloat()
                val c = if (slice.isLiability) liabilityColor else palette[assetIdx++ % palette.size]
                drawRect(
                    color = c,
                    topLeft = Offset(x, 0f),
                    size = Size(sliceW, size.height)
                )
                x += sliceW
            }
        }

        var assetColorIdx = 0
        slices.forEach { slice ->
            val share = (slice.amount / totalAbs).toFloat().coerceIn(0f, 1f)
            val color = if (slice.isLiability) liabilityColor else palette[assetColorIdx++ % palette.size]
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
                        Text(
                            text = if (slice.isLiability) "${slice.label} (Liability)" else slice.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        text = "${if (slice.isLiability) "−" else ""}${formatExactTk(slice.amount)} (${(share * 100).roundToInt()}%)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (slice.isLiability) liabilityColor else MaterialTheme.colorScheme.onSurface
                    )
                }
                LinearProgressIndicator(
                    progress = { share },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape),
                    color = color,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
