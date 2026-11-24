package com.finmagic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.finmagic.data.update.GitHubUpdateChecker
import com.finmagic.data.update.UpdateCheckState
import com.finmagic.data.update.UpdateInstallState
import com.finmagic.data.web.LocalWebServer
import com.finmagic.notification.DueDateReminderScheduler
import com.finmagic.notification.SmsScheduler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import com.finmagic.data.local.AppThemeMode
import com.finmagic.data.local.PreferenceManager
import com.finmagic.ui.accounts.AccountDetailScreen
import com.finmagic.ui.accounts.AccountsScreen
import com.finmagic.ui.accounts.ArchivedAccountsScreen
import com.finmagic.ui.accounts.MyAccountsScreen
import com.finmagic.ui.charts.ChartsScreen
import com.finmagic.ui.creditcards.CardDetailScreen
import com.finmagic.ui.creditcards.CreditCardsScreen
import com.finmagic.ui.expenses.ExpensesScreen
import com.finmagic.ui.home.HomeScreen
import com.finmagic.ui.navigation.Screen
import com.finmagic.ui.settings.CategoriesScreen
import com.finmagic.ui.navigation.bottomNavScreens
import com.finmagic.ui.settings.SettingsScreen
import com.finmagic.ui.smsreview.SmsReviewScreen
import com.finmagic.ui.theme.FinMagicTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @javax.inject.Inject
    lateinit var preferenceManager: PreferenceManager

    @javax.inject.Inject
    lateinit var localWebServer: LocalWebServer

    @javax.inject.Inject
    lateinit var gitHubUpdateChecker: GitHubUpdateChecker

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            SmsScheduler.schedule(this)
            SmsScheduler.scanStartup(this)
            lifecycleScope.launch {
                DueDateReminderScheduler.schedule(this@MainActivity, preferenceManager)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions()

        lifecycleScope.launch {
            if (preferenceManager.webPortalAutoStart.first()) {
                val port = preferenceManager.webPortalPort.first()
                localWebServer.start(port)
            }
        }
        lifecycleScope.launch {
            if (preferenceManager.autoUpdateCheckEnabled.first()) {
                gitHubUpdateChecker.checkForUpdates(manual = false)
            }
        }

        setContent {
            val themeMode by preferenceManager.themeMode.collectAsStateWithLifecycle(initialValue = AppThemeMode.LIGHT)
            val isSystemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
                AppThemeMode.SYSTEM -> isSystemDark
            }

            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    },
                    navigationBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    }
                )
                onDispose {}
            }

            FinMagicTheme(darkTheme = darkTheme) {
                MainApp(
                    preferenceManager = preferenceManager,
                    gitHubUpdateChecker = gitHubUpdateChecker
                )
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
            SmsScheduler.scanStartup(this)
            lifecycleScope.launch {
                DueDateReminderScheduler.schedule(this@MainActivity, preferenceManager)
            }
        }
    }
}

