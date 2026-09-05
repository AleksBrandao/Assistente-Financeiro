package br.com.assistentefinanceiro.openfinance

import br.com.assistentefinanceiro.data.ExternalAccountLinkRecord
import br.com.assistentefinanceiro.data.ExternalDataProvider
import br.com.assistentefinanceiro.data.FinancialRepository
import br.com.assistentefinanceiro.notifications.FinancialAccountIdentity
import br.com.assistentefinanceiro.notifications.FinancialAccountType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * Creates the minimum local account structure required to import Pluggy data when no compatible
 * local account exists yet. It is intentionally debug-only while the Open Finance flow is being
 * validated.
 */
internal object PluggyFirstImportProvisioner {
    fun provisionSelectedAccounts(
        repository: FinancialRepository,
        remote: PluggySandboxPreview,
        selectedExternalAccountIds: Set<String>,
        today: LocalDate = LocalDate.now(),
        lookbackDays: Long? = 90,
        startDate: LocalDate? = null,
        endDate: LocalDate = today,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Int {
        require(lookbackDays == null || lookbackDays > 0)
        require(!endDate.isAfter(today))
        val earliest = startDate ?: lookbackDays?.let { today.minusDays(it) }
        require(earliest == null || !earliest.isAfter(endDate))
        if (selectedExternalAccountIds.isEmpty()) return 0

        val initialAccounts = repository.financialAccounts()
        val initiallyHasBank = initialAccounts.any { it.type == FinancialAccountType.BANK_ACCOUNT }
        val initiallyHasCard = initialAccounts.any { it.type == FinancialAccountType.CREDIT_CARD }
        val existingLinks = repository.externalAccountLinks(ExternalDataProvider.PLUGGY)
            .associateBy { it.externalAccountId }
        val usedNames = repository.financialAccounts()
            .map { FinancialAccountIdentity.normalize(it.name) }
            .toMutableSet()
        var defaultBankAssigned = initialAccounts.any { it.isDefault }
        var createdCount = 0

        remote.accounts
            .filter { it.account.externalId in selectedExternalAccountIds }
            .forEach { accountPreview ->
                val externalId = accountPreview.account.externalId
                if (externalId in existingLinks) return@forEach

                val localType = when (accountPreview.account.type) {
                    PluggyAccountType.BANK -> FinancialAccountType.BANK_ACCOUNT
                    PluggyAccountType.CREDIT -> FinancialAccountType.CREDIT_CARD
                }
                val compatibleExistedBeforeProvisioning = when (localType) {
                    FinancialAccountType.BANK_ACCOUNT -> initiallyHasBank
                    FinancialAccountType.CREDIT_CARD -> initiallyHasCard
                }
                if (compatibleExistedBeforeProvisioning) return@forEach

                val cardIdentifiers = accountPreview.transactions
                    .mapNotNull { it.cardLastFour }
                    .distinct()
                    .sorted()
                    .joinToString(",")
                    .takeIf { it.isNotBlank() }
                val localName = uniqueLocalName(
                    accountPreview = accountPreview,
                    cardIdentifiers = cardIdentifiers,
                    usedNames = usedNames,
                )
                val latestBill = accountPreview.bills.maxByOrNull { it.dueDate }
                val opening = when (accountPreview.account.type) {
                    PluggyAccountType.BANK -> bankOpeningSnapshot(
                        accountPreview = accountPreview,
                        today = today,
                        earliest = earliest,
                        endDate = endDate,
                        zoneId = zoneId,
                    )
                    PluggyAccountType.CREDIT -> OpeningSnapshot(BigDecimal.ZERO, null)
                }
                val makeDefault = localType == FinancialAccountType.BANK_ACCOUNT && !defaultBankAssigned

                check(
                    repository.saveFinancialAccount(
                        id = null,
                        name = localName,
                        type = localType,
                        closingDay = latestBill?.closingDate?.dayOfMonth,
                        dueDay = latestBill?.dueDate?.dayOfMonth
                            ?: accountPreview.account.creditData?.balanceDueDate?.dayOfMonth,
                        isDefault = makeDefault,
                        cardIdentifiers = cardIdentifiers,
                        openingBalance = opening.balance,
                        openingBalanceDate = opening.date,
                    ),
                ) { "Não foi possível criar a conta local para ${accountPreview.account.name}" }

                val createdAccount = repository.financialAccounts().firstOrNull { account ->
                    account.type == localType &&
                        FinancialAccountIdentity.normalize(account.name) ==
                        FinancialAccountIdentity.normalize(localName)
                } ?: error("Conta local criada, mas não foi possível recuperar seu identificador")

                check(
                    repository.saveExternalAccountLink(
                        ExternalAccountLinkRecord(
                            provider = ExternalDataProvider.PLUGGY,
                            externalAccountId = externalId,
                            localAccountId = createdAccount.id,
                        ),
                    ),
                ) { "Não foi possível vincular ${accountPreview.account.name} à conta local" }

                usedNames += FinancialAccountIdentity.normalize(localName)
                if (makeDefault) defaultBankAssigned = true
                createdCount++
            }

        return createdCount
    }

    private data class OpeningSnapshot(
        val balance: BigDecimal,
        val date: LocalDate?,
    )

    /**
     * Pluggy supplies the current balance. Reverse the net change of the selected POSTED history
     * to place the local opening balance immediately before the first imported movement.
     */
    private fun bankOpeningSnapshot(
        accountPreview: PluggySandboxAccountPreview,
        today: LocalDate,
        earliest: LocalDate?,
        endDate: LocalDate,
        zoneId: ZoneId,
    ): OpeningSnapshot {
        val postedInWindow = accountPreview.transactions.filter { transaction ->
            val accountingDate = transaction.date.atZone(zoneId).toLocalDate()
            transaction.status == PluggyTransactionStatus.POSTED &&
                (earliest == null || !accountingDate.isBefore(earliest)) &&
                !accountingDate.isAfter(endDate)
        }
        if (postedInWindow.isEmpty()) {
            return OpeningSnapshot(accountPreview.account.balance, today)
        }

        val netChange = postedInWindow.fold(BigDecimal.ZERO) { total, transaction ->
            when (transaction.direction) {
                PluggyTransactionDirection.CREDIT -> total + transaction.absoluteAmount
                PluggyTransactionDirection.DEBIT -> total - transaction.absoluteAmount
            }
        }
        val firstImportedDate = postedInWindow.minOf { it.date.atZone(zoneId).toLocalDate() }
        return OpeningSnapshot(
            balance = accountPreview.account.balance - netChange,
            date = firstImportedDate.minusDays(1),
        )
    }

    private fun uniqueLocalName(
        accountPreview: PluggySandboxAccountPreview,
        cardIdentifiers: String?,
        usedNames: Set<String>,
    ): String {
        val fallback = when (accountPreview.account.type) {
            PluggyAccountType.BANK -> "Conta Pluggy"
            PluggyAccountType.CREDIT -> "Cartão Pluggy"
        }
        val rawBase = accountPreview.account.name.trim().ifBlank { fallback }
        val singleLastFour = cardIdentifiers
            ?.takeIf { ',' !in it }
            ?.takeIf { it.length == 4 }
        val base = if (
            accountPreview.account.type == PluggyAccountType.CREDIT &&
            singleLastFour != null &&
            !rawBase.contains(singleLastFour)
        ) {
            "$rawBase • $singleLastFour"
        } else {
            rawBase
        }

        if (FinancialAccountIdentity.normalize(base) !in usedNames) return base
        var suffix = 2
        while (FinancialAccountIdentity.normalize("$base ($suffix)") in usedNames) suffix++
        return "$base ($suffix)"
    }
}
