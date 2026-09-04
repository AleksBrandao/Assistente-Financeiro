package br.com.assistentefinanceiro.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.assistentefinanceiro.notifications.DiagnosticStore
import br.com.assistentefinanceiro.notifications.FinancialAccountType
import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.FinancialTransactionType
import br.com.assistentefinanceiro.notifications.TransactionCategory
import br.com.assistentefinanceiro.notifications.TransactionCategorySource
import br.com.assistentefinanceiro.notifications.TransactionOrigin
import br.com.assistentefinanceiro.notifications.TransactionStatus
import java.math.BigDecimal
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
    fun confirmedLinkAndTransactionArePersistedIdempotently() {
        val repository = DiagnosticFinancialRepository(context)
        assertTrue(
            repository.saveFinancialAccount(
                id = null,
                name = "Cartão Pluggy teste",
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
        val link = ExternalAccountLinkRecord(
            provider = ExternalDataProvider.PLUGGY,
            externalAccountId = "remote-card",
            localAccountId = account.id,
        )
        assertTrue(repository.saveExternalAccountLink(link))
        assertEquals(link, repository.externalAccountLinks(ExternalDataProvider.PLUGGY).single())

        val draft = ExternalTransactionImportDraft(
            provider = ExternalDataProvider.PLUGGY,
            externalTransactionId = "remote-tx-1",
            externalAccountId = "remote-card",
            localAccountId = account.id,
            direction = FinancialTransactionDirection.EXPENSE,
            type = FinancialTransactionType.CARD_PURCHASE,
            amount = BigDecimal("171.70"),
            occurredAt = LocalDateTime.parse("2026-08-14T03:00:00"),
            description = "Loja Pluggy",
            status = TransactionStatus.REALIZED,
            category = TransactionCategory.FOOD,
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
        )

        val first = repository.importExternalTransactions(listOf(draft))
        assertEquals(1, first.imported)
        assertEquals(0, first.alreadyImported)

        val second = repository.importExternalTransactions(listOf(draft))
        assertEquals(0, second.imported)
        assertEquals(1, second.alreadyImported)

        val transaction = repository.recentTransactions(10).single()
        assertEquals(TransactionOrigin.PLUGGY, transaction.origin)
        assertEquals(account.id, transaction.accountId)
        assertEquals(TransactionCategory.FOOD, transaction.category)
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

    private companion object {
        const val DATABASE_NAME = "notification_diagnostics.db"
    }
}
