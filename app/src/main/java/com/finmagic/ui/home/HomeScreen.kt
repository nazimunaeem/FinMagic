package com.finmagic.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    onAccountClick: (Long) -> Unit = {},
    onCardClick: (Long) -> Unit = {},
    onChartsClick: () -> Unit = {}
) {
    val accountsVm: AccountsViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val aState by accountsVm.uiState.collectAsStateWithLifecycle()
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
