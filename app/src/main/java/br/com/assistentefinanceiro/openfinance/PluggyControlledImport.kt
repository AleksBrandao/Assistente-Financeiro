package br.com.assistentefinanceiro.openfinance

import br.com.assistentefinanceiro.data.ExternalDataProvider
import br.com.assistentefinanceiro.data.ExternalTransactionImportDraft
import br.com.assistentefinanceiro.notifications.FinancialAccountIdentity
import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.FinancialTransactionRecord
import br.com.assistentefinanceiro.notifications.FinancialTransactionType
import br.com.assistentefinanceiro.notifications.TransactionCategory
import br.com.assistentefinanceiro.notifications.TransactionOrigin
import br.com.assistentefinanceiro.notifications.TransactionStatus
import java.time.LocalDate
import java.time.ZoneId

data class PluggyControlledImportPlan(
    val drafts: List<ExternalTransactionImportDraft>,
    val selectedAccounts: Int,
    val matchedExisting: Int,
    val skippedReview: Int,
    val skippedPending: Int,
    val skippedCreditCardCredits: Int,
    val skippedOutsideWindow: Int,
) {
    val importable: Int get() = drafts.size
}

/**
 * Builds an explicit import plan. Nothing marked REVIEW is imported, PENDING is excluded in this
 * first write phase, and credit-side movements of CREDIT accounts are excluded because invoice
 * payments/refunds need a dedicated reconciliation rule.
 */
object PluggyControlledImportPlanner {
    fun plan(
        datasets: List<PluggyAccountDataset>,
        reconciliation: PluggyReconciliationPreview,
        selectedExternalAccountIds: Set<String>,
        localTransactions: List<FinancialTransactionRecord>,
        today: LocalDate = LocalDate.now(),
        lookbackDays: Long = 90,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): PluggyControlledImportPlan {
        require(lookbackDays > 0)
        val earliest = today.minusDays(lookbackDays)
        var matchedExisting = 0
        var skippedReview = 0
        var skippedPending = 0
        var skippedCreditCardCredits = 0
        var skippedOutsideWindow = 0
        val drafts = mutableListOf<ExternalTransactionImportDraft>()

        val selectedResults = reconciliation.accounts.filter { result ->
            result.pluggyAccountExternalId in selectedExternalAccountIds &&
                result.localAccountId != null &&
                result.status in setOf(
                    PluggyReconciliationStatus.CONFIRMED,
                    PluggyReconciliationStatus.STRONG,
                )
        }
        val datasetsById = datasets.associateBy { it.account.externalId }

        selectedResults.forEach { accountResult ->
            val dataset = datasetsById[accountResult.pluggyAccountExternalId] ?: return@forEach
            val localAccountId = checkNotNull(accountResult.localAccountId)
            val localForAccount = localTransactions.filter { it.accountId == localAccountId }
            dataset.transactions.forEach { remote ->
                if (remote.status != PluggyTransactionStatus.POSTED) {
                    skippedPending++
                    return@forEach
                }
                val accountingDate = remote.date.atZone(zoneId).toLocalDate()
                if (accountingDate.isBefore(earliest) || accountingDate.isAfter(today)) {
                    skippedOutsideWindow++
                    return@forEach
                }
                if (
                    dataset.account.type == PluggyAccountType.CREDIT &&
                    remote.direction == PluggyTransactionDirection.CREDIT
                ) {
                    skippedCreditCardCredits++
                    return@forEach
                }
                when (
                    PluggyReconciliationEngine.transactionMatchStatus(
                        remote = remote,
                        local = localForAccount,
                        zoneId = zoneId,
                    )
                ) {
                    PluggyTransactionMatchStatus.MATCHED -> {
                        matchedExisting++
                        return@forEach
                    }
                    PluggyTransactionMatchStatus.REVIEW -> {
                        skippedReview++
                        return@forEach
                    }
                    PluggyTransactionMatchStatus.UNMATCHED -> Unit
                }

                val direction = when (remote.direction) {
                    PluggyTransactionDirection.CREDIT -> FinancialTransactionDirection.INCOME
                    PluggyTransactionDirection.DEBIT -> FinancialTransactionDirection.EXPENSE
                }
                val type = transactionType(dataset.account.type, remote, direction)
                val category = directCategory(remote.category, direction)
                drafts += ExternalTransactionImportDraft(
                    provider = ExternalDataProvider.PLUGGY,
                    externalTransactionId = remote.externalId,
                    externalAccountId = dataset.account.externalId,
                    localAccountId = localAccountId,
                    direction = direction,
                    type = type,
                    amount = remote.absoluteAmount,
                    occurredAt = remote.date.atZone(zoneId).toLocalDateTime(),
                    description = remote.description.trim().ifBlank { defaultDescription(type) },
                    status = TransactionStatus.REALIZED,
                    category = category,
                    originalCategory = remote.category,
                    origin = TransactionOrigin.PLUGGY,
                    purchaseAt = remote.purchaseDate?.toString(),
                    sourceCategoryId = remote.categoryId,
                    operationType = remote.operationType,
                    paymentMethod = remote.paymentMethod,
                    installmentNumber = remote.installmentNumber,
                    totalInstallments = remote.totalInstallments,
                    billForecastPeriod = remote.billForecastDate,
                    externalBillId = remote.billExternalId,
                )
            }
        }

        return PluggyControlledImportPlan(
            drafts = drafts,
            selectedAccounts = selectedResults.size,
            matchedExisting = matchedExisting,
            skippedReview = skippedReview,
            skippedPending = skippedPending,
            skippedCreditCardCredits = skippedCreditCardCredits,
            skippedOutsideWindow = skippedOutsideWindow,
        )
    }

    private fun transactionType(
        accountType: PluggyAccountType,
        remote: PluggyTransactionSnapshot,
        direction: FinancialTransactionDirection,
    ): FinancialTransactionType = when {
        accountType == PluggyAccountType.CREDIT -> FinancialTransactionType.CARD_PURCHASE
        direction == FinancialTransactionDirection.INCOME &&
            (remote.operationType.equals("PIX", ignoreCase = true) ||
                remote.paymentMethod.equals("PIX", ignoreCase = true)) ->
            FinancialTransactionType.PIX_RECEIVED
        direction == FinancialTransactionDirection.INCOME ->
            FinancialTransactionType.IMPORTED_INCOME
        else -> FinancialTransactionType.IMPORTED_EXPENSE
    }

    private fun directCategory(
        source: String?,
        direction: FinancialTransactionDirection,
    ): TransactionCategory {
        val key = source?.let(FinancialAccountIdentity::normalize).orEmpty()
        if (key.isBlank()) return TransactionCategory.UNCATEGORIZED
        return TransactionCategory.entries.firstOrNull { category ->
            category != TransactionCategory.UNCATEGORIZED &&
                category.supports(direction) &&
                key in setOf(
                    FinancialAccountIdentity.normalize(category.name),
                    FinancialAccountIdentity.normalize(category.displayName),
                )
        } ?: TransactionCategory.UNCATEGORIZED
    }

    private fun defaultDescription(type: FinancialTransactionType): String = when (type) {
        FinancialTransactionType.CARD_PURCHASE -> "Compra no cartão"
        FinancialTransactionType.PIX_RECEIVED -> "PIX recebido"
        FinancialTransactionType.IMPORTED_EXPENSE -> "Despesa Pluggy"
        FinancialTransactionType.IMPORTED_INCOME -> "Receita Pluggy"
        FinancialTransactionType.MANUAL_EXPENSE -> "Despesa"
        FinancialTransactionType.MANUAL_INCOME -> "Receita"
    }
}
