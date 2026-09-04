package br.com.assistentefinanceiro.openfinance

import br.com.assistentefinanceiro.notifications.CreditCardInvoiceRecord
import br.com.assistentefinanceiro.notifications.CreditCardInvoiceStatus
import br.com.assistentefinanceiro.notifications.FinancialAccountRecord
import br.com.assistentefinanceiro.notifications.FinancialAccountType
import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.FinancialTransactionRecord
import br.com.assistentefinanceiro.notifications.FinancialTransactionType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluggyReconciliationTest {
    @Test
    fun creditCardLastFourAndBillingDatesProduceStrongMatch() {
        val localAccount = FinancialAccountRecord(
            id = 10,
            name = "Cartão principal",
            type = FinancialAccountType.CREDIT_CARD,
            closingDay = 14,
            dueDay = 21,
            cardIdentifiers = "1234",
        )
        val remoteTransaction = remoteCardTransaction(
            amount = "171.70",
            date = "2026-07-19T15:24:20Z",
            cardLastFour = "1234",
            category = "Shopping",
        )
        val remoteBill = PluggyBillSnapshot(
            externalId = "bill-1",
            accountExternalId = "remote-card",
            dueDate = LocalDate.parse("2026-07-21"),
            closingDate = LocalDate.parse("2026-07-14"),
            totalAmount = BigDecimal("2613.81"),
            minimumPaymentAmount = BigDecimal("261.38"),
            allowsInstallments = false,
        )
        val localTransaction = FinancialTransactionRecord(
            id = 100,
            sourceEventId = null,
            direction = FinancialTransactionDirection.EXPENSE,
            type = FinancialTransactionType.CARD_PURCHASE,
            amount = "171.70",
            occurredAt = "2026-07-19T15:24:20",
            description = "Loja exemplo",
            sourcePackage = "test",
            accountId = 10,
        )
        val localInvoice = CreditCardInvoiceRecord(
            id = 200,
            accountId = 10,
            closingPeriod = YearMonth.of(2026, 7),
            closingDate = LocalDate.parse("2026-07-14"),
            dueDate = LocalDate.parse("2026-07-21"),
            status = CreditCardInvoiceStatus.CLOSED,
            total = BigDecimal("2613.81"),
            paidAmount = BigDecimal.ZERO,
            outstandingAmount = BigDecimal("2613.81"),
            transactionCount = 1,
        )

        val result = PluggyReconciliationEngine.reconcile(
            PluggyReconciliationInput(
                pluggyAccounts = listOf(
                    PluggyAccountDataset(
                        account = remoteCreditAccount(),
                        transactions = listOf(remoteTransaction),
                        bills = listOf(remoteBill),
                    ),
                ),
                localAccounts = listOf(localAccount),
                localTransactions = listOf(localTransaction),
                localInvoicesByAccount = mapOf(10L to listOf(localInvoice)),
                zoneId = ZoneOffset.UTC,
            ),
        )

        val account = result.accounts.single()
        assertEquals(PluggyReconciliationStatus.STRONG, account.status)
        assertEquals(10L, account.localAccountId)
        assertTrue(account.reasons.contains("final do cartão coincide"))
        assertEquals(1, account.transactionCounts.matched)
        assertEquals(1, account.billCounts.matched)
        assertEquals(1, result.distinctPluggyCategories)
        assertEquals(1, result.directCategoryMatches)
    }

    @Test
    fun ambiguousCreditCardsWithoutIdentifiersRequireReview() {
        val localAccounts = listOf(
            FinancialAccountRecord(
                id = 1,
                name = "Cartão A",
                type = FinancialAccountType.CREDIT_CARD,
            ),
            FinancialAccountRecord(
                id = 2,
                name = "Cartão B",
                type = FinancialAccountType.CREDIT_CARD,
            ),
        )

        val result = PluggyReconciliationEngine.reconcile(
            PluggyReconciliationInput(
                pluggyAccounts = listOf(
                    PluggyAccountDataset(
                        account = remoteCreditAccount(),
                        transactions = emptyList(),
                        bills = emptyList(),
                    ),
                ),
                localAccounts = localAccounts,
                localTransactions = emptyList(),
                localInvoicesByAccount = emptyMap(),
                zoneId = ZoneOffset.UTC,
            ),
        )

        val account = result.accounts.single()
        assertEquals(PluggyReconciliationStatus.REVIEW, account.status)
        assertEquals(null, account.localAccountId)
        assertEquals(2, account.compatibleCandidateCount)
    }

    @Test
    fun sameAmountAndDateWithMultipleLocalCandidatesStaysInReview() {
        val localAccount = FinancialAccountRecord(
            id = 10,
            name = "Cartão principal",
            type = FinancialAccountType.CREDIT_CARD,
            closingDay = 14,
            dueDay = 21,
            cardIdentifiers = "1234",
        )
        val remote = remoteCardTransaction(
            amount = "90.00",
            date = "2026-06-26T10:56:52Z",
            cardLastFour = "1234",
            category = null,
        )
        val locals = listOf(
            localExpense(1, 10, "90.00", "2026-06-26T10:56:52", "Compra um"),
            localExpense(2, 10, "90.00", "2026-06-26T10:56:52", "Compra dois"),
        )

        val result = PluggyReconciliationEngine.reconcile(
            PluggyReconciliationInput(
                pluggyAccounts = listOf(
                    PluggyAccountDataset(remoteCreditAccount(), listOf(remote), emptyList()),
                ),
                localAccounts = listOf(localAccount),
                localTransactions = locals,
                localInvoicesByAccount = emptyMap(),
                zoneId = ZoneOffset.UTC,
            ),
        )

        assertEquals(1, result.accounts.single().transactionCounts.review)
    }

    private fun remoteCreditAccount(): PluggyAccountSnapshot = PluggyAccountSnapshot(
        externalId = "remote-card",
        type = PluggyAccountType.CREDIT,
        subtype = "CREDIT_CARD",
        name = "SANTANDER ELITE VISA",
        currencyCode = "BRL",
        balance = BigDecimal("1000.00"),
        creditData = PluggyCreditData(
            creditLimit = BigDecimal("10000.00"),
            availableCreditLimit = BigDecimal("9000.00"),
        ),
    )

    private fun remoteCardTransaction(
        amount: String,
        date: String,
        cardLastFour: String,
        category: String?,
    ): PluggyTransactionSnapshot = PluggyTransactionSnapshot(
        externalId = "remote-tx-$date-$amount",
        accountExternalId = "remote-card",
        amount = BigDecimal(amount),
        date = Instant.parse(date),
        purchaseDate = Instant.parse(date),
        direction = PluggyTransactionDirection.DEBIT,
        status = PluggyTransactionStatus.POSTED,
        description = "Loja exemplo",
        category = category,
        cardLastFour = cardLastFour,
    )

    private fun localExpense(
        id: Long,
        accountId: Long,
        amount: String,
        occurredAt: String,
        description: String,
    ): FinancialTransactionRecord = FinancialTransactionRecord(
        id = id,
        sourceEventId = null,
        direction = FinancialTransactionDirection.EXPENSE,
        type = FinancialTransactionType.CARD_PURCHASE,
        amount = amount,
        occurredAt = occurredAt,
        description = description,
        sourcePackage = "test",
        accountId = accountId,
    )
}
