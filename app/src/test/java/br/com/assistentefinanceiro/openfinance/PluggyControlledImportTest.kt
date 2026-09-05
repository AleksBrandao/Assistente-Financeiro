package br.com.assistentefinanceiro.openfinance

import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.FinancialTransactionRecord
import br.com.assistentefinanceiro.notifications.FinancialTransactionType
import br.com.assistentefinanceiro.notifications.TransactionCategory
import br.com.assistentefinanceiro.notifications.TransactionStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluggyControlledImportTest {
    @Test
    fun planImportsPostedAndPendingAndPreservesPluggyCategory() {
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
            category = "Eating out",
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
            category = "Transfer",
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
        val reconciliation = reconciliation(
            accountId = 7,
            accountName = "Conta local",
            remoteAccountId = "bank-remote",
            remoteAccountName = "Banco",
            remoteType = PluggyAccountType.BANK,
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

        assertEquals(2, plan.importable)
        val postedDraft = plan.drafts.single { it.externalTransactionId == "new" }
        val pendingDraft = plan.drafts.single { it.externalTransactionId == "pending" }
        assertEquals(TransactionStatus.REALIZED, postedDraft.status)
        assertEquals(TransactionCategory.OTHER_EXPENSE, postedDraft.category)
        assertEquals("Eating out", postedDraft.customCategory)
        assertEquals(TransactionStatus.PENDING, pendingDraft.status)
        assertEquals("Transfer", pendingDraft.customCategory)
        assertEquals(1, plan.matchedExisting)
        assertEquals(1, plan.skippedOutsideWindow)
    }

    @Test
    fun allAvailablePeriodIncludesOlderTransaction() {
        val account = PluggyAccountSnapshot(
            externalId = "bank-remote",
            type = PluggyAccountType.BANK,
            subtype = "CHECKING_ACCOUNT",
            name = "Banco",
            currencyCode = "BRL",
            balance = BigDecimal.ZERO,
        )
        val old = remoteBankTransaction(
            id = "old",
            amount = "50.00",
            date = "2026-01-01T12:00:00Z",
            status = PluggyTransactionStatus.POSTED,
            direction = PluggyTransactionDirection.DEBIT,
        )

        val plan = PluggyControlledImportPlanner.plan(
            datasets = listOf(PluggyAccountDataset(account, listOf(old), emptyList())),
            reconciliation = reconciliation(
                7, "Conta local", "bank-remote", "Banco", PluggyAccountType.BANK,
            ),
            selectedExternalAccountIds = setOf("bank-remote"),
            localTransactions = emptyList(),
            today = LocalDate.of(2026, 9, 4),
            lookbackDays = null,
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(1, plan.importable)
        assertEquals("old", plan.drafts.single().externalTransactionId)
        assertEquals(null, plan.windowStartDate)
    }

    @Test
    fun cardCreditWithoutBillPaymentIsImportedAsIncome() {
        val account = cardAccount()
        val refund = PluggyTransactionSnapshot(
            externalId = "credit-movement",
            accountExternalId = "card-remote",
            amount = BigDecimal("100.00"),
            date = Instant.parse("2026-09-03T12:00:00Z"),
            direction = PluggyTransactionDirection.CREDIT,
            status = PluggyTransactionStatus.POSTED,
            description = "Estorno no cartão",
            category = "Refund",
        )

        val plan = PluggyControlledImportPlanner.plan(
            datasets = listOf(PluggyAccountDataset(account, listOf(refund), emptyList())),
            reconciliation = reconciliation(
                9, "Cartão local", "card-remote", "Cartão", PluggyAccountType.CREDIT,
            ),
            selectedExternalAccountIds = setOf("card-remote"),
            localTransactions = emptyList(),
            today = LocalDate.of(2026, 9, 4),
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(1, plan.importable)
        assertEquals(FinancialTransactionDirection.INCOME, plan.drafts.single().direction)
        assertEquals(FinancialTransactionType.IMPORTED_INCOME, plan.drafts.single().type)
        assertEquals("Refund", plan.drafts.single().customCategory)
        assertEquals(0, plan.skippedCreditCardPayments)
    }

    @Test
    fun cardCreditMatchingOfficialBillPaymentIsNotImportedAsTransaction() {
        val account = cardAccount()
        val paymentTransaction = PluggyTransactionSnapshot(
            externalId = "credit-payment",
            accountExternalId = "card-remote",
            amount = BigDecimal("100.00"),
            date = Instant.parse("2026-08-21T12:00:00Z"),
            direction = PluggyTransactionDirection.CREDIT,
            status = PluggyTransactionStatus.POSTED,
            description = "Pagamento de fatura",
        )
        val bill = PluggyBillSnapshot(
            externalId = "bill-august",
            accountExternalId = "card-remote",
            dueDate = LocalDate.of(2026, 8, 21),
            closingDate = LocalDate.of(2026, 8, 14),
            totalAmount = BigDecimal("50.00"),
            minimumPaymentAmount = null,
            allowsInstallments = false,
            payments = listOf(
                PluggyBillPaymentSnapshot(
                    externalId = "payment-july",
                    amount = BigDecimal("100.00"),
                    paymentDate = LocalDate.of(2026, 8, 21),
                ),
            ),
        )

        val plan = PluggyControlledImportPlanner.plan(
            datasets = listOf(PluggyAccountDataset(account, listOf(paymentTransaction), listOf(bill))),
            reconciliation = reconciliation(
                9, "Cartão local", "card-remote", "Cartão", PluggyAccountType.CREDIT,
            ),
            selectedExternalAccountIds = setOf("card-remote"),
            localTransactions = emptyList(),
            today = LocalDate.of(2026, 9, 4),
            lookbackDays = 90,
            zoneId = ZoneOffset.UTC,
        )

        assertTrue(plan.drafts.isEmpty())
        assertEquals(1, plan.skippedCreditCardPayments)
        assertEquals(1, plan.billDrafts.size)
        assertEquals(1, plan.billDrafts.single().payments.size)
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

    private fun cardAccount() = PluggyAccountSnapshot(
        externalId = "card-remote",
        type = PluggyAccountType.CREDIT,
        subtype = "CREDIT_CARD",
        name = "Cartão",
        currencyCode = "BRL",
        balance = BigDecimal.ZERO,
    )

    private fun reconciliation(
        accountId: Long,
        accountName: String,
        remoteAccountId: String,
        remoteAccountName: String,
        remoteType: PluggyAccountType,
    ) = PluggyReconciliationPreview(
        accounts = listOf(
            PluggyAccountReconciliation(
                pluggyAccountExternalId = remoteAccountId,
                pluggyAccountName = remoteAccountName,
                pluggyAccountType = remoteType,
                status = PluggyReconciliationStatus.CONFIRMED,
                localAccountId = accountId,
                localAccountName = accountName,
                compatibleCandidateCount = 1,
                reasons = listOf("confirmado"),
                transactionCounts = PluggyReconciliationCounts(0, 0, 0),
                billCounts = PluggyReconciliationCounts(0, 0, 0),
                pluggyCategoryCount = 0,
            ),
        ),
        distinctPluggyCategories = 0,
        directCategoryMatches = 0,
    )

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
