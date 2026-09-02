package com.finmagic.ui.creditcards

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finmagic.data.local.entity.BillWithCard
import com.finmagic.data.local.entity.CardPayment
import com.finmagic.data.local.entity.CreditCard
import com.finmagic.ui.components.BillCard
import com.finmagic.ui.components.BillPaySheet
import com.finmagic.ui.components.SectionHeader
import com.finmagic.ui.components.SmsPermissionCard
import com.finmagic.ui.components.SummaryCard
import com.finmagic.ui.theme.StatusDueSoon
import com.finmagic.ui.theme.StatusOverdue
import com.finmagic.ui.theme.StatusSafe
import com.finmagic.ui.theme.StatusUpcoming
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditCardsScreen(
    initialTab: Int = 0,
    onTabSelected: (Int) -> Unit = {},
    onCardClick: (Long) -> Unit = {},
    showTopBar: Boolean = true,
    showPill: Boolean = true,
    viewModel: CreditCardsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }
    var showAddCardDialog by remember { mutableStateOf(false) }
    var heroPayBill by remember { mutableStateOf<BillWithCard?>(null) }

    var hasSmsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_SMS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasSmsPermission = isGranted
        if (isGranted) {
            viewModel.refresh()
        }
    }

    val showLoading = state.isLoading && state.unpaidCount == 0

    val timeline = remember(state.paidHistory, state.recentPayments) {
        buildList {
            state.paidHistory.forEach { add(TimelineEntry.BillEntry(it)) }
            state.recentPayments.forEach { add(TimelineEntry.PaymentEntry(it)) }
        }.sortedByDescending { it.ts }
    }

    if (showLoading) {
        FirstTimeLoadingScreen()
        return
    }

    Scaffold(
        topBar = if (showTopBar) {
            {
                TopAppBar(
                    title = {
                        Text(
                            text = "Credit Cards",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0)
                )
            }
        } else {
            {}
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = {
                    if (hasSmsPermission) {
                        viewModel.refresh()
                    } else {
                        permissionLauncher.launch(Manifest.permission.READ_SMS)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (selectedTab == 0) {
                        // DASHBOARD TAB
                        if (!hasSmsPermission) {
                            item {
                                SmsPermissionCard(
                                    onRequestPermission = {
                                        permissionLauncher.launch(Manifest.permission.READ_SMS)
                                    },
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        }

                        if (state.nextDue != null) {
                            item {
                                NextDueHero(
                                    bill = state.nextDue!!,
                                    onPay = { heroPayBill = state.nextDue!! },
                                    onClick = { onCardClick(state.nextDue!!.cardId) },
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        }

                        if (state.unpaidCount == 0 && state.paidHistory.isEmpty() && !state.isLoading) {
                            item {
                                CreditCardTeachingCard(
                                    onAddManual = { showAddCardDialog = true },
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        }

                        if (state.perCardSummary.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    title = "BY CARD",
                                    count = state.perCardSummary.size,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            items(state.perCardSummary, key = { "bycard_${it.cardId}" }) { row ->
                                CardSummaryRowCard(
                                    row = row,
                                    onClick = { onCardClick(row.cardId) },
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }

                        if (state.trend.size >= 2) {
                            item {
                                SectionHeader(
                                    title = "STATEMENT TREND",
                                    count = state.trend.size,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            item {
                                TrendChart(
                                    points = state.trend,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }

                        if (state.overdueBills.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    title = "OVERDUE",
                                    count = state.overdueBills.size,
                                    color = StatusOverdue
                                )
                            }
                            items(state.overdueBills, key = { "overdue_${it.billId}" }) { bill ->
                                BillCard(
                                    bill = bill,
                                    onMarkPaid = { viewModel.markBillAsPaid(bill.billId) },
                                    onUpdatePayment = { viewModel.updatePartialPayment(bill.billId, it) },
                                    onCardClick = { onCardClick(bill.cardId) },
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                )
                            }
                        }

                        if (state.dueSoonBills.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    title = "DUE SOON",
                                    count = state.dueSoonBills.size,
                                    color = StatusDueSoon
                                )
                            }
                            items(state.dueSoonBills, key = { "soon_${it.billId}" }) { bill ->
                                BillCard(
                                    bill = bill,
                                    onMarkPaid = { viewModel.markBillAsPaid(bill.billId) },
                                    onUpdatePayment = { viewModel.updatePartialPayment(bill.billId, it) },
                                    onCardClick = { onCardClick(bill.cardId) },
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                )
                            }
                        }

                        if (state.upcomingBills.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    title = "UPCOMING",
                                    count = state.upcomingBills.size,
                                    color = StatusUpcoming
                                )
                            }
                            items(state.upcomingBills, key = { "upcoming_${it.billId}" }) { bill ->
                                BillCard(
                                    bill = bill,
                                    onMarkPaid = { viewModel.markBillAsPaid(bill.billId) },
                                    onUpdatePayment = { viewModel.updatePartialPayment(bill.billId, it) },
                                    onCardClick = { onCardClick(bill.cardId) },
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                )
                            }
                        }

                        if (state.safeBills.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    title = "SAFE",
                                    count = state.safeBills.size,
                                    color = StatusSafe
                                )
                            }
                            items(state.safeBills, key = { "safe_${it.billId}" }) { bill ->
                                BillCard(
                                    bill = bill,
                                    onMarkPaid = { viewModel.markBillAsPaid(bill.billId) },
                                    onUpdatePayment = { viewModel.updatePartialPayment(bill.billId, it) },
                                    onCardClick = { onCardClick(bill.cardId) },
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                )
                            }
                        }

                        if (state.previousMonthsBills.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    title = "PREVIOUS MONTHS",
                                    count = state.previousMonthsBills.size,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            items(state.previousMonthsBills, key = { "prev_${it.billId}" }) { bill ->
                                BillCard(
                                    bill = bill,
                                    onMarkPaid = { viewModel.markBillAsPaid(bill.billId) },
                                    onUpdatePayment = { viewModel.updatePartialPayment(bill.billId, it) },
                                    onCardClick = { onCardClick(bill.cardId) },
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                )
                            }
                        }

                        if (state.paidHistory.isNotEmpty() || state.recentPayments.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    title = "HISTORY & PAYMENTS",
                                    count = state.paidHistory.size + state.recentPayments.size,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            val timelineItems = timeline

                            items(timelineItems, key = { it.key }) { entry ->
                                when (entry) {
                                    is TimelineEntry.BillEntry -> {
                                        val bill = entry.bill
                                        BillCard(
                                            bill = bill,
                                            onMarkPaid = { viewModel.markBillAsPaid(bill.billId) },
                                            onUpdatePayment = { viewModel.updatePartialPayment(bill.billId, it) },
                                            onMarkUnpaid = { viewModel.markBillAsUnpaid(bill.billId) },
                                            onEditBill = { total, min, due ->
                                                viewModel.editBill(bill.billId, total, min, due)
                                            },
                                            onCardClick = { onCardClick(bill.cardId) },
                                            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)
                                        )
                                    }
                                    is TimelineEntry.PaymentEntry -> {
                                        PaymentReceivedRow(
                                            payment = entry.payment,
                                            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)
                                        )
                                    }
                                }
                            }
                        }

                    } else {
                        // MY CARDS TAB
                        if (state.cards.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            Icons.Default.CreditCard,
                                            contentDescription = null,
                                            modifier = Modifier.padding(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "No cards yet",
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Cards are added automatically when SMS bills are detected.\nYou can also add one manually.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Button(
                                            onClick = { showAddCardDialog = true },
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Add Card Manually")
                                        }
                                    }
                                }
                            }
                        } else {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Your Cards",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { showAddCardDialog = true }) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Add")
                                    }
                                }
                            }

                            items(state.cards, key = { it.card.id }) { cardWithStats ->
                                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                                    CardItem(
                                        stats = cardWithStats,
                                        onClick = { onCardClick(cardWithStats.card.id) }
                                    )
                                }
                            }

                            item {
                                Spacer(modifier = Modifier.height(24.dp))
                                SupportedBanksInfo()
                            }
                        }
                    }
                }
            }

            // Floating Pill Tab
            if (showPill) Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .zIndex(1f),
                shape = RoundedCornerShape(50.dp),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PillTabItem(
                        selected = selectedTab == 0,
                        icon = if (selectedTab == 0) Icons.Filled.Dashboard else Icons.Outlined.Dashboard,
                        onClick = { selectedTab = 0; onTabSelected(0) }
                    )

                    PillTabItem(
                        selected = selectedTab == 1,
                        icon = if (selectedTab == 1) Icons.Filled.CreditCard else Icons.Outlined.CreditCard,
                        onClick = { selectedTab = 1; onTabSelected(1) }
                    )
                }
            }
        }
    }

    if (showAddCardDialog) {
        AddCardDialog(
            onDismiss = { showAddCardDialog = false },
            onConfirm = { bankName, last4, nickname, creditLimit, currency ->
                viewModel.addManualCard(bankName, last4, nickname, creditLimit, currency)
                showAddCardDialog = false
            }
        )
    }

    if (heroPayBill != null) {
        BillPaySheet(
            bill = heroPayBill!!,
            onDismiss = { heroPayBill = null }
        )
    }
}

