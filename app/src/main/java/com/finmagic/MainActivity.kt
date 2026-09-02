package com.finmagic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.finmagic.notification.DueDateReminderScheduler
import com.finmagic.notification.SmsScheduler
import com.finmagic.data.local.PreferenceManager
import com.finmagic.ui.accounts.AccountDetailScreen
import com.finmagic.ui.accounts.AccountsScreen
import com.finmagic.ui.accounts.MyAccountsScreen
import com.finmagic.ui.creditcards.CardDetailScreen
import com.finmagic.ui.creditcards.CreditCardsScreen
import com.finmagic.ui.expenses.ExpensesScreen
import com.finmagic.ui.home.HomeScreen
import com.finmagic.ui.navigation.Screen
import com.finmagic.ui.settings.CategoriesScreen
import com.finmagic.ui.navigation.bottomNavScreens
import com.finmagic.ui.settings.SettingsScreen
import com.finmagic.ui.smsreview.SmsReviewScreen
import com.finmagic.ui.stats.StatsScreen
import com.finmagic.ui.theme.FinMagicTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @javax.inject.Inject
    lateinit var preferenceManager: PreferenceManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            SmsScheduler.schedule(this)
            SmsScheduler.scanNow(this, force = true)
            lifecycleScope.launch {
                DueDateReminderScheduler.schedule(this@MainActivity, preferenceManager)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions()

        setContent {
            FinMagicTheme {
                MainApp()
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_SMS)
        }

        // Needed to read the SIM's phone number so wallet accounts are keyed by
        // MSISDN (SIM1/SIM2 fallback label when unavailable).
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_PHONE_STATE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        } else {
            SmsScheduler.schedule(this)
            SmsScheduler.scanNow(this, force = true)
            lifecycleScope.launch {
                DueDateReminderScheduler.schedule(this@MainActivity, preferenceManager)
            }
        }
    }
}

@Composable
fun MainApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Sub-tab selection per section, hoisted here so it survives being covered by
    // detail screens (back restores it), while a bottom-bar tap force-resets it to
    // the default sub-tab (Accounts -> History, Cards -> Dashboard).
    var accountsInitialTab by remember { mutableIntStateOf(0) }
    var cardsInitialTab by remember { mutableIntStateOf(0) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                bottomNavScreens.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = screen.title
                            )
                        },
                        label = { Text(screen.title) },
                        selected = selected,
                        onClick = {
                            val inSection = currentDestination?.hierarchy
                                ?.any { it.route == screen.route } == true
                            if (!inSection) {
                                // Switching to a different section: reset to default sub-tab.
                                if (screen.route == Screen.Accounts.route) accountsInitialTab = 0
                                if (screen.route == Screen.CreditCards.route) cardsInitialTab = 0
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id)
                                    launchSingleTop = true
                                }
                            } else {
                                // Already inside this section: pop back to its root.
                                navController.popBackStack(screen.route, inclusive = false)
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onAccountClick = { accountId ->
                        navController.navigate("accounts/$accountId")
                    },
                    onCardClick = { cardId ->
                        navController.navigate("cards/$cardId")
                    }
                )
            }

            composable(Screen.CreditCards.route) {
                CreditCardsScreen(
                    initialTab = cardsInitialTab,
                    onTabSelected = { cardsInitialTab = it },
                    onCardClick = { cardId ->
                        navController.navigate("cards/$cardId")
                    }
                )
            }
            composable(Screen.Expenses.route) {
                ExpensesScreen()
            }
            composable(Screen.Stats.route) {
                StatsScreen()
            }
            composable(
                route = "cards/{cardId}",
                arguments = listOf(navArgument("cardId") { type = NavType.LongType })
            ) {
                CardDetailScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Accounts.route) {
                AccountsScreen(
                    initialTab = accountsInitialTab,
                    onTabSelected = { accountsInitialTab = it },
                    onAccountClick = { accountId ->
                        navController.navigate("accounts/$accountId")
                    }
                )
            }
            composable("my_accounts") {
                MyAccountsScreen(
                    onBack = { navController.popBackStack() },
                    onAccountClick = { accountId ->
                        navController.navigate("accounts/$accountId")
                    }
                )
            }
            composable(
                route = "accounts/{accountId}",
                arguments = listOf(navArgument("accountId") { type = NavType.LongType })
            ) {
                AccountDetailScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToCategories = { navController.navigate(Screen.Categories.route) },
                    onNavigateToSmsReview = { navController.navigate(Screen.SmsReview.route) }
                )
            }
            composable(Screen.Categories.route) {
                CategoriesScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.SmsReview.route) {
                SmsReviewScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
