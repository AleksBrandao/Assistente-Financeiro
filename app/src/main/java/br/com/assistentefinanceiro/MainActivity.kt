package br.com.assistentefinanceiro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.assistentefinanceiro.data.DiagnosticFinancialRepository
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
import br.com.assistentefinanceiro.ui.theme.AssistenteFinanceiroTheme

private enum class AppScreen {
    STATEMENT,
    DIAGNOSTIC,
    ACCOUNTS,
    SEARCH,
    SUMMARY,
    PLANNING,
    DATA,
    BUDGET,
    MORE,
    ABOUT,
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AssistenteFinanceiroTheme {
                val repository = remember {
                    DiagnosticFinancialRepository(applicationContext)
                }
                val preferences = remember { BankPackagePreferences(applicationContext) }
                var screen by remember { mutableStateOf(AppScreen.STATEMENT) }

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        MainNavigationBar(screen = screen, onNavigate = { screen = it })
                    },
                ) { rootPadding ->
                    Box(Modifier.fillMaxSize().padding(rootPadding)) {
                        when (screen) {
                            AppScreen.STATEMENT -> MonthlyStatementScreen(
                                repository = repository,
                            )
                            AppScreen.DIAGNOSTIC -> DiagnosticScreen(
                                repository = repository,
                                preferences = preferences,
                                onBack = { screen = AppScreen.MORE },
                            )
                            AppScreen.ACCOUNTS -> AccountsScreen(repository = repository)
                            AppScreen.SEARCH -> TransactionSearchScreen(
                                repository = repository,
                                onBack = { screen = AppScreen.MORE },
                            )
                            AppScreen.SUMMARY -> AnnualSummaryScreen(
                                repository = repository,
                                onBack = { screen = AppScreen.MORE },
                            )
                            AppScreen.PLANNING -> PlanningScreen(repository = repository)
                            AppScreen.DATA -> DataManagementScreen(
                                repository = repository,
                                onBack = { screen = AppScreen.MORE },
                            )
                            AppScreen.BUDGET -> MonthlyBudgetScreen(repository = repository)
                            AppScreen.MORE -> MoreScreen(
                                onSearch = { screen = AppScreen.SEARCH },
                                onSummary = { screen = AppScreen.SUMMARY },
                                onData = { screen = AppScreen.DATA },
                                onDiagnostic = { screen = AppScreen.DIAGNOSTIC },
                                onAbout = { screen = AppScreen.ABOUT },
                            )
                            AppScreen.ABOUT -> AboutScreen(onBack = { screen = AppScreen.MORE })
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MainNavigationBar(
        screen: AppScreen,
        onNavigate: (AppScreen) -> Unit,
    ) {
        val selected = when (screen) {
            AppScreen.STATEMENT -> AppScreen.STATEMENT
            AppScreen.PLANNING -> AppScreen.PLANNING
            AppScreen.BUDGET -> AppScreen.BUDGET
            AppScreen.ACCOUNTS -> AppScreen.ACCOUNTS
            else -> AppScreen.MORE
        }
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                listOf(
                    Triple(AppScreen.STATEMENT, Icons.Rounded.ReceiptLong, "Extrato"),
                    Triple(AppScreen.PLANNING, Icons.Rounded.EventNote, "Planejar"),
                    Triple(AppScreen.BUDGET, Icons.Rounded.DonutLarge, "Orçamento"),
                    Triple(AppScreen.ACCOUNTS, Icons.Rounded.AccountBalanceWallet, "Contas"),
                    Triple(AppScreen.MORE, Icons.Rounded.MoreHoriz, "Mais"),
                ).forEach { (destination, icon, label) ->
                    NavigationBarItem(
                        selected = selected == destination,
                        onClick = { onNavigate(destination) },
                        icon = {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                modifier = Modifier.size(24.dp),
                            )
                        },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
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
}
