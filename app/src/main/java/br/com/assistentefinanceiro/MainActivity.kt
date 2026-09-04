package br.com.assistentefinanceiro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import br.com.assistentefinanceiro.data.DiagnosticFinancialRepository
import br.com.assistentefinanceiro.notifications.BankPackagePreferences
import br.com.assistentefinanceiro.notifications.BudgetAlertManager
import br.com.assistentefinanceiro.ui.navigation.AssistenteFinanceiroApp
import br.com.assistentefinanceiro.ui.theme.AssistenteFinanceiroTheme

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BudgetAlertManager.ensureChannel(applicationContext)
        requestNotificationPermissionIfNeeded()
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

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
