package br.com.assistentefinanceiro.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.EventNote
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import br.com.assistentefinanceiro.data.FinancialRepository
import br.com.assistentefinanceiro.notifications.BankPackagePreferences
import br.com.assistentefinanceiro.ui.screens.AboutScreen
import br.com.assistentefinanceiro.ui.screens.AccountsScreen
import br.com.assistentefinanceiro.ui.screens.AnnualSummaryScreen
import br.com.assistentefinanceiro.ui.screens.DataManagementScreen
import br.com.assistentefinanceiro.ui.screens.DiagnosticScreen
import br.com.assistentefinanceiro.ui.screens.MonthlyBudgetScreen
import br.com.assistentefinanceiro.ui.screens.MonthlyStatementScreen
import br.com.assistentefinanceiro.ui.screens.MoreScreen
import br.com.assistentefinanceiro.ui.screens.PlanningScreen
import br.com.assistentefinanceiro.ui.screens.TransactionSearchScreen
import br.com.assistentefinanceiro.ui.viewmodels.MonthlyBudgetViewModel
import br.com.assistentefinanceiro.ui.viewmodels.ScreenViewModelFactory

private object AppRoute {
    const val STATEMENT = "statement"
    const val DIAGNOSTIC = "diagnostic"
    const val ACCOUNTS = "accounts"
    const val SEARCH = "search"
    const val SUMMARY = "summary"
    const val PLANNING = "planning"
    const val DATA = "data"
    const val BUDGET = "budget"
    const val MORE = "more"
    const val ABOUT = "about"
}

private data class BottomDestination(
    val route: String,
    val icon: ImageVector,
    val label: String,
)

@Composable
internal fun AssistenteFinanceiroApp(
    repository: FinancialRepository,
    preferences: BankPackagePreferences,
) {
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val activityViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            MainNavigationBar(
                currentRoute = currentRoute,
                onNavigate = navController::navigateReplacingCurrent,
            )
        },
    ) { rootPadding ->
        AppNavHost(
            navController = navController,
            repository = repository,
            preferences = preferences,
            activityViewModelStoreOwner = activityViewModelStoreOwner,
            modifier = Modifier.fillMaxSize().padding(rootPadding),
        )
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    repository: FinancialRepository,
    preferences: BankPackagePreferences,
    activityViewModelStoreOwner: ViewModelStoreOwner,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = AppRoute.STATEMENT,
        modifier = modifier,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(AppRoute.STATEMENT) {
            ActivityScopedDestination(activityViewModelStoreOwner) {
                MonthlyStatementScreen(repository = repository)
            }
        }
        composable(AppRoute.DIAGNOSTIC) {
            ActivityScopedDestination(activityViewModelStoreOwner) {
                DiagnosticScreen(
                    repository = repository,
                    preferences = preferences,
                    onBack = { navController.navigateReplacingCurrent(AppRoute.MORE) },
                )
            }
        }
        composable(AppRoute.ACCOUNTS) {
            ActivityScopedDestination(activityViewModelStoreOwner) {
                AccountsScreen(repository = repository)
            }
        }
        composable(AppRoute.SEARCH) {
            ActivityScopedDestination(activityViewModelStoreOwner) {
                TransactionSearchScreen(
                    repository = repository,
                    onBack = { navController.navigateReplacingCurrent(AppRoute.MORE) },
                )
            }
        }
        composable(AppRoute.SUMMARY) {
            ActivityScopedDestination(activityViewModelStoreOwner) {
                AnnualSummaryScreen(
                    repository = repository,
                    onBack = { navController.navigateReplacingCurrent(AppRoute.MORE) },
                )
            }
        }
        composable(AppRoute.PLANNING) {
            ActivityScopedDestination(activityViewModelStoreOwner) {
                PlanningScreen(repository = repository)
            }
        }
        composable(AppRoute.DATA) {
            ActivityScopedDestination(activityViewModelStoreOwner) {
                DataManagementScreen(
                    repository = repository,
                    onBack = { navController.navigateReplacingCurrent(AppRoute.MORE) },
                )
            }
        }
        composable(AppRoute.BUDGET) {
            ActivityScopedDestination(activityViewModelStoreOwner) {
                val budgetViewModel: MonthlyBudgetViewModel = viewModel(
                    factory = ScreenViewModelFactory {
                        MonthlyBudgetViewModel(repository)
                    },
                )
                LaunchedEffect(budgetViewModel) {
                    budgetViewModel.refresh()
                }
                MonthlyBudgetScreen(repository = repository)
            }
        }
        composable(AppRoute.MORE) {
            ActivityScopedDestination(activityViewModelStoreOwner) {
                MoreScreen(
                    onSearch = { navController.navigateReplacingCurrent(AppRoute.SEARCH) },
                    onSummary = { navController.navigateReplacingCurrent(AppRoute.SUMMARY) },
                    onData = { navController.navigateReplacingCurrent(AppRoute.DATA) },
                    onDiagnostic = {
                        navController.navigateReplacingCurrent(AppRoute.DIAGNOSTIC)
                    },
                    onAbout = { navController.navigateReplacingCurrent(AppRoute.ABOUT) },
                )
            }
        }
        composable(AppRoute.ABOUT) {
            ActivityScopedDestination(activityViewModelStoreOwner) {
                AboutScreen(onBack = { navController.navigateReplacingCurrent(AppRoute.MORE) })
            }
        }
    }
}

@Composable
private fun ActivityScopedDestination(
    viewModelStoreOwner: ViewModelStoreOwner,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalViewModelStoreOwner provides viewModelStoreOwner,
        content = content,
    )
}

@Composable
private fun MainNavigationBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    val selectedRoute = when (currentRoute) {
        null, AppRoute.STATEMENT -> AppRoute.STATEMENT
        AppRoute.PLANNING -> AppRoute.PLANNING
        AppRoute.BUDGET -> AppRoute.BUDGET
        AppRoute.ACCOUNTS -> AppRoute.ACCOUNTS
        else -> AppRoute.MORE
    }
    val destinations = listOf(
        BottomDestination(AppRoute.STATEMENT, Icons.Rounded.ReceiptLong, "Extrato"),
        BottomDestination(AppRoute.PLANNING, Icons.Rounded.EventNote, "Planejar"),
        BottomDestination(AppRoute.BUDGET, Icons.Rounded.DonutLarge, "Orçamento"),
        BottomDestination(AppRoute.ACCOUNTS, Icons.Rounded.AccountBalanceWallet, "Contas"),
        BottomDestination(AppRoute.MORE, Icons.Rounded.MoreHoriz, "Mais"),
    )

    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        ) {
            destinations.forEach { destination ->
                NavigationBarItem(
                    selected = selectedRoute == destination.route,
                    onClick = { onNavigate(destination.route) },
                    icon = {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = destination.label,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    label = {
                        Text(
                            destination.label,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }
        }
    }
}

private fun NavHostController.navigateReplacingCurrent(route: String) {
    val currentRoute = currentDestination?.route
    if (currentRoute == route) return

    navigate(route) {
        if (currentRoute != null) {
            popUpTo(currentRoute) { inclusive = true }
        }
        launchSingleTop = true
    }
}
