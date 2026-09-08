package br.com.assistentefinanceiro.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.assistentefinanceiro.notifications.CreditCardInvoiceStatus
import br.com.assistentefinanceiro.notifications.FinancialAccountType
import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.FinancialTransactionType
import br.com.assistentefinanceiro.notifications.TransactionCategory
import br.com.assistentefinanceiro.notifications.TransactionOrigin
import br.com.assistentefinanceiro.notifications.TransactionStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BankInvoicePaymentReconciliationIntegrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun bankDebitWithinCentsMarksInvoicePaidWithoutDuplicatingExpense() {
        val repository = DiagnosticFinancialRepository(context)
        assertTrue(
            repository.saveFinancialAccount(
                id = null,
                name = "Banco Santander",
                type = FinancialAccountType.BANK_ACCOUNT,
                closingDay = null,
                dueDay = null,
                isDefault = false,
                cardIdentifiers = null,
                openingBalance = BigDecimal("2000.00"),
                openingBalanceDate = LocalDate.of(2026, 8, 1),
            ),
        )
        assertTrue(
            repository.saveFinancialAccount(
                id = null,
                name = "SANTANDER ELITE VISA",
                type = FinancialAccountType.CREDIT_CARD,
                closingDay = 14,
                dueDay = 21,
                isDefault = false,
                cardIdentifiers = "7107",
                openingBalance = BigDecimal.ZERO,
                openingBalanceDate = null,
            ),
        )
        val bank = repository.financialAccounts().single { it.name == "Banco Santander" }
        val card = repository.financialAccounts().single { it.name == "SANTANDER ELITE VISA" }

        repository.importExternalTransactions(
            listOf(
                ExternalTransactionImportDraft(
                    provider = ExternalDataProvider.PLUGGY,
                    externalTransactionId = "bank-payment-7107",
                    externalAccountId = "remote-bank",
                    localAccountId = bank.id,
                    direction = FinancialTransactionDirection.EXPENSE,
                    type = FinancialTransactionType.IMPORTED_EXPENSE,
                    amount = BigDecimal("1650.14"),
                    occurredAt = LocalDateTime.parse("2026-08-21T08:05:48"),
                    description = "DEBITO AUT. FATURA CARTAO VISA FINAL 7107",
                    status = TransactionStatus.REALIZED,
                    category = TransactionCategory.OTHER_EXPENSE,
                    customCategory = "Credit card payment",
                    originalCategory = "Credit card payment",
                    origin = TransactionOrigin.PLUGGY,
                ),
            ),
        )
        repository.importExternalBills(
            listOf(
                ExternalBillImportDraft(
                    provider = ExternalDataProvider.PLUGGY,
                    externalBillId = "bill-august-7107",
                    externalAccountId = "remote-card",
                    localAccountId = card.id,
                    closingDate = LocalDate.of(2026, 8, 14),
                    dueDate = LocalDate.of(2026, 8, 21),
                    totalAmount = BigDecimal("1650.18"),
                ),
            ),
        )

        val invoice = repository.creditCardInvoices(card.id)
            .single { it.closingPeriod == YearMonth.of(2026, 8) }
        assertEquals(CreditCardInvoiceStatus.PAID, invoice.status)
        assertEquals(0, invoice.paidAmount.compareTo(BigDecimal("1650.18")))
        assertEquals(0, invoice.outstandingAmount.compareTo(BigDecimal.ZERO))

        val payment = repository.invoicePayments(invoice).single()
        assertEquals(bank.id, payment.sourceAccountId)
        assertEquals(0, payment.amount.compareTo(BigDecimal("1650.18")))
        assertEquals(LocalDate.of(2026, 8, 21), payment.paidAt)

        // The bank debit remains in account cash flow, but is excluded from expense analytics.
        assertTrue(repository.granularTransactions().isEmpty())
        val statement = repository.statementEntries().single()
        assertEquals("Fatura SANTANDER ELITE VISA", statement.transaction.description)
        assertEquals(0, statement.realizedAmount!!.compareTo(BigDecimal("1650.18")))
        assertEquals(0, statement.pendingAmount!!.compareTo(BigDecimal.ZERO))

        // Opening balance 2,000.00 less the real bank debit 1,650.14. The invoice payment itself
        // must not be subtracted a second time from the projected balance.
        val projected = repository.generalProjectedBalance(LocalDate.of(2026, 9, 30))
        assertEquals(0, projected.compareTo(BigDecimal("349.86")))
    }

    private companion object {
        const val DATABASE_NAME = "notification_diagnostics.db"
    }
}
