package br.com.assistentefinanceiro.openfinance

import android.content.Context
import br.com.assistentefinanceiro.data.FinancialRepository

/** Debug sandbox keeps its explicit/manual flow and does not consume production webhook signals. */
internal object PluggyAutoSyncFeature {
    suspend fun syncIfPending(
        context: Context,
        repository: FinancialRepository,
    ) = Unit
}
