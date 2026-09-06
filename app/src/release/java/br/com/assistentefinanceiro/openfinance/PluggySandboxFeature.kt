package br.com.assistentefinanceiro.openfinance

import androidx.compose.runtime.Composable
import br.com.assistentefinanceiro.data.FinancialRepository

/** Release Open Finance flow: credentials stay in the backend; the APK stores only pairing data. */
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
