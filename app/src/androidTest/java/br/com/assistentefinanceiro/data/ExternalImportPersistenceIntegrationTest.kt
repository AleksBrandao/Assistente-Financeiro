package br.com.assistentefinanceiro.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.assistentefinanceiro.notifications.CreditCardInvoiceStatus
import br.com.assistentefinanceiro.notifications.DiagnosticStore
import br.com.assistentefinanceiro.notifications.FinancialAccountType
import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.FinancialTransactionType
import br.com.assistentefinanceiro.notifications.TransactionCategory
import br.com.assistentefinanceiro.notifications.TransactionCategorySource
import br.com.assistentefinanceiro.notifications.TransactionOrigin
import br.com.assistentefinanceiro.notifications.TransactionStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalImportPersistenceIntegrationTest {
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
    fun confirmedLinkAndTransactionArePersistedAndUpdatedIdempotently() {
        val repository = DiagnosticFinancialRepository(context)
        val account = createCard(repository)
        val link = ExternalAccountLinkRecord(
            provider = ExternalDataProvider.PLUGGY,
            externalAccountId = "remote-card",
            localAccountId = account.id,
        )
        assertTrue(repository.saveExternalAccountLink(link))
        assertEquals(link, repository.externalAccountLinks(ExternalDataProvider.PLUGGY).single())

        val pending = ExternalTransactionImportDraft(
            provider = ExternalDataProvider.PLUGGY,
            externalTransactionId = "remote-tx-1",
            externalAccountId = "remote-card",
            localAccountId = account.id,
            direction = FinancialTransactionDirection.EXPENSE,
            type = FinancialTransactionType.CARD_PURCHASE,
            amount = BigDecimal("171.70"),
            occurredAt = LocalDateTime.parse("2026-08-14T03:00:00"),
            description = "Loja Pluggy",
            status = TransactionStatus.PENDING,
            category = TransactionCategory.OTHER_EXPENSE,
            customCategory = "Eating out",
            originalCategory = "Eating out",
            origin = TransactionOrigin.PLUGGY,
            purchaseAt = "2026-07-19T15:24:20Z",
            sourceCategoryId = "11010000",
            operationType = "CARTAO",
            paymentMethod = "OTHER",
            installmentNumber = 2,
            totalInstallments = 6,
            billForecastPeriod = YearMonth.of(2026, 8),
            externalBillId = "bill-remote",
            invoiceClosingDate = LocalDate.of(2026, 8, 14),
            invoiceDueDate = LocalDate.of(2026, 8, 21),
        )

        val first = repository.importExternalTransactions(listOf(pending))
        assertEquals(1, first.imported)
        assertEquals(0, first.alreadyImported)
        assertEquals(0, first.updated)

        val posted = pending.copy(status = TransactionStatus.REALIZED)
        val second = repository.importExternalTransactions(listOf(posted))
        assertEquals(0, second.imported)
        assertEquals(0, second.alreadyImported)
        assertEquals(1, second.updated)

        val transaction = repository.recentTransactions(10).single()
        assertEquals(TransactionOrigin.PLUGGY, transaction.origin)
        assertEquals(TransactionStatus.REALIZED, transaction.status)
        assertEquals(account.id, transaction.accountId)
        assertEquals(TransactionCategory.OTHER_EXPENSE, transaction.category)
        assertEquals("Eating out", transaction.customCategory)
        assertEquals(TransactionCategorySource.EXTERNAL, transaction.categorySource)
        assertEquals("Eating out", transaction.originalCategory)
        assertEquals(2, transaction.seriesIndex)
        assertEquals(6, transaction.seriesTotal)
        assertNotNull(transaction.invoiceId)

        val store = DiagnosticStore(context)
        val metadata = store.readableDatabase.rawQuery(
            """SELECT purchase_at,source_category_id,operation_type,payment_method,
                      bill_forecast_period,external_bill_id
               FROM external_transaction_metadata
               WHERE provider = ? AND external_transaction_id = ?""",
            arrayOf(ExternalDataProvider.PLUGGY.name, "remote-tx-1"),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            List(6) { index -> cursor.getString(index) }
        }
        assertEquals("2026-07-19T15:24:20Z", metadata[0])
        assertEquals("11010000", metadata[1])
        assertEquals("CARTAO", metadata[2])
        assertEquals("OTHER", metadata[3])
        assertEquals("2026-08", metadata[4])
        assertEquals("bill-remote", metadata[5])
    }

    @Test
    fun forecastPeriodRepresentsDueMonthWhenDueDayPrecedesClosingDay() {
        val repository = DiagnosticFinancialRepository(context)
        val account = createCard(repository, closingDay = 25, dueDay = 5)
        val pending = ExternalTransactionImportDraft(
            provider = ExternalDataProvider.PLUGGY,
            externalTransactionId = "forecast-tx",
            externalAccountId = "remote-card",
            localAccountId = account.id,
            direction = FinancialTransactionDirection.EXPENSE,
            type = FinancialTransactionType.CARD_PURCHASE,
            amount = BigDecimal("80.00"),
            occurredAt = LocalDateTime.parse("2026-08-26T12:00:00"),
            description = "Compra pendente",
            status = TransactionStatus.PENDING,
            category = TransactionCategory.OTHER_EXPENSE,
            customCategory = "Shopping",
            originalCategory = "Shopping",
            origin = TransactionOrigin.PLUGGY,
            billForecastPeriod = YearMonth.of(2026, 9),
        )

        repository.importExternalTransactions(listOf(pending))

        val invoice = repository.creditCardInvoices(account.id).single()
        val transaction = repository.recentTransactions(10).single()
        assertEquals(YearMonth.of(2026, 8), invoice.closingPeriod)
        assertEquals(LocalDate.of(2026, 8, 25), invoice.closingDate)
        assertEquals(LocalDate.of(2026, 9, 5), invoice.dueDate)
        assertEquals(invoice.id, transaction.invoiceId)
    }

    @Test
    fun officialBillPaymentFromFollowingCycleSettlesPreviousInvoice() {
        val repository = DiagnosticFinancialRepository(context)
        val account = createCard(repository)
        val july = ExternalBillImportDraft(
            provider = ExternalDataProvider.PLUGGY,
            externalBillId = "bill-july",
            externalAccountId = "remote-card",
            localAccountId = account.id,
            closingDate = LocalDate.of(2026, 7, 14),
            dueDate = LocalDate.of(2026, 7, 21),
            totalAmount = BigDecimal("100.00"),
        )
        val august = ExternalBillImportDraft(
            provider = ExternalDataProvider.PLUGGY,
            externalBillId = "bill-august",
            externalAccountId = "remote-card",
            localAccountId = account.id,
            closingDate = LocalDate.of(2026, 8, 14),
            dueDate = LocalDate.of(2026, 8, 21),
            totalAmount = BigDecimal("50.00"),
            payments = listOf(
                ExternalBillPaymentDraft(
                    externalPaymentId = "payment-july",
                    amount = BigDecimal("100.00"),
                    paidAt = LocalDate.of(2026, 7, 21),
                ),
            ),
        )

        val result = repository.importExternalBills(listOf(july, august))
        assertEquals(2, result.billsSynced)
        assertEquals(1, result.paymentsSynced)

        val invoices = repository.creditCardInvoices(account.id)
        val julyInvoice = invoices.single { it.closingPeriod == YearMonth.of(2026, 7) }
        val augustInvoice = invoices.single { it.closingPeriod == YearMonth.of(2026, 8) }
        assertEquals(0, julyInvoice.total.compareTo(BigDecimal("100.00")))
        assertEquals(0, julyInvoice.paidAmount.compareTo(BigDecimal("100.00")))
        assertEquals(CreditCardInvoiceStatus.PAID, julyInvoice.status)
        assertEquals(0, augustInvoice.total.compareTo(BigDecimal("50.00")))

        val second = repository.importExternalBills(listOf(july, august))
        assertEquals(1, repository.invoicePayments(julyInvoice).size)
        assertEquals(2, second.billsSynced)
    }

    @Test
    fun officialBillRelinksExistingTransactionBeforeRecalculatingAdjustment() {
        val repository = DiagnosticFinancialRepository(context)
        val account = createCard(repository)
        repository.importExternalTransactions(
            listOf(
                ExternalTransactionImportDraft(
                    provider = ExternalDataProvider.PLUGGY,
                    externalTransactionId = "tx-july",
                    externalAccountId = "remote-card",
                    localAccountId = account.id,
                    direction = FinancialTransactionDirection.EXPENSE,
                    type = FinancialTransactionType.CARD_PURCHASE,
                    amount = BigDecimal("80.00"),
                    occurredAt = LocalDateTime.parse("2026-07-10T12:00:00"),
                    description = "Compra julho",
                    status = TransactionStatus.REALIZED,
                    origin = TransactionOrigin.PLUGGY,
                    billForecastPeriod = YearMonth.of(2026, 8),
                    externalBillId = "bill-july",
                ),
            ),
        )
        assertEquals(
            YearMonth.of(2026, 8),
            repository.creditCardInvoices(account.id).single().closingPeriod,
        )

        repository.importExternalBills(
            listOf(
                ExternalBillImportDraft(
                    provider = ExternalDataProvider.PLUGGY,
                    externalBillId = "bill-july",
                    externalAccountId = "remote-card",
                    localAccountId = account.id,
                    closingDate = LocalDate.of(2026, 7, 14),
                    dueDate = LocalDate.of(2026, 7, 21),
                    totalAmount = BigDecimal("100.00"),
                ),
            ),
        )

        val july = repository.creditCardInvoices(account.id)
            .single { it.closingPeriod == YearMonth.of(2026, 7) }
        val transaction = repository.recentTransactions(10).single()
        assertEquals(july.id, transaction.invoiceId)
        assertEquals(1, july.transactionCount)
        assertEquals(0, july.baseTotal.compareTo(BigDecimal("80.00")))
        assertEquals(0, july.adjustmentAmount.compareTo(BigDecimal("20.00")))
        assertEquals(0, july.total.compareTo(BigDecimal("100.00")))
    }

    @Test
    fun transactionAfterOfficialClosingIsMovedToForecastCycle() {
        val repository = DiagnosticFinancialRepository(context)
        val account = createCard(repository)
        repository.importExternalTransactions(
            listOf(
                ExternalTransactionImportDraft(
                    provider = ExternalDataProvider.PLUGGY,
                    externalTransactionId = "late-tx",
                    externalAccountId = "remote-card",
                    localAccountId = account.id,
                    direction = FinancialTransactionDirection.EXPENSE,
                    type = FinancialTransactionType.CARD_PURCHASE,
                    amount = BigDecimal("80.00"),
                    occurredAt = LocalDateTime.parse("2026-08-16T12:00:00"),
                    description = "Compra após fechamento",
                    status = TransactionStatus.PENDING,
                    origin = TransactionOrigin.PLUGGY,
                    billForecastPeriod = YearMonth.of(2026, 9),
                    externalBillId = "bill-august",
                    invoiceClosingDate = LocalDate.of(2026, 8, 14),
                    invoiceDueDate = LocalDate.of(2026, 8, 21),
                ),
            ),
        )

        repository.importExternalBills(
            listOf(
                ExternalBillImportDraft(
                    provider = ExternalDataProvider.PLUGGY,
                    externalBillId = "bill-august",
                    externalAccountId = "remote-card",
                    localAccountId = account.id,
                    closingDate = LocalDate.of(2026, 8, 14),
                    dueDate = LocalDate.of(2026, 8, 21),
                    totalAmount = BigDecimal("50.00"),
                ),
            ),
        )

        val invoices = repository.creditCardInvoices(account.id)
        val august = invoices.single { it.closingPeriod == YearMonth.of(2026, 8) }
        val september = invoices.single { it.closingPeriod == YearMonth.of(2026, 9) }
        val transaction = repository.recentTransactions(10).single()
        assertEquals(0, august.transactionCount)
        assertEquals(0, august.total.compareTo(BigDecimal("50.00")))
        assertEquals(september.id, transaction.invoiceId)
        assertEquals(1, september.transactionCount)
        assertEquals(0, september.total.compareTo(BigDecimal("80.00")))
    }

    @Test
    fun externalPaymentDoesNotSettleUnbackedProvisionalInvoice() {
        val repository = DiagnosticFinancialRepository(context)
        val account = createCard(repository)
        repository.importExternalTransactions(
            listOf(
                ExternalTransactionImportDraft(
                    provider = ExternalDataProvider.PLUGGY,
                    externalTransactionId = "provisional-july",
                    externalAccountId = "remote-card",
                    localAccountId = account.id,
                    direction = FinancialTransactionDirection.EXPENSE,
                    type = FinancialTransactionType.CARD_PURCHASE,
                    amount = BigDecimal("100.00"),
                    occurredAt = LocalDateTime.parse("2026-07-10T12:00:00"),
                    description = "Compra julho",
                    status = TransactionStatus.REALIZED,
                    origin = TransactionOrigin.PLUGGY,
                ),
            ),
        )
        val july = repository.creditCardInvoices(account.id).single()

        repository.importExternalBills(
            listOf(
                ExternalBillImportDraft(
                    provider = ExternalDataProvider.PLUGGY,
                    externalBillId = "bill-august",
                    externalAccountId = "remote-card",
                    localAccountId = account.id,
                    closingDate = LocalDate.of(2026, 8, 14),
                    dueDate = LocalDate.of(2026, 8, 21),
                    totalAmount = BigDecimal("50.00"),
                    payments = listOf(
                        ExternalBillPaymentDraft(
                            externalPaymentId = "payment-unknown-previous",
                            amount = BigDecimal("100.00"),
                            paidAt = LocalDate.of(2026, 7, 21),
                        ),
                    ),
                ),
            ),
        )

        val refreshedJuly = repository.creditCardInvoices(account.id)
            .single { it.id == july.id }
        assertEquals(0, refreshedJuly.paidAmount.compareTo(BigDecimal.ZERO))
        assertTrue(repository.invoicePayments(refreshedJuly).isEmpty())
    }

    private fun createCard(
        repository: DiagnosticFinancialRepository,
        closingDay: Int = 14,
        dueDay: Int = 21,
    ) = run {
        assertTrue(
            repository.saveFinancialAccount(
                id = null,
                name = "Cartão Pluggy teste",
                type = FinancialAccountType.CREDIT_CARD,
                closingDay = closingDay,
                dueDay = dueDay,
                isDefault = false,
                cardIdentifiers = "1234",
                openingBalance = BigDecimal.ZERO,
                openingBalanceDate = null,
            ),
        )
        repository.financialAccounts().single()
    }

    private companion object {
        const val DATABASE_NAME = "notification_diagnostics.db"
    }
}
