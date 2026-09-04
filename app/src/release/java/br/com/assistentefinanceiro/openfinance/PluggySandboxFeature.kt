package br.com.assistentefinanceiro.openfinance

import androidx.compose.runtime.Composable
import br.com.assistentefinanceiro.data.FinancialRepository

/** Release stub: the Pluggy manual-key sandbox is intentionally unavailable in production. */
internal object PluggySandboxFeature {
    const val isEnabled: Boolean = false

    @Composable
    fun Screen(
        repository: FinancialRepository,
        onBack: () -> Unit,
    ) = Unit
}
