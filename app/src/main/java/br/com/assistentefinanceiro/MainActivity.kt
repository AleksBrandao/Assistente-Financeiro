package br.com.assistentefinanceiro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import br.com.assistentefinanceiro.data.DiagnosticFinancialRepository
import br.com.assistentefinanceiro.notifications.BankPackagePreferences
import br.com.assistentefinanceiro.ui.navigation.AssistenteFinanceiroApp
import br.com.assistentefinanceiro.ui.theme.AssistenteFinanceiroTheme

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
                AssistenteFinanceiroApp(
                    repository = repository,
                    preferences = preferences,
                )
            }
        }
    }
}
