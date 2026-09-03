package br.com.assistentefinanceiro.ui.viewmodels

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import br.com.assistentefinanceiro.data.FinancialRepository
import br.com.assistentefinanceiro.notifications.BankPackagePreferences
import br.com.assistentefinanceiro.notifications.DiagnosticEvent
import br.com.assistentefinanceiro.notifications.FinanceNotificationListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DiagnosticUiState(
    val allowedPackages: Set<String>,
    val candidates: List<Pair<String, String>>,
    val events: List<DiagnosticEvent>,
    val notificationAccessEnabled: Boolean,
)

internal class DiagnosticViewModel(
    context: Context,
    private val repository: FinancialRepository,
    private val preferences: BankPackagePreferences,
) : ViewModel() {
    private val applicationContext = context.applicationContext
    private val _uiState = MutableStateFlow(loadState())
    val uiState: StateFlow<DiagnosticUiState> = _uiState.asStateFlow()

    fun refresh() {
        _uiState.value = loadState()
    }

    fun setPackageAllowed(packageName: String, allowed: Boolean) {
        if (allowed) preferences.allow(packageName) else preferences.remove(packageName)
        refresh()
    }

    fun clearEvents() {
        repository.clearEvents()
        refresh()
    }

    private fun loadState() = DiagnosticUiState(
        allowedPackages = preferences.allowedPackages(),
        candidates = repository.candidates(),
        events = repository.recentEvents(),
        notificationAccessEnabled = notificationAccessEnabled(),
    )

    private fun notificationAccessEnabled(): Boolean {
        val component = ComponentName(applicationContext, FinanceNotificationListener::class.java)
        return Settings.Secure.getString(
            applicationContext.contentResolver,
            "enabled_notification_listeners",
        )
            ?.split(":")
            ?.any { ComponentName.unflattenFromString(it) == component } == true
    }
}