private sealed class TimelineEntry {
    abstract val ts: Long
    abstract val key: String

    data class BillEntry(val bill: BillWithCard) : TimelineEntry() {
        override val ts: Long = bill.paidAt ?: bill.detectedAt
        override val key: String = "history_${bill.billId}"
    }

    data class PaymentEntry(val payment: CardPayment) : TimelineEntry() {
        override val ts: Long = payment.paidAt
        override val key: String = "pay_${payment.id}"
    }
}

@Composable
fun PaymentReceivedRow(
    payment: CardPayment,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = StatusSafe.copy(alpha = 0.08f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(StatusSafe.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = StatusSafe,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Payment Received •••${payment.cardTail}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = SimpleDateFormat("dd MMM, hh:mm a", Locale.US).format(payment.paidAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "Tk ${String.format(Locale.US, "%,.0f", payment.amountBdt)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = StatusSafe
            )
        }
    }
}

@Composable
private fun PillTabItem(
    selected: Boolean,
    icon: ImageVector,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                CircleShape
            )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatCurrencyMap(totals: Map<String, Double>): String {
    if (totals.isEmpty()) return "৳0.00"
    return totals.entries.joinToString(" + ") { (currency, amount) ->
        val symbol = when (currency.uppercase()) {
            "USD" -> "$"
            "BDT", "TK" -> "৳"
            else -> currency
        }
        "$symbol${String.format(Locale.US, "%,.2f", amount)}"
    }
}

