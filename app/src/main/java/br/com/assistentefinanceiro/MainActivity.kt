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
import androidx.lifecycle.lifecycleScope
import br.com.assistentefinanceiro.data.DiagnosticFinancialRepository
import br.com.assistentefinanceiro.notifications.BankPackagePreferences
import br.com.assistentefinanceiro.notifications.BudgetAlertManager
import br.com.assistentefinanceiro.openfinance.PluggyAutoSyncFeature
import br.com.assistentefinanceiro.ui.navigation.AssistenteFinanceiroApp
import br.com.assistentefinanceiro.ui.theme.AssistenteFinanceiroTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var repository: DiagnosticFinancialRepository

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = DiagnosticFinancialRepository(applicationContext)
        BudgetAlertManager.ensureChannel(applicationContext)
        requestNotificationPermissionIfNeeded()
        enableEdgeToEdge()
        setContent {
            AssistenteFinanceiroTheme {
                val appRepository = remember { repository }
                val preferences = remember { BankPackagePreferences(applicationContext) }
                AssistenteFinanceiroApp(
                    repository = appRepository,
                    preferences = preferences,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!::repository.isInitialized) return
        lifecycleScope.launch {
            PluggyAutoSyncFeature.syncIfPending(
                context = applicationContext,
                repository = repository,
            )
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
