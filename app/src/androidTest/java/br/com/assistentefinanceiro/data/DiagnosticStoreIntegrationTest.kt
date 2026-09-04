package br.com.assistentefinanceiro.data

import android.content.ContentValues
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
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticStoreIntegrationTest {
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
    fun createsLatestSchemaFromScratch() {
        val store = DiagnosticStore(context)
        try {
            val db = store.writableDatabase
            assertEquals(LATEST_DATABASE_VERSION, db.version)

            val tables = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table'",
                null,
            ).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertTrue(
                tables.containsAll(
                    setOf(
                        "candidates",
                        "events",
                        "transactions",
                        "category_rules",
                        "financial_accounts",
                        "credit_card_invoices",
                        "invoice_payments",
                        "invoice_adjustments",
                        "account_movements",
                        "deleted_transaction_groups",
                        "deleted_transactions",
                        "monthly_budgets",
                    ),
                ),
            )

            val transactionColumns = tableColumns(db = db, table = "transactions")
            assertTrue("custom_category" in transactionColumns)
            assertTrue("subcategory" in transactionColumns)
            assertTrue("import_key" in transactionColumns)
        } finally {
            store.close()
        }
    }

    @Test
    fun migratesVersionOneToLatestWithoutLosingExistingData() {
        createVersionOneDatabase()

        val store = DiagnosticStore(context)
        try {
            val db = store.writableDatabase
            assertEquals(LATEST_DATABASE_VERSION, db.version)

            val candidate = db.rawQuery(
                "SELECT app_label,last_seen_at FROM candidates WHERE package_name = ?",
                arrayOf("com.santander.app"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0) to cursor.getLong(1)
            }
            assertEquals("Santander", candidate.first)
            assertEquals(1_700_000_000_000L, candidate.second)

            val eventCount = db.rawQuery("SELECT COUNT(*) FROM events", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
            assertEquals(1, eventCount)

            val migratedTransaction = db.rawQuery(
                "SELECT type,amount,description FROM transactions WHERE source_event_id = 1",
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))
            }
            assertEquals(FinancialTransactionType.CARD_PURCHASE.name, migratedTransaction.first)
            assertEquals("123.45", migratedTransaction.second)
            assertEquals("PADARIA TESTE", migratedTransaction.third)

            val transactionColumns = tableColumns(db = db, table = "transactions")
            assertTrue("custom_category" in transactionColumns)
            assertTrue("subcategory" in transactionColumns)
        } finally {
            store.close()
        }
    }

    @Test
    fun projectedBalanceAndConsolidatedTransactionsUseRealDatabaseState() {
        val repository = DiagnosticFinancialRepository(context)
        val store = DiagnosticStore(context)
        try {
            assertTrue(
                repository.saveFinancialAccount(
                    id = null,
                    name = "Conta integração",
                    type = FinancialAccountType.BANK_ACCOUNT,
                    closingDay = null,
                    dueDay = null,
                    isDefault = false,
                    cardIdentifiers = null,
                    openingBalance = BigDecimal("1000.00"),
                    openingBalanceDate = LocalDate.of(2029, 12, 31),
                ),
            )
            assertTrue(
                repository.saveFinancialAccount(
                    id = null,
                    name = "Cartão integração",
                    type = FinancialAccountType.CREDIT_CARD,
                    closingDay = 10,
                    dueDay = 15,
                    isDefault = false,
                    cardIdentifiers = "4242",
                    openingBalance = BigDecimal.ZERO,
                    openingBalanceDate = null,
                ),
            )

            val accounts = repository.financialAccounts()
            val bankAccount = accounts.single { it.name == "Conta integração" }
            val cardAccount = accounts.single { it.name == "Cartão integração" }
            val db = store.writableDatabase

            insertTransaction(
                db = db,
                direction = FinancialTransactionDirection.EXPENSE,
                type = FinancialTransactionType.MANUAL_EXPENSE,
                amount = "100.00",
                occurredAt = "2030-01-01T00:00:00",
                description = "Despesa realizada",
                accountId = bankAccount.id,
                accountName = bankAccount.name,
                status = TransactionStatus.REALIZED,
                dueDate = "2030-01-01",
                paidAt = "2030-01-01",
            )
            insertTransaction(
                db = db,
                direction = FinancialTransactionDirection.EXPENSE,
                type = FinancialTransactionType.MANUAL_EXPENSE,
                amount = "50.00",
                occurredAt = "2030-01-02T00:00:00",
                description = "Despesa pendente",
                accountId = bankAccount.id,
                accountName = bankAccount.name,
                status = TransactionStatus.PENDING,
                dueDate = "2030-01-02",
            )

            val invoiceId = db.insertOrThrow(
                "credit_card_invoices",
                null,
                ContentValues().apply {
                    put("account_id", cardAccount.id)
                    put("closing_period", "2030-01")
                    put("closing_date", "2030-01-10")
                    put("due_date", "2030-01-15")
                    put("status", "OPEN")
                },
            )
            insertTransaction(
                db = db,
                direction = FinancialTransactionDirection.EXPENSE,
                type = FinancialTransactionType.CARD_PURCHASE,
                amount = "200.00",
                occurredAt = "2030-01-03T12:00:00",
                description = "Compra integração",
                accountId = cardAccount.id,
                accountName = cardAccount.name,
                status = TransactionStatus.REALIZED,
                invoiceId = invoiceId,
                paidAt = "2030-01-03",
            )

            val projected = repository.generalProjectedBalance(LocalDate.of(2030, 1, 15))
            assertEquals(0, projected.compareTo(BigDecimal("650.00")))

            val consolidated = repository.consolidatedTransactions()
            assertFalse(consolidated.any { it.type == FinancialTransactionType.CARD_PURCHASE })
            val invoiceLine = consolidated.single { it.sourcePackage == "credit-card-invoice" }
            assertEquals("Fatura ${cardAccount.name}", invoiceLine.description)
            assertEquals(0, invoiceLine.amount.toBigDecimal().compareTo(BigDecimal("200.00")))
            assertEquals(TransactionStatus.PENDING, invoiceLine.status)
        } finally {
            store.close()
        }
    }

    private fun createVersionOneDatabase() {
        val db = context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null)
        try {
            db.execSQL(
                """CREATE TABLE candidates(
                    package_name TEXT PRIMARY KEY,
                    app_label TEXT NOT NULL,
                    last_seen_at INTEGER NOT NULL
                )""",
            )
            db.execSQL(
                """CREATE TABLE events(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    package_name TEXT NOT NULL,
                    app_label TEXT NOT NULL,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    posted_at INTEGER NOT NULL,
                    parsed INTEGER NOT NULL,
                    card_last_four TEXT,
                    amount TEXT,
                    merchant TEXT
                )""",
            )
            db.insertOrThrow(
                "candidates",
                null,
                ContentValues().apply {
                    put("package_name", "com.santander.app")
                    put("app_label", "Santander")
                    put("last_seen_at", 1_700_000_000_000L)
                },
            )
            db.insertOrThrow(
                "events",
                null,
                ContentValues().apply {
                    put("id", 1)
                    put("package_name", "com.santander.app")
                    put("app_label", "Santander")
                    put("title", "Compra aprovada!")
                    put(
                        "body",
                        "Compra no cartão final 4242, de R$ 123,45, em 01/01/24, às 12:30, em PADARIA TESTE, aprovada.",
                    )
                    put("posted_at", 1_704_110_600_000L)
                    put("parsed", 1)
                    put("card_last_four", "4242")
                    put("amount", "123.45")
                    put("merchant", "PADARIA TESTE")
                },
            )
            db.version = 1
        } finally {
            db.close()
        }
    }

    private fun tableColumns(db: android.database.sqlite.SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }

    private fun insertTransaction(
        db: android.database.sqlite.SQLiteDatabase,
        direction: FinancialTransactionDirection,
        type: FinancialTransactionType,
        amount: String,
        occurredAt: String,
        description: String,
        accountId: Long,
        accountName: String,
        status: TransactionStatus,
        dueDate: String? = null,
        paidAt: String? = null,
        invoiceId: Long? = null,
    ) {
        db.insertOrThrow(
            "transactions",
            null,
            ContentValues().apply {
                put("direction", direction.name)
                put("type", type.name)
                put("amount", amount)
                put("occurred_at", occurredAt)
                put("description", description)
                put("source_package", "INTEGRATION_TEST")
                put("category", TransactionCategory.UNCATEGORIZED.name)
                put("category_source", TransactionCategorySource.DEFAULT.name)
                put("origin", TransactionOrigin.MANUAL.name)
                put("status", status.name)
                put("account", accountName)
                put("account_id", accountId)
                if (dueDate == null) putNull("due_date") else put("due_date", dueDate)
                if (paidAt == null) putNull("paid_at") else put("paid_at", paidAt)
                if (invoiceId == null) putNull("invoice_id") else put("invoice_id", invoiceId)
            },
        )
    }

    private companion object {
        const val DATABASE_NAME = "notification_diagnostics.db"
        const val LATEST_DATABASE_VERSION = 21
    }
}