@Composable
fun MainApp(
    preferenceManager: PreferenceManager,
    gitHubUpdateChecker: GitHubUpdateChecker
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val updateState by gitHubUpdateChecker.state.collectAsStateWithLifecycle()
    val installState by gitHubUpdateChecker.installState.collectAsStateWithLifecycle()
    val skippedTag by preferenceManager.skippedUpdateTag.collectAsStateWithLifecycle(initialValue = null)

    // Sub-tab selection per section, hoisted here so it survives being covered by
    // detail screens (back restores it), while a bottom-bar tap force-resets it to
    // the default sub-tab (Accounts -> History, Cards -> Dashboard).
    var accountsInitialTab by rememberSaveable { mutableIntStateOf(0) }
    var cardsInitialTab by rememberSaveable { mutableIntStateOf(0) }
    var homeSelectedTab by rememberSaveable { mutableIntStateOf(0) }
    var homeDoubleTapTrigger by rememberSaveable { mutableIntStateOf(0) }
    var lastHomeTapMs by remember { mutableLongStateOf(0L) }

    androidx.compose.runtime.LaunchedEffect(installState) {
        if (installState is UpdateInstallState.ReadyToInstall) {
            val ready = installState as UpdateInstallState.ReadyToInstall
            runCatching { context.startActivity(ready.intent) }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
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
                            if (screen.route == Screen.Home.route) {
                                val now = System.currentTimeMillis()
                                val isDoubleTap = now - lastHomeTapMs <= 450L
                                lastHomeTapMs = if (isDoubleTap) 0L else now
                                if (isDoubleTap) {
                                    homeSelectedTab = 0
                                    homeDoubleTapTrigger++
                                    navController.navigate(Screen.Home.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            inclusive = false
                                        }
                                        launchSingleTop = true
                                    }
                                    return@NavigationBarItem
                                }
                            } else {
                                lastHomeTapMs = 0L
                            }

                            val inSection = currentDestination?.hierarchy
                                ?.any { it.route == screen.route } == true
                            if (!inSection) {
                                if (screen.route == Screen.Accounts.route) accountsInitialTab = 0
                                if (screen.route == Screen.CreditCards.route) cardsInitialTab = 0
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id)
                                    launchSingleTop = true
                                }
                            } else {
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
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    selectedTab = homeSelectedTab,
                    onTabSelected = { homeSelectedTab = it },
                    cardsSubTab = cardsInitialTab,
                    onCardsSubTabSelected = { cardsInitialTab = it },
                    scrollToTopTrigger = homeDoubleTapTrigger,
                    onAccountClick = { accountId ->
                        navController.navigate("accounts/$accountId")
                    },
                    onCardClick = { cardId ->
                        navController.navigate("cards/$cardId")
                    },
                    onChartsClick = {
                        navController.navigate(Screen.Charts.route) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToArchivedAccounts = {
                        navController.navigate(Screen.ArchivedAccounts.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable(Screen.Charts.route) {
                ChartsScreen(onBack = { navController.popBackStack() })
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
                ExpensesScreen(
                    onChartsClick = {
                        navController.navigate(Screen.Charts.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(
                route = "cards/{cardId}",
                arguments = listOf(navArgument("cardId") { type = NavType.LongType })
            ) {
                CardDetailScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Accounts.route) { backStackEntry ->
                val initialTab = backStackEntry.arguments?.getInt("initialTab") ?: accountsInitialTab
                AccountsScreen(
                    initialTab = initialTab,
                    onTabSelected = { accountsInitialTab = it },
                    onAccountClick = { accountId ->
                        navController.navigate("accounts/$accountId")
                    },
                    onNavigateToArchivedAccounts = {
                        navController.navigate(Screen.ArchivedAccounts.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable("my_accounts") {
                MyAccountsScreen(
                    onBack = { navController.popBackStack() },
                    onAccountClick = { accountId ->
                        navController.navigate("accounts/$accountId")
                    },
                    onNavigateToArchivedAccounts = {
                        navController.navigate(Screen.ArchivedAccounts.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Screen.ArchivedAccounts.route) {
                ArchivedAccountsScreen(onBack = { navController.popBackStack() })
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
                    onNavigateToSmsReview = { navController.navigate(Screen.SmsReview.route) },
                    onNavigateToArchivedAccounts = {
                        navController.navigate(Screen.ArchivedAccounts.route) {
                            launchSingleTop = true
                        }
                    }
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

    val availableUpdate = (updateState as? UpdateCheckState.UpdateAvailable)?.release
    if (availableUpdate != null && availableUpdate.tagName != skippedTag) {
        AlertDialog(
            onDismissRequest = {
                if (installState !is UpdateInstallState.Downloading) {
                    gitHubUpdateChecker.dismissUpdate()
                }
            },
            title = { Text("Update Available (${availableUpdate.tagName})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = buildString {
                            append(availableUpdate.title)
                            if (availableUpdate.releaseNotes.isNotBlank()) {
                                append("\n\n")
                                append(availableUpdate.releaseNotes.take(350))
                            }
                        }
                    )
                    when (val iState = installState) {
                        is UpdateInstallState.Downloading -> {
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { (iState.progress / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "Downloading APK (${iState.progress}%)...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        is UpdateInstallState.NeedsInstallPermission -> {
                            Text(
                                text = "Allow \"Install unknown apps\" for FinMagic, then tap Update Now again.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        is UpdateInstallState.SignatureConflict -> {
                            Text(
                                text = "Signing certificate differs from your installed app (${iState.installedSha256.take(11)} vs ${iState.downloadedSha256.take(11)}). Android may show a package conflict unless you back up data and reinstall cleanly.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        is UpdateInstallState.Error -> {
                            Text(
                                text = iState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        UpdateInstallState.Idle,
                        is UpdateInstallState.ReadyToInstall -> Unit
                    }
                }
            },
            confirmButton = {
                when (val iState = installState) {
                    is UpdateInstallState.Downloading -> Unit
                    is UpdateInstallState.NeedsInstallPermission -> {
                        Button(
                            onClick = {
                                runCatching { context.startActivity(iState.settingsIntent) }
                                gitHubUpdateChecker.resetInstallState()
                            }
                        ) { Text("Grant Install Permission") }
                    }
                    is UpdateInstallState.SignatureConflict -> {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(availableUpdate.htmlUrl.ifBlank { availableUpdate.apkDownloadUrl }))
                                    )
                                }
                                gitHubUpdateChecker.dismissUpdate()
                            }
                        ) { Text("Open Release Page") }
                    }
                    else -> {
                        Button(
                            onClick = {
                                if (!availableUpdate.apkDownloadUrl.isNullOrBlank()) {
                                    scope.launch {
                                        gitHubUpdateChecker.downloadAndPrepareInstall(availableUpdate)
                                    }
                                } else {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(availableUpdate.htmlUrl)))
                                    }
                                    gitHubUpdateChecker.dismissUpdate()
                                }
                            }
                        ) {
                            Text(if (!availableUpdate.apkDownloadUrl.isNullOrBlank()) "Update Now" else "Download")
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = installState !is UpdateInstallState.Downloading,
                    onClick = {
                        scope.launch {
                            preferenceManager.setSkippedUpdateTag(availableUpdate.tagName)
                            gitHubUpdateChecker.dismissUpdate()
                        }
                    }
                ) {
                    Text("Skip")
                }
            }
        )
    }
}
