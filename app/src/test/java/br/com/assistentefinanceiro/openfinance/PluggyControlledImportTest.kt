package br.com.assistentefinanceiro.openfinance

import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.FinancialTransactionRecord
import br.com.assistentefinanceiro.notifications.FinancialTransactionType
import br.com.assistentefinanceiro.notifications.TransactionCategory
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluggyControlledImportTest {
    @Test
    fun planImportsOnlyPostedUnmatchedTransactionsInsideWindow() {
        val account = PluggyAccountSnapshot(
            externalId = "bank-remote",
            type = PluggyAccountType.BANK,
            subtype = "CHECKING_ACCOUNT",
            name = "Banco",
            currencyCode = "BRL",
            balance = BigDecimal.ZERO,
        )
        val newPosted = remoteBankTransaction(
            id = "new",
            amount = "25.00",
            date = "2026-09-03T12:00:00Z",
            status = PluggyTransactionStatus.POSTED,
            direction = PluggyTransactionDirection.DEBIT,
            category = "Alimentação",
        )
        val matched = remoteBankTransaction(
            id = "matched",
            amount = "30.00",
            date = "2026-09-02T12:00:00Z",
            status = PluggyTransactionStatus.POSTED,
            direction = PluggyTransactionDirection.DEBIT,
        )
        val pending = remoteBankTransaction(
            id = "pending",
            amount = "40.00",
            date = "2026-09-01T12:00:00Z",
            status = PluggyTransactionStatus.PENDING,
            direction = PluggyTransactionDirection.DEBIT,
        )
        val old = remoteBankTransaction(
            id = "old",
            amount = "50.00",
            date = "2026-01-01T12:00:00Z",
            status = PluggyTransactionStatus.POSTED,
            direction = PluggyTransactionDirection.DEBIT,
        )
        val localMatched = FinancialTransactionRecord(
            id = 1,
            sourceEventId = null,
            direction = FinancialTransactionDirection.EXPENSE,
            type = FinancialTransactionType.IMPORTED_EXPENSE,
            amount = "30.00",
            occurredAt = "2026-09-02T12:00:00",
            description = "Descrição",
            sourcePackage = "test",
            accountId = 7,
        )
        val reconciliation = PluggyReconciliationPreview(
            accounts = listOf(
                PluggyAccountReconciliation(
                    pluggyAccountExternalId = "bank-remote",
                    pluggyAccountName = "Banco",
                    pluggyAccountType = PluggyAccountType.BANK,
                    status = PluggyReconciliationStatus.CONFIRMED,
                    localAccountId = 7,
                    localAccountName = "Conta local",
                    compatibleCandidateCount = 1,
                    reasons = listOf("confirmado"),
                    transactionCounts = PluggyReconciliationCounts(1, 0, 3),
                    billCounts = PluggyReconciliationCounts(0, 0, 0),
                    pluggyCategoryCount = 1,
                ),
            ),
            distinctPluggyCategories = 1,
            directCategoryMatches = 1,
        )

        val plan = PluggyControlledImportPlanner.plan(
            datasets = listOf(
                PluggyAccountDataset(account, listOf(newPosted, matched, pending, old), emptyList()),
            ),
            reconciliation = reconciliation,
            selectedExternalAccountIds = setOf("bank-remote"),
            localTransactions = listOf(localMatched),
            today = LocalDate.of(2026, 9, 4),
            lookbackDays = 90,
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(1, plan.importable)
        assertEquals("new", plan.drafts.single().externalTransactionId)
        assertEquals(TransactionCategory.FOOD, plan.drafts.single().category)
        assertEquals(1, plan.matchedExisting)
        assertEquals(1, plan.skippedPending)
        assertEquals(1, plan.skippedOutsideWindow)
    }

    @Test
    fun cardCreditsAreExcludedFromFirstWritePhase() {
        val account = PluggyAccountSnapshot(
            externalId = "card-remote",
            type = PluggyAccountType.CREDIT,
            subtype = "CREDIT_CARD",
            name = "Cartão",
            currencyCode = "BRL",
            balance = BigDecimal.ZERO,
        )
        val paymentOrRefund = PluggyTransactionSnapshot(
            externalId = "credit-movement",
            accountExternalId = "card-remote",
            amount = BigDecimal("100.00"),
            date = Instant.parse("2026-09-03T12:00:00Z"),
            direction = PluggyTransactionDirection.CREDIT,
            status = PluggyTransactionStatus.POSTED,
            description = "Crédito no cartão",
        )
        val reconciliation = PluggyReconciliationPreview(
            accounts = listOf(
                PluggyAccountReconciliation(
                    pluggyAccountExternalId = "card-remote",
                    pluggyAccountName = "Cartão",
                    pluggyAccountType = PluggyAccountType.CREDIT,
                    status = PluggyReconciliationStatus.STRONG,
                    localAccountId = 9,
                    localAccountName = "Cartão local",
                    compatibleCandidateCount = 1,
                    reasons = listOf("final do cartão coincide"),
                    transactionCounts = PluggyReconciliationCounts(0, 0, 1),
                    billCounts = PluggyReconciliationCounts(0, 0, 0),
                    pluggyCategoryCount = 0,
                ),
            ),
            distinctPluggyCategories = 0,
            directCategoryMatches = 0,
        )

        val plan = PluggyControlledImportPlanner.plan(
            datasets = listOf(PluggyAccountDataset(account, listOf(paymentOrRefund), emptyList())),
            reconciliation = reconciliation,
            selectedExternalAccountIds = setOf("card-remote"),
            localTransactions = emptyList(),
            today = LocalDate.of(2026, 9, 4),
            zoneId = ZoneOffset.UTC,
        )

        assertTrue(plan.drafts.isEmpty())
        assertEquals(1, plan.skippedCreditCardCredits)
    }

    @Test
    fun confirmedLinkOverridesHeuristic() {
        val remoteAccount = PluggyAccountSnapshot(
            externalId = "remote-card",
            type = PluggyAccountType.CREDIT,
            subtype = "CREDIT_CARD",
            name = "Cartão remoto",
            currencyCode = "BRL",
            balance = BigDecimal.ZERO,
        )
        val localA = br.com.assistentefinanceiro.notifications.FinancialAccountRecord(
            id = 1,
            name = "A",
            type = br.com.assistentefinanceiro.notifications.FinancialAccountType.CREDIT_CARD,
        )
        val localB = br.com.assistentefinanceiro.notifications.FinancialAccountRecord(
            id = 2,
            name = "B",
            type = br.com.assistentefinanceiro.notifications.FinancialAccountType.CREDIT_CARD,
        )

        val preview = PluggyReconciliationEngine.reconcile(
            PluggyReconciliationInput(
                pluggyAccounts = listOf(PluggyAccountDataset(remoteAccount, emptyList(), emptyList())),
                localAccounts = listOf(localA, localB),
                localTransactions = emptyList(),
                localInvoicesByAccount = emptyMap(),
                confirmedAccountLinks = mapOf("remote-card" to 2L),
                zoneId = ZoneOffset.UTC,
            ),
        )

        assertEquals(PluggyReconciliationStatus.CONFIRMED, preview.accounts.single().status)
        assertEquals(2L, preview.accounts.single().localAccountId)
    }

    private fun remoteBankTransaction(
        id: String,
        amount: String,
        date: String,
        status: PluggyTransactionStatus,
        direction: PluggyTransactionDirection,
        category: String? = null,
    ): PluggyTransactionSnapshot = PluggyTransactionSnapshot(
        externalId = id,
        accountExternalId = "bank-remote",
        amount = BigDecimal(amount),
        date = Instant.parse(date),
        direction = direction,
        status = status,
        description = "Descrição",
        category = category,
    )
}
