package br.com.assistentefinanceiro.openfinance

import android.content.Context
import android.util.Log
import br.com.assistentefinanceiro.data.ExternalDataProvider
import br.com.assistentefinanceiro.data.FinancialRepository
import br.com.assistentefinanceiro.notifications.FinancialAccountType
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Consumes the durable webhook signal created by the backend.
 *
 * No financial record is inferred here: when a signal exists the app downloads a fresh Pluggy
 * snapshot and imports only provider records that are actually present in that snapshot.
 */
internal object PluggyAutoSyncFeature {
    private const val TAG = "PluggyAutoSync"
    private val mutex = Mutex()

    suspend fun syncIfPending(
        context: Context,
        repository: FinancialRepository,
    ) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    syncIfPendingLocked(context.applicationContext, repository)
                }.onFailure { error ->
                    // Foreground startup must never fail because the provider/backend is temporarily
                    // unavailable. The webhook remains pending and will be retried on a later start.
                    Log.w(TAG, "Webhook-triggered Open Finance sync failed", error)
                }
            }
        }
    }

    private fun syncIfPendingLocked(
        context: Context,
        repository: FinancialRepository,
    ) {
        val settings = PluggyConnectionStore(context).load()
        val itemId = settings.itemId?.takeIf(String::isNotBlank) ?: return
        if (settings.backendUrl.isBlank() || settings.accessCode.isBlank()) return

        val webhookClient = PluggyWebhookClient()
        val status = webhookClient.fetchStatus(
            backendUrl = settings.backendUrl,
            accessCode = settings.accessCode,
            itemId = itemId,
        )
        if (!status.hasPendingChanges) return

        val eventId = status.lastEventId ?: return

        // An Item error is not financial data and must not be converted into transactions. Keep the
        // signal pending so a later successful item/updated event can supersede it.
        if (status.lastEvent == "item/error") return

        // Deletion propagation requires an explicit provider-id deletion path in persistence.
        // Until that path exists, do not acknowledge a deletion signal: stale local data is safer
        // than silently claiming that a deletion was applied when it was not.
        if (status.transactionChanges.deleted > 0) return

        val links = repository.externalAccountLinks(ExternalDataProvider.PLUGGY)
        if (links.isEmpty()) return
        val linkedAccountIds = links.map { it.localAccountId }.toSet()
        val confirmedLinks = links.associate { it.externalAccountId to it.localAccountId }

        val remote = PluggyReadOnlyClient().fetchPreview(
            backendUrl = settings.backendUrl,
            accessCode = settings.accessCode,
            itemId = itemId,
        )
        val datasets = remote.accounts.map { accountPreview ->
            PluggyAccountDataset(
                account = accountPreview.account,
                transactions = accountPreview.transactions,
                bills = accountPreview.bills,
            )
        }
        val selectedExternalAccountIds = datasets
            .map { it.account.externalId }
            .filter { it in confirmedLinks }
            .toSet()
        if (selectedExternalAccountIds.isEmpty()) return

        val localAccounts = repository.financialAccounts()
        val localInvoicesByAccount = localAccounts
            .filter { it.id in linkedAccountIds && it.type == FinancialAccountType.CREDIT_CARD }
            .associate { account -> account.id to repository.creditCardInvoices(account.id) }
        val localTransactions = repository.granularTransactions()
        val reconciliation = PluggyReconciliationEngine.reconcile(
            PluggyReconciliationInput(
                pluggyAccounts = datasets,
                localAccounts = localAccounts,
                localTransactions = localTransactions,
                localInvoicesByAccount = localInvoicesByAccount,
                confirmedAccountLinks = confirmedLinks,
            ),
        )
        val today = LocalDate.now()
        val plan = PluggyControlledImportPlanner.plan(
            datasets = datasets,
            reconciliation = reconciliation,
            selectedExternalAccountIds = selectedExternalAccountIds,
            localTransactions = localTransactions,
            today = today,
            lookbackDays = null,
            startDate = null,
            endDate = today,
        )

        repository.importExternalTransactions(plan.drafts)
        repository.importExternalBills(plan.billDrafts)

        // Acknowledge exactly the event observed before the snapshot. If a newer webhook arrived
        // during synchronization, the backend deliberately refuses to clear that newer signal.
        webhookClient.acknowledge(
            backendUrl = settings.backendUrl,
            accessCode = settings.accessCode,
            itemId = itemId,
            throughEventId = eventId,
        )
    }
}