private fun bankBrandGradient(bankName: String?): Brush {
    val name = bankName?.trim()?.lowercase() ?: ""
    return when {
        name.contains("prime") -> Brush.linearGradient(listOf(Color(0xFF1E88E5), Color(0xFF0D47A1)))
        name.contains("pubali") -> Brush.linearGradient(listOf(Color(0xFFE53935), Color(0xFFB71C1C)))
        name.contains("city") -> Brush.linearGradient(listOf(Color(0xFFFF8F00), Color(0xFFE65100)))
        name.contains("brac") -> Brush.linearGradient(listOf(Color(0xFF43A047), Color(0xFF1B5E20)))
        name.contains("ebl") -> Brush.linearGradient(listOf(Color(0xFF8E24AA), Color(0xFF4A148C)))
        name.contains("dbbl") -> Brush.linearGradient(listOf(Color(0xFF546E7A), Color(0xFF263238)))
        name.contains("aibl") || name.contains("aib") -> Brush.linearGradient(listOf(Color(0xFF00897B), Color(0xFF004D40)))
        else -> Brush.linearGradient(listOf(Color(0xFF7E57C2), Color(0xFF512DA8)))
    }
}

@Composable
private fun CardItem(
    stats: CardWithStats,
    onClick: () -> Unit
) {
    val card = stats.card
    val utilization = if (card.creditLimit > 0) {
        (stats.totalUnpaid / card.creditLimit).coerceIn(0.0, 1.0)
    } else null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bankBrandGradient(card.bankName)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CreditCard,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = card.maskedCard,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Total Paid",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        text = formatCurrencyMap(stats.paidByCurrency),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.End
                    )
                }
            }

            if (stats.totalUnpaid > 0 || utilization != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Unpaid",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Text(
                            text = formatCurrencyMap(stats.unpaidByCurrency),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (stats.overdueBillCount > 0) StatusOverdue
                            else if (stats.unpaidBillCount > 0) MaterialTheme.colorScheme.onSurface
                            else StatusSafe
                        )
                    }

                    Text(
                        text = if (stats.unpaidBillCount > 0) {
                            "${stats.unpaidBillCount} bill(s) • ${if (stats.overdueBillCount > 0) "${stats.overdueBillCount} overdue" else "unpaid"}"
                        } else "All paid",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (stats.overdueBillCount > 0) StatusOverdue else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (utilization != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        LinearProgressIndicator(
                            progress = { utilization.toFloat() },
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = if (utilization > 0.8) StatusOverdue else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "${card.limitSymbol()}${String.format(Locale.US, "%,.0f", card.creditLimit)} limit • ${(utilization * 100).toInt()}% used",
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
private fun CardSummaryRowCard(
    row: CardSummaryRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bankBrandGradient(row.displayName)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.CreditCard,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = row.maskedCard,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatCurrencyMap(row.unpaidByCurrency),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = if (row.overdueBillCount > 0) "${row.overdueBillCount} overdue" else "${row.unpaidBillCount} unpaid",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun NextDueHero(
    bill: BillWithCard,
    onPay: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val days = bill.daysUntilDue
    val isOverdue = days < 0
    val minSymbol = when (bill.currency.uppercase()) {
        "USD" -> "$"
        "BDT", "TK" -> "৳"
        else -> bill.currency
    }
    val formattedMin = "$minSymbol${String.format(Locale.US, "%,.2f", bill.minDue)}"
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                        )
                    )
                )
                .fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = if (isOverdue) "OVERDUE PAYMENT" else "NEXT PAYMENT DUE",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = bill.formattedRemaining,
                    style = if (bill.formattedRemaining.length > 14) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = bill.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isOverdue) "${-days}d overdue" else "${days}d left",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isOverdue) Color(0xFFFF8A80) else MaterialTheme.colorScheme.onPrimary
                    )
                }
                Text(
                    text = "Due ${bill.formattedDueDate} • Min $formattedMin",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(10.dp))
                TextButton(
                    onClick = onPay,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pay now", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun TrendChart(
    points: List<TrendPoint>,
    modifier: Modifier = Modifier
) {
    val max = points.maxOfOrNull { it.amount } ?: 0.0
    val barHeight = 96.dp

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                points.forEach { point ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        if (point.amount > 0) {
                            Text(
                                text = shortAmount(point.amount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        val scaledHeight = if (max > 0) (point.amount / max).toFloat() else 0f
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .height(if (point.amount > 0) (barHeight * 0.5f) * scaledHeight + 4.dp else 4.dp)
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                .background(
                                    if (points.lastOrNull() == point) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = point.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun shortAmount(amount: Double): String = when {
    amount >= 1000 -> "৳${String.format(Locale.US, "%.0fk", amount / 1000.0)}"
    else -> "৳${String.format(Locale.US, "%.0f", amount)}"
}

@Composable
private fun AddCardDialog(
    onDismiss: () -> Unit,
    onConfirm: (bankName: String, last4: String, nickname: String, creditLimit: Double, currency: String) -> Unit
) {
    var bankName by remember { mutableStateOf("") }
    var last4 by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("BDT") }

    val digitsOnly = last4.filter { it.isDigit() }.take(4)
    val isValid = bankName.isNotBlank() && digitsOnly.length == 4

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Credit Card") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = bankName,
                    onValueChange = { bankName = it },
                    label = { Text("Bank Name") },
                    placeholder = { Text("e.g. Prime Bank") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = digitsOnly,
                    onValueChange = { last4 = it },
                    label = { Text("Last 4 Digits") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("Nickname (optional)") },
                    placeholder = { Text("e.g. My Corporate Card") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = limit,
                    onValueChange = { limit = it },
                    label = { Text("Credit Limit (optional)") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Currency",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = currency == "BDT",
                        onClick = { currency = "BDT" },
                        label = { Text("৳ BDT") }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = currency == "USD",
                        onClick = { currency = "USD" },
                        label = { Text("$ USD") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedLimit = limit.replace(",", "").toDoubleOrNull() ?: 0.0
                    onConfirm(bankName.trim(), digitsOnly, nickname.trim(), parsedLimit, currency)
                },
                enabled = isValid
            ) {
                Text("Add Card")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CreditCardTeachingCard(
    onAddManual: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CreditCard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Credit Card Bills",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            TeachingStep(
                icon = Icons.Default.Sms,
                title = "Auto-detect from SMS",
                description = "FinMagic reads your bank SMS messages and automatically finds credit card bill statements."
            )

            Spacer(modifier = Modifier.height(12.dp))

            TeachingStep(
                icon = Icons.Default.CheckCircle,
                title = "Track payments",
                description = "Mark bills as paid or partial payments. FinMagic keeps a history of all your payments."
            )

            Spacer(modifier = Modifier.height(12.dp))

            TeachingStep(
                icon = Icons.Default.Notifications,
                title = "Never miss a due date",
                description = "Get reminded before bills are due. Configure alert timing in Settings."
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onAddManual,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Card Manually")
            }

            Spacer(modifier = Modifier.height(12.dp))

            HorizontalDivider()

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Supported Banks",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Prime Bank  •  Pubali Bank  •  City Bank (Amex)\nBRAC Bank  •  Eastern Bank",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Grant SMS permission and bills will appear automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontStyle = FontStyle.Italic
            )
        }
    }
}

@Composable
private fun TeachingStep(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun SupportedBanksInfo() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Supported Banks",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Prime Bank • Pubali Bank • City Bank (Amex)\nBRAC Bank • EBL • Eastern Bank",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun FirstTimeLoadingScreen() {
    val infiniteTransition = rememberInfiniteTransition(label = "loading")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val fadeAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fade"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                        MaterialTheme.colorScheme.surface
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(160.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                )

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                )

                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(40.dp)
                        .alpha(fadeAlpha)
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            Text(
                text = "Scanning Your SMS",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Reading bank messages to find your credit card bills...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                LoadingStepRow(Icons.Default.CreditCard, "Reading SMS messages", true, fadeAlpha)
                LoadingStepRow(Icons.Default.Search, "Identifying bank messages", true, fadeAlpha * 0.7f)
                LoadingStepRow(Icons.Default.CheckCircle, "Extracting bill information", false, 0.4f)
            }

            Spacer(modifier = Modifier.height(48.dp))

            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "This may take a moment on first launch",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun LoadingStepRow(
    icon: ImageVector,
    text: String,
    isActive: Boolean,
    alpha: Float
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (isActive) {
            Spacer(modifier = Modifier.weight(1f))
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}