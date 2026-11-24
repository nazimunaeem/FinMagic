package com.finmagic.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finmagic.data.sms.ScanPhase
import com.finmagic.data.sms.SmsScanProgress
import com.finmagic.ui.accounts.AccountsScreen
import com.finmagic.ui.accounts.AccountsViewModel
import com.finmagic.ui.accounts.AddEntryDialog
import com.finmagic.ui.creditcards.CreditCardsScreen
import com.finmagic.ui.settings.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    selectedTab: Int = 0,
    onTabSelected: (Int) -> Unit = {},
    cardsSubTab: Int = 0,
    onCardsSubTabSelected: (Int) -> Unit = {},
    scrollToTopTrigger: Int = 0,
    onAccountClick: (Long) -> Unit = {},
    onCardClick: (Long) -> Unit = {},
    onChartsClick: () -> Unit = {}
) {
    val accountsVm: AccountsViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val smsScanVm: SmsScanViewModel = hiltViewModel()
    val aState by accountsVm.uiState.collectAsStateWithLifecycle()
    val scanStatus by smsScanVm.scanStatus.collectAsStateWithLifecycle()
    val cashExpenseCategories by settingsVm.cashExpenseCategories.collectAsStateWithLifecycle()
    val cashIncomeCategories by settingsVm.cashIncomeCategories.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = selectedTab != 0) {
        onTabSelected(0)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (selectedTab == 0 || selectedTab == 1) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.testTag("add_entry_fab"),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Entry")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AnimatedVisibility(
                visible = scanStatus.isScanning,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                SmsScanAnimatedBanner(
                    status = scanStatus,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.weight(1f)
                ) {
                    SegmentedButton(
                        selected = selectedTab == 0,
                        onClick = { onTabSelected(0) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                        modifier = Modifier.testTag("home_tab_history")
                    ) { Text("History") }
                    SegmentedButton(
                        selected = selectedTab == 1,
                        onClick = { onTabSelected(1) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                        modifier = Modifier.testTag("home_tab_accounts")
                    ) { Text("Accounts") }
                    SegmentedButton(
                        selected = selectedTab == 2,
                        onClick = { onTabSelected(2) },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                        modifier = Modifier.testTag("home_tab_cards")
                    ) { Text("Cards") }
                }

                IconButton(
                    onClick = onChartsClick,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("open_charts_button")
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.BarChart,
                                contentDescription = "Open Charts",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }

            when (selectedTab) {
                0 -> AccountsScreen(
                    showTopBar = false,
                    showPill = false,
                    initialTab = 0,
                    scrollToTopTrigger = scrollToTopTrigger,
                    onAccountClick = onAccountClick,
                    onCardClick = onCardClick,
                    viewModel = accountsVm
                )
                1 -> AccountsScreen(
                    showTopBar = false,
                    showPill = false,
                    initialTab = 1,
                    onAccountClick = onAccountClick,
                    onCardClick = onCardClick,
                    viewModel = accountsVm
                )
                2 -> CreditCardsScreen(
                    showTopBar = false,
                    showPill = false,
                    initialTab = cardsSubTab,
                    onTabSelected = onCardsSubTabSelected,
                    onCardClick = onCardClick
                )
            }
        }

        if (showAddDialog) {
            AddEntryDialog(
                viewModel = accountsVm,
                summaries = aState.summaries,
                expenseCategories = cashExpenseCategories,
                incomeCategories = cashIncomeCategories,
                onDismiss = { showAddDialog = false }
            )
        }
    }
}

@Composable
private fun SmsScanAnimatedBanner(
    status: SmsScanProgress,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sms_scan_spin")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sms_scan_rotation"
    )
    val phaseLabel = when (status.phase) {
        ScanPhase.RECENT_50 -> "LAST 50 SMS"
        ScanPhase.FULL_AUTO -> "AUTO FULL SCAN"
        ScanPhase.MANUAL_OR_PERIODIC -> "SYNCING"
        ScanPhase.IDLE -> ""
    }

    Surface(
        modifier = modifier.testTag("sms_scan_banner"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Sync,
                    contentDescription = "Scanning SMS",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(angle)
                )
                Text(
                    text = status.message.ifBlank { "Scanning SMS..." },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (phaseLabel.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = phaseLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            if (status.total > 0) {
                LinearProgressIndicator(
                    progress = { (status.processed.toFloat() / status.total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
