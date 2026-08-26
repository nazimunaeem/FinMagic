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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finmagic.data.local.entity.CreditCard
import com.finmagic.ui.components.BillCard
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
    onCardClick: (Long) -> Unit = {},
    viewModel: CreditCardsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Dashboard, 1: My Cards

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

    if (showLoading) {
        FirstTimeLoadingScreen()
        return
    }

    Scaffold(
        topBar = {
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

                        item {
                            SummaryCard(
                                thisMonthTotal = state.formatTotals(state.currentMonthTotals),
                                thisMonthMinTotal = state.formatTotals(state.currentMonthMinTotals),
                                prevMonthTotal = state.formatTotals(state.previousMonthsTotals),
                                unpaidCount = state.unpaidCount,
                                overdueCount = state.overdueCount,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }

                        if (state.unpaidCount == 0 && state.paidHistory.isEmpty() && !state.isLoading) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "No bills found",
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Credit card bills will appear here\nafter SMS is detected",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
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
                            
                            val maxLen = maxOf(state.recentPayments.size, state.paidHistory.size)
                            for (i in 0 until maxLen) {
                                if (i < state.recentPayments.size) {
                                    item(key = "pay_${state.recentPayments[i].id}") {
                                        PaymentReceivedRow(
                                            payment = state.recentPayments[i],
                                            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)
                                        )
                                    }
                                }
                                if (i < state.paidHistory.size) {
                                    item(key = "history_${state.paidHistory[i].billId}") {
                                        val bill = state.paidHistory[i]
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
                                            text = "Cards will be added automatically\nwhen SMS bills are detected",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        } else {
                            items(state.cards, key = { it.card.id }) { cardWithStats ->
                                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                                    CardItem(
                                        card = cardWithStats.card,
                                        totalPaid = cardWithStats.totalPaid,
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
            Surface(
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
                    // Dash Icon
                    PillTabItem(
                        selected = selectedTab == 0,
                        icon = if (selectedTab == 0) Icons.Filled.Dashboard else Icons.Outlined.Dashboard,
                        onClick = { selectedTab = 0 }
                    )

                    // Cards Icon
                    PillTabItem(
                        selected = selectedTab == 1,
                        icon = if (selectedTab == 1) Icons.Filled.CreditCard else Icons.Outlined.CreditCard,
                        onClick = { selectedTab = 1 }
                    )
                }
            }
        }
    }
}

@Composable
fun PaymentReceivedRow(
    payment: com.finmagic.data.local.entity.CardPayment,
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

@Composable
private fun CardItem(
    card: CreditCard,
    totalPaid: Double,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    )
                ) {
                    Icon(
                        Icons.Default.CreditCard,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp).size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = card.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = card.maskedCard,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Total Paid",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Text(
                    text = "Tk ${String.format(Locale.US, "%,.2f", totalPaid)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
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
            text = "Prime Bank • Pubali Bank • City Bank (Amex)\nBRAC Bank • EBL",
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
