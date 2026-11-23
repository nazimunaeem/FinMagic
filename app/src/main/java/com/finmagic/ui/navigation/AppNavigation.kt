package com.finmagic.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Home : Screen(
        route = "home",
        title = "Home",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home
    )

    data object CreditCards : Screen(
        route = "credit_cards",
        title = "Credit Cards",
        selectedIcon = Icons.Filled.CreditCard,
        unselectedIcon = Icons.Outlined.CreditCard
    )

    data object Expenses : Screen(
        route = "expenses",
        title = "Expenses",
        selectedIcon = Icons.Filled.ShoppingCart,
        unselectedIcon = Icons.Outlined.ShoppingCart
    )

    data object Accounts : Screen(
        route = "accounts",
        title = "Accounts",
        selectedIcon = Icons.Filled.AccountBalance,
        unselectedIcon = Icons.Outlined.AccountBalance
    )

    data object Settings : Screen(
        route = "settings",
        title = "Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings
    )

    data object Categories : Screen(
        route = "categories",
        title = "Categories",
        selectedIcon = Icons.Filled.Label,
        unselectedIcon = Icons.Outlined.Label
    )

    data object SmsReview : Screen(
        route = "sms_review",
        title = "SMS Review",
        selectedIcon = Icons.Filled.Sms,
        unselectedIcon = Icons.Outlined.Sms
    )

    data object Charts : Screen(
        route = "charts",
        title = "Charts",
        selectedIcon = Icons.Filled.BarChart,
        unselectedIcon = Icons.Outlined.BarChart
    )

}

val bottomNavScreens = listOf(
    Screen.Home,
    Screen.Expenses,
    Screen.Settings
)
