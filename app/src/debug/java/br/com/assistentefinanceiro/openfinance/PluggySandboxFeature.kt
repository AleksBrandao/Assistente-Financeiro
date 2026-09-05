package br.com.assistentefinanceiro.openfinance

import androidx.compose.runtime.Composable
import br.com.assistentefinanceiro.data.FinancialRepository

internal object PluggySandboxFeature {
    const val isEnabled: Boolean = true

    @Composable
    fun Screen(
        repository: FinancialRepository,
        onBack: () -> Unit,
    ) {
        PluggyConnectedScreen(repository = repository, onBack = onBack)
    }
}
