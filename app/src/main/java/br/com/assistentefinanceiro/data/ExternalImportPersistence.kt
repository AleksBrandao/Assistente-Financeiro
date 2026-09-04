package br.com.assistentefinanceiro.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import br.com.assistentefinanceiro.notifications.CreditCardBillingCycle
import br.com.assistentefinanceiro.notifications.DiagnosticStore
import br.com.assistentefinanceiro.notifications.FinancialAccountType
import br.com.assistentefinanceiro.notifications.FinancialTransactionType
import br.com.assistentefinanceiro.notifications.TransactionCategory
import br.com.assistentefinanceiro.notifications.TransactionCategoryRule
import br.com.assistentefinanceiro.notifications.TransactionCategorySource
import java.time.LocalDate

/**
 * Persistence adapter for externally sourced data.
 *
 * It deliberately lives outside DiagnosticStore so transport/source rules do not leak into the
 * legacy SQLite helper. The two auxiliary tables are additive and created lazily; core transactions
 * still use the existing transactions table and its unique import_key for idempotence.
 */
internal class ExternalImportPersistence(
    private val store: DiagnosticStore,
) {
    fun accountLinks(provider: ExternalDataProvider): List<ExternalAccountLinkRecord> {
        val db = store.writableDatabase
        ensureTables(db)
        return db.rawQuery(
            """SELECT external_account_id,local_account_id
               FROM external_account_links WHERE provider = ?
               ORDER BY confirmed_at DESC""",
            arrayOf(provider.name),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ExternalAccountLinkRecord(
                            provider = provider,
                            externalAccountId = cursor.getString(0),
                            localAccountId = cursor.getLong(1),
                        ),
                    )
                }
            }
        }
    }

    fun saveAccountLink(link: ExternalAccountLinkRecord): Boolean {
        if (link.externalAccountId.isBlank() || link.localAccountId <= 0) return false
        val db = store.writableDatabase
        ensureTables(db)
        val localExists = db.rawQuery(
            "SELECT 1 FROM financial_accounts WHERE id = ? LIMIT 1",
            arrayOf(link.localAccountId.toString()),
        ).use { it.moveToFirst() }
        if (!localExists) return false
        return db.insertWithOnConflict(
            "external_account_links",
            null,
            ContentValues().apply {
                put("provider", link.provider.name)
                put("external_account_id", link.externalAccountId)
                put("local_account_id", link.localAccountId)
                put("confirmed_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        ) != -1L
    }

    fun importTransactions(drafts: List<ExternalTransactionImportDraft>): ExternalImportResult {
        if (drafts.isEmpty()) return ExternalImportResult(0, 0)
        val db = store.writableDatabase
        ensureTables(db)
        var imported = 0
        var alreadyImported = 0
        db.beginTransaction()
        try {
            drafts.forEach { draft ->
                val account = queryAccount(db, draft.localAccountId) ?: return@forEach
                val ruleKey = if (draft.type == FinancialTransactionType.CARD_PURCHASE) {
                    TransactionCategoryRule.normalizeMerchant(draft.description)
                } else {
                    null
                }
                val ruleCategory = ruleKey?.let { findCategoryRule(db, it, draft.direction.name) }
                val effectiveCategory = ruleCategory ?: draft.category
                val categorySource = when {
                    ruleCategory != null -> TransactionCategorySource.RULE
                    effectiveCategory != TransactionCategory.UNCATEGORIZED ->
                        TransactionCategorySource.EXTERNAL
                    else -> TransactionCategorySource.DEFAULT
                }
                val transactionId = db.insertWithOnConflict(
                    "transactions",
                    null,
                    ContentValues().apply {
                        putNull("source_event_id")
                        put("direction", draft.direction.name)
                        put("type", draft.type.name)
                        put("amount", draft.amount.toPlainString())
                        put("occurred_at", draft.occurredAt.toString())
                        put("description", draft.description.trim())
                        put("source_package", draft.provider.name)
                        put("category", effectiveCategory.name)
                        put("category_source", categorySource.name)
                        if (ruleKey == null) putNull("rule_key") else put("rule_key", ruleKey)
                        put("origin", draft.origin.name)
                        put("status", draft.status.name)
                        put("account", account.name)
                        if (draft.originalCategory == null) putNull("original_category")
                        else put("original_category", draft.originalCategory)
                        put("original_status", draft.status.name)
                        put("account_id", draft.localAccountId)
                        putNull("invoice_id")
                        if (draft.installmentNumber == null) putNull("series_index")
                        else put("series_index", draft.installmentNumber)
                        if (draft.totalInstallments == null) putNull("series_total")
                        else put("series_total", draft.totalInstallments)
                        put("import_key", importKey(draft))
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                if (transactionId == -1L) {
                    alreadyImported++
                    return@forEach
                }

                if (account.type == FinancialAccountType.CREDIT_CARD) {
                    val invoiceId = ensureInvoice(
                        db = db,
                        accountId = draft.localAccountId,
                        closingDay = account.closingDay,
                        dueDay = account.dueDay,
                        accountingDate = draft.occurredAt.toLocalDate(),
                    )
                    if (invoiceId != null) {
                        db.update(
                            "transactions",
                            ContentValues().apply { put("invoice_id", invoiceId) },
                            "id = ?",
                            arrayOf(transactionId.toString()),
                        )
                    }
                }

                db.insertOrThrow(
                    "external_transaction_metadata",
                    null,
                    ContentValues().apply {
                        put("provider", draft.provider.name)
                        put("external_transaction_id", draft.externalTransactionId)
                        put("transaction_id", transactionId)
                        put("external_account_id", draft.externalAccountId)
                        put("purchase_at", draft.purchaseAt)
                        put("source_category_id", draft.sourceCategoryId)
                        put("operation_type", draft.operationType)
                        put("payment_method", draft.paymentMethod)
                        put("installment_number", draft.installmentNumber)
                        put("total_installments", draft.totalInstallments)
                        put("bill_forecast_period", draft.billForecastPeriod?.toString())
                        put("external_bill_id", draft.externalBillId)
                        put("created_at", System.currentTimeMillis())
                    },
                )
                imported++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ExternalImportResult(imported = imported, alreadyImported = alreadyImported)
    }

    private data class StoredAccount(
        val name: String,
        val type: FinancialAccountType,
        val closingDay: Int?,
        val dueDay: Int?,
    )

    private fun queryAccount(db: SQLiteDatabase, accountId: Long): StoredAccount? = db.rawQuery(
        "SELECT name,type,closing_day,due_day FROM financial_accounts WHERE id = ?",
        arrayOf(accountId.toString()),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        StoredAccount(
            name = cursor.getString(0),
            type = FinancialAccountType.fromStored(cursor.getString(1)),
            closingDay = if (cursor.isNull(2)) null else cursor.getInt(2),
            dueDay = if (cursor.isNull(3)) null else cursor.getInt(3),
        )
    }

    private fun findCategoryRule(
        db: SQLiteDatabase,
        ruleKey: String,
        direction: String,
    ): TransactionCategory? = db.rawQuery(
        "SELECT category FROM category_rules WHERE rule_key = ? AND direction = ?",
        arrayOf(ruleKey, direction),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        TransactionCategory.fromStored(cursor.getString(0))
    }

    private fun ensureInvoice(
        db: SQLiteDatabase,
        accountId: Long,
        closingDay: Int?,
        dueDay: Int?,
        accountingDate: LocalDate,
    ): Long? {
        val closing = closingDay ?: return null
        val dates = CreditCardBillingCycle.calculate(accountingDate, closing, dueDay)
        val inserted = db.insertWithOnConflict(
            "credit_card_invoices",
            null,
            ContentValues().apply {
                put("account_id", accountId)
                put("closing_period", dates.closingPeriod.toString())
                put("closing_date", dates.closingDate.toString())
                put("due_date", dates.dueDate?.toString())
                put("status", CreditCardBillingCycle.status(dates.closingDate, LocalDate.now()).name)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        val invoiceId = inserted.takeIf { it != -1L } ?: db.rawQuery(
            """SELECT id FROM credit_card_invoices
               WHERE account_id = ? AND closing_period = ?""",
            arrayOf(accountId.toString(), dates.closingPeriod.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else return null }
        if (dates.dueDate != null) {
            db.update(
                "credit_card_invoices",
                ContentValues().apply { put("due_date", dates.dueDate.toString()) },
                "id = ?",
                arrayOf(invoiceId.toString()),
            )
        }
        return invoiceId
    }

    private fun ensureTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS external_account_links(
                provider TEXT NOT NULL,
                external_account_id TEXT NOT NULL,
                local_account_id INTEGER NOT NULL,
                confirmed_at INTEGER NOT NULL,
                PRIMARY KEY(provider,external_account_id)
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS external_transaction_metadata(
                provider TEXT NOT NULL,
                external_transaction_id TEXT NOT NULL,
                transaction_id INTEGER NOT NULL UNIQUE,
                external_account_id TEXT NOT NULL,
                purchase_at TEXT,
                source_category_id TEXT,
                operation_type TEXT,
                payment_method TEXT,
                installment_number INTEGER,
                total_installments INTEGER,
                bill_forecast_period TEXT,
                external_bill_id TEXT,
                created_at INTEGER NOT NULL,
                PRIMARY KEY(provider,external_transaction_id)
            )""",
        )
        db.execSQL(
            """CREATE INDEX IF NOT EXISTS idx_external_links_local_account
               ON external_account_links(local_account_id)""",
        )
        db.execSQL(
            """CREATE INDEX IF NOT EXISTS idx_external_metadata_account
               ON external_transaction_metadata(provider,external_account_id)""",
        )
    }

    private fun importKey(draft: ExternalTransactionImportDraft): String =
        "EXTERNAL:${draft.provider.name}:${draft.externalTransactionId}"
}
