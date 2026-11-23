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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finmagic.data.local.entity.BillWithCard
import com.finmagic.data.local.entity.CardPayment
import com.finmagic.ui.components.BillCard
import com.finmagic.ui.components.BillPaySheet
import com.finmagic.ui.components.SectionHeader
import com.finmagic.ui.components.SmsPermissionCard
import com.finmagic.ui.theme.StatusDueSoon
import com.finmagic.ui.theme.StatusOverdue
import com.finmagic.ui.theme.StatusSafe
import com.finmagic.ui.theme.StatusUpcoming
import java.text.SimpleDateFormat
import java.util.Calendar
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
    LaunchedEffect(initialTab) {
        selectedTab = initialTab
    }
    var showAddCardDialog by remember { mutableStateOf(false) }
    var editingCardStats by remember { mutableStateOf<CardWithStats?>(null) }
    var deletingCard by remember { mutableStateOf<com.finmagic.data.local.entity.CreditCard?>(null) }
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
        buildPaidTimeline(state.paidHistory, state.recentPayments)
    }

    if (showLoading) {
        FirstTimeLoadingScreen()
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            CardsTabBar(
                selectedTab = selectedTab,
                unpaidBillCount = state.unpaidCount,
                overdueCount = state.overdueCount,
                cardCount = state.cards.size,
                onSelect = { tab ->
                    selectedTab = tab
                    onTabSelected(tab)
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp)
            )

            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = {
                    if (hasSmsPermission) {
                        viewModel.refresh()
                    } else {
                        permissionLauncher.launch(Manifest.permission.READ_SMS)
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (selectedTab == 0) {
                        // BILLS TAB: Unpaid active bills at the top with full details
                        if (!hasSmsPermission) {
                            item {
                                SmsPermissionCard(
                                    onRequestPermission = {
                                        permissionLauncher.launch(Manifest.permission.READ_SMS)
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }
                        }

                        if (state.unpaidCount == 0 && state.paidHistory.isEmpty() && !state.isLoading) {
                            item {
                                CreditCardTeachingCard(
                                    onAddManual = { showAddCardDialog = true },
                                    modifier = Modifier.padding(horizontal = 12.dp)
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
                                    onMarkUnpaid = { viewModel.markBillAsUnpaid(bill.billId) },
                                    onEditBill = { total, min, due ->
                                        viewModel.editBill(bill.billId, total, min, due)
                                    },
                                    onCardClick = { onCardClick(bill.cardId) },
                                    modifier = Modifier.padding(horizontal = 12.dp)
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
                                    onMarkUnpaid = { viewModel.markBillAsUnpaid(bill.billId) },
                                    onEditBill = { total, min, due ->
                                        viewModel.editBill(bill.billId, total, min, due)
                                    },
                                    onCardClick = { onCardClick(bill.cardId) },
                                    modifier = Modifier.padding(horizontal = 12.dp)
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
                                    onMarkUnpaid = { viewModel.markBillAsUnpaid(bill.billId) },
                                    onEditBill = { total, min, due ->
                                        viewModel.editBill(bill.billId, total, min, due)
                                    },
                                    onCardClick = { onCardClick(bill.cardId) },
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }
                        }

                        if (state.safeBills.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    title = "ACTIVE UNPAID",
                                    count = state.safeBills.size,
                                    color = StatusSafe
                                )
                            }
                            items(state.safeBills, key = { "safe_${it.billId}" }) { bill ->
                                BillCard(
                                    bill = bill,
                                    onMarkPaid = { viewModel.markBillAsPaid(bill.billId) },
                                    onUpdatePayment = { viewModel.updatePartialPayment(bill.billId, it) },
                                    onMarkUnpaid = { viewModel.markBillAsUnpaid(bill.billId) },
                                    onEditBill = { total, min, due ->
                                        viewModel.editBill(bill.billId, total, min, due)
                                    },
                                    onCardClick = { onCardClick(bill.cardId) },
                                    modifier = Modifier.padding(horizontal = 12.dp)
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
                                    onMarkUnpaid = { viewModel.markBillAsUnpaid(bill.billId) },
                                    onEditBill = { total, min, due ->
                                        viewModel.editBill(bill.billId, total, min, due)
                                    },
                                    onCardClick = { onCardClick(bill.cardId) },
                                    modifier = Modifier.padding(horizontal = 12.dp)
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
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }
                        }

                        if (state.paidHistory.isNotEmpty() || state.recentPayments.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    title = "PAID BILLS & PAYMENTS",
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
                                            modifier = Modifier.padding(horizontal = 12.dp)
                                        )
                                    }
                                    is TimelineEntry.PaymentEntry -> {
                                        PaymentReceivedRow(
                                            payment = entry.payment,
                                            modifier = Modifier.padding(horizontal = 12.dp)
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
                                        .padding(horizontal = 12.dp, vertical = 0.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Your Cards",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = { showAddCardDialog = true },
                                        modifier = Modifier.testTag("add_card_button")
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Add", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }

                            item {
                                CardPeriodSelector(
                                    mode = state.periodMode,
                                    selectedYear = state.selectedYear,
                                    selectedMonth = state.selectedMonth,
                                    canStepNext = state.canStepNext,
                                    onModeSelected = { viewModel.setPeriodMode(it) },
                                    onPrevious = { viewModel.stepPreviousPeriod() },
                                    onNext = { viewModel.stepNextPeriod() },
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }

                            items(state.cards, key = { it.card.id }) { cardWithStats ->
                                Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                                    CardItem(
                                        stats = cardWithStats,
                                        periodLabel = formatCardPeriodLabel(
                                            state.periodMode,
                                            state.selectedYear,
                                            state.selectedMonth
                                        ),
                                        onClick = { onCardClick(cardWithStats.card.id) },
                                        onEditClick = { editingCardStats = cardWithStats },
                                        onPayBill = cardWithStats.nextUnpaidBill?.let { bill ->
                                            { heroPayBill = bill }
                                        }
                                    )
                                }
                            }

                        }
                    }
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

    if (editingCardStats != null) {
        val targetStats = editingCardStats!!
        EditCardDialog(
            card = targetStats.card,
            currentOutstanding = targetStats.outstandingAmount,
            onDismiss = { editingCardStats = null },
            onConfirm = { nickname, creditLimit, currency, newOutstanding, clearAnchor ->
                viewModel.updateCardDetails(
                    card = targetStats.card,
                    nickname = nickname,
                    creditLimit = creditLimit,
                    currency = currency,
                    newOutstanding = newOutstanding,
                    clearOutstandingAnchor = clearAnchor
                )
                editingCardStats = null
            },
            onDeleteRequest = {
                editingCardStats = null
                deletingCard = targetStats.card
            }
        )
    }

    if (deletingCard != null) {
        val targetCard = deletingCard!!
        AlertDialog(
            onDismissRequest = { deletingCard = null },
            title = { Text("Delete Card") },
            text = { Text("Are you sure you want to delete ${targetCard.displayName}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteCard(targetCard)
                        deletingCard = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingCard = null }) {
                    Text("Cancel")
                }
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

internal sealed class TimelineEntry {
    abstract val ts: Long
    abstract val sortId: Long
    abstract val key: String

    data class BillEntry(val bill: BillWithCard) : TimelineEntry() {
        override val ts: Long = billHistoryTimestamp(bill)
        override val sortId: Long = bill.billId
        override val key: String = "history_${bill.billId}"
    }

    data class PaymentEntry(val payment: CardPayment) : TimelineEntry() {
        override val ts: Long = payment.paidAt
        override val sortId: Long = payment.id
        override val key: String = "pay_${payment.id}"
    }
}

internal fun buildPaidTimeline(
    paidBills: List<BillWithCard>,
    payments: List<CardPayment>
): List<TimelineEntry> = buildList {
    paidBills.forEach { add(TimelineEntry.BillEntry(it)) }
    payments.forEach { add(TimelineEntry.PaymentEntry(it)) }
}.sortedWith(
    compareByDescending<TimelineEntry> { it.ts }
        .thenByDescending { it.sortId }
)

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
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(StatusSafe.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = StatusSafe,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Payment Received •••${payment.cardTail}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(payment.paidAt),
                    style = MaterialTheme.typography.bodySmall,
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

internal fun formatCardPeriodLabel(
    mode: CardPeriodMode,
    year: Int,
    month: Int
): String = when (mode) {
    CardPeriodMode.ALL -> "All time"
    CardPeriodMode.YEAR -> year.toString()
    CardPeriodMode.MONTH -> {
        val cal = Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
        }
        SimpleDateFormat("MMM yyyy", Locale.US).format(cal.time)
    }
}

@Composable
internal fun CardPeriodSelector(
    mode: CardPeriodMode,
    selectedYear: Int,
    selectedMonth: Int,
    canStepNext: Boolean,
    onModeSelected: (CardPeriodMode) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = mode == CardPeriodMode.ALL,
                onClick = { onModeSelected(CardPeriodMode.ALL) },
                label = { Text("All") },
                modifier = Modifier.testTag("cards_period_all")
            )
            FilterChip(
                selected = mode == CardPeriodMode.MONTH,
                onClick = { onModeSelected(CardPeriodMode.MONTH) },
                label = { Text("Month") },
                modifier = Modifier.testTag("cards_period_month")
            )
            FilterChip(
                selected = mode == CardPeriodMode.YEAR,
                onClick = { onModeSelected(CardPeriodMode.YEAR) },
                label = { Text("Year") },
                modifier = Modifier.testTag("cards_period_year")
            )
        }

        if (mode != CardPeriodMode.ALL) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onPrevious,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("cards_period_prev")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Previous period"
                        )
                    }
                    Text(
                        text = formatCardPeriodLabel(mode, selectedYear, selectedMonth),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(
                        onClick = onNext,
                        enabled = canStepNext,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("cards_period_next")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Next period",
                            tint = if (canStepNext) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            }
                        )
                    }
                }
            }
        }
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
    periodLabel: String,
    onClick: () -> Unit,
    onEditClick: () -> Unit,
    onPayBill: (() -> Unit)? = null
) {
    val card = stats.card
    val outstanding = stats.outstandingAmount
    val utilization = if (card.creditLimit > 0) {
        (outstanding / card.creditLimit).coerceIn(0.0, 1.0)
    } else null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bankBrandGradient(card.bankName)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CreditCard,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = card.maskedCard,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f)
                ) {
                    Text(
                        text = "${stats.transactionCount} txns",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
                IconButton(
                    onClick = onEditClick,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("card_edit_${card.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit card & outstanding",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (utilization != null) {
                Spacer(modifier = Modifier.height(5.dp))
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
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${card.limitSymbol()}${String.format(Locale.US, "%,.0f", card.creditLimit)} • ${(utilization * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(5.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "Outstanding ${card.limitSymbol()}${String.format(Locale.US, "%,.2f", outstanding)} • Spent ৳${String.format(Locale.US, "%,.2f", stats.spentAmount)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (outstanding > 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val billSummary = if (stats.unpaidByCurrency.isNotEmpty()) {
                        "Bill due ${formatCurrencyMap(stats.unpaidByCurrency)}"
                    } else {
                        "Billed ${formatCurrencyMap(stats.billedByCurrency)}"
                    }
                    Text(
                        text = "$billSummary • Paid ${formatCurrencyMap(stats.paidByCurrency)} ($periodLabel)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (stats.unpaidBillCount > 0) {
                            "${stats.unpaidBillCount} unpaid bill${if (stats.unpaidBillCount == 1) "" else "s"}"
                        } else {
                            "All bills paid"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (stats.unpaidBillCount > 0) MaterialTheme.colorScheme.error else StatusSafe
                    )
                    if (onPayBill != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = onPayBill,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 38.dp)
                                .testTag("card_pay_bill_${card.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Payments,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pay Bill", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardsTabBar(
    selectedTab: Int,
    unpaidBillCount: Int,
    overdueCount: Int,
    cardCount: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CardsTabPill(
                title = "Bills",
                icon = Icons.Default.ReceiptLong,
                badge = if (unpaidBillCount > 0) unpaidBillCount.toString() else null,
                badgeDanger = overdueCount > 0 || unpaidBillCount > 0,
                selected = selectedTab == 0,
                onClick = { onSelect(0) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("cards_tab_bills")
            )
            CardsTabPill(
                title = "My Cards",
                icon = Icons.Default.CreditCard,
                badge = if (cardCount > 0) cardCount.toString() else null,
                badgeDanger = false,
                selected = selectedTab == 1,
                onClick = { onSelect(1) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("cards_tab_my_cards")
            )
        }
    }
}

@Composable
private fun CardsTabPill(
    title: String,
    icon: ImageVector,
    badge: String?,
    badgeDanger: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1
            )
            if (badge != null) {
                Spacer(modifier = Modifier.width(6.dp))
                val badgeBg = when {
                    badgeDanger -> MaterialTheme.colorScheme.error
                    selected -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)
                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                }
                val badgeFg = when {
                    badgeDanger -> Color.White
                    selected -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.primary
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(badgeBg)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = badgeFg
                    )
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