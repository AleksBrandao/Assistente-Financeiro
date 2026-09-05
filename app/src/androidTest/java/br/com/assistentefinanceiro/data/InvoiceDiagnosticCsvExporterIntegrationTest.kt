package br.com.assistentefinanceiro.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.assistentefinanceiro.notifications.FinancialAccountType
import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.FinancialTransactionType
import br.com.assistentefinanceiro.notifications.TransactionOrigin
import br.com.assistentefinanceiro.notifications.TransactionStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InvoiceDiagnosticCsvExporterIntegrationTest {
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
    fun exportIncludesOfficialBillTransactionsAndPaymentSource() {
        val repository = DiagnosticFinancialRepository(context)
        assertTrue(
            repository.saveFinancialAccount(
                id = null,
                name = "Cartão diagnóstico",
                type = FinancialAccountType.CREDIT_CARD,
                closingDay = 14,
                dueDay = 21,
                isDefault = false,
                cardIdentifiers = "1234",
                openingBalance = BigDecimal.ZERO,
                openingBalanceDate = null,
            ),
        )
        val account = repository.financialAccounts().single()

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
                    externalBillId = "bill-july",
                    invoiceClosingDate = LocalDate.of(2026, 7, 14),
                    invoiceDueDate = LocalDate.of(2026, 7, 21),
                ),
            ),
        )
        repository.importExternalBills(
            listOf(
                ExternalBillImportDraft(
                    provider = ExternalDataProvider.PLUGGY,
                    externalBillId = "bill-july",
                    externalAccountId = "remote-card",
                    localAccountId = account.id,
                    dueDate = LocalDate.of(2026, 7, 21),
                    closingDate = LocalDate.of(2026, 7, 14),
                    totalAmount = BigDecimal("100.00"),
                ),
                ExternalBillImportDraft(
                    provider = ExternalDataProvider.PLUGGY,
                    externalBillId = "bill-august",
                    externalAccountId = "remote-card",
                    localAccountId = account.id,
                    dueDate = LocalDate.of(2026, 8, 21),
                    closingDate = LocalDate.of(2026, 8, 14),
                    totalAmount = BigDecimal("50.00"),
                    payments = listOf(
                        ExternalBillPaymentDraft(
                            externalPaymentId = "payment-july",
                            amount = BigDecimal("100.00"),
                            paidAt = LocalDate.of(2026, 7, 21),
                        ),
                    ),
                ),
            ),
        )

        val csv = repository.exportInvoiceDiagnosticsCsv()
        val julyLine = csv.lineSequence().first { line ->
            line.contains("\"FATURA\"") &&
                line.contains("\"2026-07\"") &&
                line.contains("bill-july")
        }

        assertTrue(julyLine.contains("bill-august"))
        assertTrue(julyLine.contains("payment-july"))
        assertTrue(julyLine.contains("tx-july").not())
        assertTrue(csv.contains("External Bill IDs das compras"))
    }

    @Test
    fun exportFlagsCardTransactionWithExternalBillButNoLocalInvoice() {
        val repository = DiagnosticFinancialRepository(context)
        assertTrue(
            repository.saveFinancialAccount(
                id = null,
                name = "Cartão sem ciclo",
                type = FinancialAccountType.CREDIT_CARD,
                closingDay = null,
                dueDay = null,
                isDefault = false,
                cardIdentifiers = "9876",
                openingBalance = BigDecimal.ZERO,
                openingBalanceDate = null,
            ),
        )
        val account = repository.financialAccounts().single()

        repository.importExternalTransactions(
            listOf(
                ExternalTransactionImportDraft(
                    provider = ExternalDataProvider.PLUGGY,
                    externalTransactionId = "orphan-tx",
                    externalAccountId = "remote-card-orphan",
                    localAccountId = account.id,
                    direction = FinancialTransactionDirection.EXPENSE,
                    type = FinancialTransactionType.CARD_PURCHASE,
                    amount = BigDecimal("42.50"),
                    occurredAt = LocalDateTime.parse("2026-08-10T12:00:00"),
                    description = "Compra sem ciclo",
                    status = TransactionStatus.REALIZED,
                    origin = TransactionOrigin.PLUGGY,
                    externalBillId = "orphan-bill",
                ),
            ),
        )

        val csv = repository.exportInvoiceDiagnosticsCsv()

        assertTrue(csv.contains("TRANSACAO_DIVERGENTE"))
        assertTrue(csv.contains("TRANSACAO_COM_BILL_SEM_FATURA_LOCAL"))
        assertTrue(csv.contains("orphan-tx"))
        assertTrue(csv.contains("orphan-bill"))
    }

    private companion object {
        const val DATABASE_NAME = "notification_diagnostics.db"
    }
}
