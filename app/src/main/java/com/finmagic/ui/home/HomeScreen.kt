package com.finmagic.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finmagic.ui.accounts.AccountsScreen
import com.finmagic.ui.accounts.AddEntryDialog
import com.finmagic.ui.accounts.AccountsViewModel
import com.finmagic.ui.creditcards.CreditCardsScreen
import com.finmagic.ui.settings.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAccountClick: (Long) -> Unit = {},
    onCardClick: (Long) -> Unit = {}
) {
    val accountsVm: AccountsViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val aState by accountsVm.uiState.collectAsStateWithLifecycle()
    val cashExpenseCategories by settingsVm.cashExpenseCategories.collectAsStateWithLifecycle()
    val cashIncomeCategories by settingsVm.cashIncomeCategories.collectAsStateWithLifecycle()

    // 0=History, 1=Accounts, 2=Plus(add), 3=Card Dash, 4=Card List
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "FinMagic",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> key(0) {
                    AccountsScreen(
                        showTopBar = false,
                        showPill = false,
                        initialTab = 0,
                        onAccountClick = onAccountClick
                    )
                }
                1 -> key(1) {
                    AccountsScreen(
                        showTopBar = false,
                        showPill = false,
                        initialTab = 1,
                        onAccountClick = onAccountClick
                    )
                }
                3 -> key(3) {
                    CreditCardsScreen(
                        showTopBar = false,
                        showPill = false,
                        initialTab = 0,
                        onCardClick = onCardClick
                    )
                }
                4 -> key(4) {
                    CreditCardsScreen(
                        showTopBar = false,
                        showPill = false,
                        initialTab = 1,
                        onCardClick = onCardClick
                    )
                }
            }

            // Unified bottom pill: History, Accounts, Plus, Card Dash, Card List
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
                    HomePillItem(
                        selected = selectedTab == 0,
                        icon = if (selectedTab == 0) Icons.Filled.History else Icons.Outlined.History,
                        onClick = { selectedTab = 0 }
                    )
                    HomePillItem(
                        selected = selectedTab == 1,
                        icon = if (selectedTab == 1) Icons.Filled.AccountBalance else Icons.Outlined.AccountBalance,
                        onClick = { selectedTab = 1 }
                    )
                    FloatingActionButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Entry")
                    }
                    HomePillItem(
                        selected = selectedTab == 3,
                        icon = if (selectedTab == 3) Icons.Filled.Dashboard else Icons.Outlined.Dashboard,
                        onClick = { selectedTab = 3 }
                    )
                    HomePillItem(
                        selected = selectedTab == 4,
                        icon = if (selectedTab == 4) Icons.Filled.CreditCard else Icons.Outlined.CreditCard,
                        onClick = { selectedTab = 4 }
                    )
                }
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
private fun HomePillItem(
    selected: Boolean,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(40.dp),
        color = containerColor,
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(40.dp))
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
        }
    }
}
