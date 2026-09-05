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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * Persistence adapter for externally sourced data.
 *
 * External identifiers stay in additive side tables. Core transactions and invoices continue to
 * use the app's existing schema, while repeated provider syncs update the same local rows.
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
        if (drafts.isEmpty()) return ExternalImportResult(0, 0, 0)
        val db = store.writableDatabase
        ensureTables(db)
        var imported = 0
        var alreadyImported = 0
        var updated = 0
        db.beginTransaction()
        try {
            drafts.forEach { draft ->
                val account = queryAccount(db, draft.localAccountId) ?: return@forEach
                val existingTransactionId = findExternalTransactionId(db, draft)
                if (existingTransactionId != null) {
                    syncExistingTransaction(db, existingTransactionId, draft, account)
                    updateExternalMetadata(db, existingTransactionId, draft)
                    updated++
                    return@forEach
                }

                val categoryValues = categoryValues(db, draft)
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
                        put("category", categoryValues.category.name)
                        put("category_source", categoryValues.source.name)
                        if (categoryValues.ruleKey == null) putNull("rule_key")
                        else put("rule_key", categoryValues.ruleKey)
                        if (categoryValues.customCategory == null) putNull("custom_category")
                        else put("custom_category", categoryValues.customCategory)
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

                attachInvoiceIfNeeded(db, transactionId, draft, account)
                insertExternalMetadata(db, transactionId, draft)
                imported++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ExternalImportResult(
            imported = imported,
            alreadyImported = alreadyImported,
            updated = updated,
        )
    }

    fun importBills(drafts: List<ExternalBillImportDraft>): ExternalBillImportResult {
        if (drafts.isEmpty()) return ExternalBillImportResult(0, 0)
        val db = store.writableDatabase
        ensureTables(db)
        var billsSynced = 0
        var paymentsSynced = 0
        db.beginTransaction()
        try {
            drafts.groupBy { it.localAccountId }.forEach { (accountId, accountDrafts) ->
                val account = queryAccount(db, accountId) ?: return@forEach
                if (account.type != FinancialAccountType.CREDIT_CARD) return@forEach
                accountDrafts.sortedBy { it.dueDate }.forEach { bill ->
                    val invoice = ensureOfficialInvoice(db, accountId, account, bill)
                    saveExternalBillLink(db, bill, invoice.closingPeriod)
                    synchronizeOfficialTotal(db, invoice.id, accountId, invoice.closingPeriod, bill.totalAmount)
                    billsSynced++
                }

                // Open Finance Regulado reports payment of Bill N in the following bill cycle.
                // Therefore each payment carried by the current bill is attached to the latest
                // earlier local invoice, never blindly to the current bill itself.
                accountDrafts.sortedBy { it.dueDate }.forEach { sourceBill ->
                    if (sourceBill.payments.isEmpty()) return@forEach
                    val target = previousInvoice(db, accountId, sourceBill.dueDate) ?: return@forEach
                    sourceBill.payments.forEach { payment ->
                        if (
                            upsertExternalBillPayment(
                                db = db,
                                provider = sourceBill.provider,
                                sourceExternalBillId = sourceBill.externalBillId,
                                targetAccountId = accountId,
                                targetClosingPeriod = target.closingPeriod,
                                payment = payment,
                            )
                        ) {
                            paymentsSynced++
                        }
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ExternalBillImportResult(billsSynced, paymentsSynced)
    }

    private data class StoredAccount(
        val name: String,
        val type: FinancialAccountType,
        val closingDay: Int?,
        val dueDay: Int?,
    )

    private data class CategoryValues(
        val category: TransactionCategory,
        val customCategory: String?,
        val source: TransactionCategorySource,
        val ruleKey: String?,
    )

    private data class StoredInvoice(
        val id: Long,
        val closingPeriod: YearMonth,
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

    private fun categoryValues(
        db: SQLiteDatabase,
        draft: ExternalTransactionImportDraft,
    ): CategoryValues {
        val ruleKey = if (draft.type == FinancialTransactionType.CARD_PURCHASE) {
            TransactionCategoryRule.normalizeMerchant(draft.description)
        } else {
            null
        }
        val ruleCategory = ruleKey?.let { findCategoryRule(db, it, draft.direction.name) }
        return if (ruleCategory != null) {
            CategoryValues(
                category = ruleCategory,
                customCategory = null,
                source = TransactionCategorySource.RULE,
                ruleKey = ruleKey,
            )
        } else {
            val hasExternalCategory = draft.category != TransactionCategory.UNCATEGORIZED ||
                !draft.customCategory.isNullOrBlank()
            CategoryValues(
                category = draft.category,
                customCategory = draft.customCategory?.trim()?.takeIf(String::isNotBlank),
                source = if (hasExternalCategory) {
                    TransactionCategorySource.EXTERNAL
                } else {
                    TransactionCategorySource.DEFAULT
                },
                ruleKey = ruleKey,
            )
        }
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

    private fun findExternalTransactionId(
        db: SQLiteDatabase,
        draft: ExternalTransactionImportDraft,
    ): Long? = db.rawQuery(
        """SELECT transaction_id FROM external_transaction_metadata
           WHERE provider = ? AND external_transaction_id = ? LIMIT 1""",
        arrayOf(draft.provider.name, draft.externalTransactionId),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    private fun syncExistingTransaction(
        db: SQLiteDatabase,
        transactionId: Long,
        draft: ExternalTransactionImportDraft,
        account: StoredAccount,
    ) {
        val manuallyEdited = db.rawQuery(
            "SELECT category_source FROM transactions WHERE id = ?",
            arrayOf(transactionId.toString()),
        ).use { cursor ->
            cursor.moveToFirst() &&
                TransactionCategorySource.fromStored(cursor.getString(0)) == TransactionCategorySource.MANUAL
        }
        val values = ContentValues().apply {
            put("direction", draft.direction.name)
            put("type", draft.type.name)
            put("amount", draft.amount.toPlainString())
            put("occurred_at", draft.occurredAt.toString())
            put("status", draft.status.name)
            put("original_status", draft.status.name)
            put("source_package", draft.provider.name)
            put("origin", draft.origin.name)
            put("account", account.name)
            put("account_id", draft.localAccountId)
            if (draft.originalCategory == null) putNull("original_category")
            else put("original_category", draft.originalCategory)
            if (!manuallyEdited) {
                put("description", draft.description.trim())
                val categoryValues = categoryValues(db, draft)
                put("category", categoryValues.category.name)
                put("category_source", categoryValues.source.name)
                if (categoryValues.ruleKey == null) putNull("rule_key")
                else put("rule_key", categoryValues.ruleKey)
                if (categoryValues.customCategory == null) putNull("custom_category")
                else put("custom_category", categoryValues.customCategory)
            }
        }
        db.update("transactions", values, "id = ?", arrayOf(transactionId.toString()))
        attachInvoiceIfNeeded(db, transactionId, draft, account)
    }

    private fun attachInvoiceIfNeeded(
        db: SQLiteDatabase,
        transactionId: Long,
        draft: ExternalTransactionImportDraft,
        account: StoredAccount,
    ) {
        if (account.type != FinancialAccountType.CREDIT_CARD) return
        val invoiceId = ensureInvoiceForDraft(db, draft, account) ?: return
        db.update(
            "transactions",
            ContentValues().apply { put("invoice_id", invoiceId) },
            "id = ?",
            arrayOf(transactionId.toString()),
        )
    }

    private fun ensureInvoiceForDraft(
        db: SQLiteDatabase,
        draft: ExternalTransactionImportDraft,
        account: StoredAccount,
    ): Long? {
        if (draft.invoiceClosingDate != null || draft.invoiceDueDate != null) {
            val dates = when {
                draft.invoiceClosingDate != null -> {
                    val closingPeriod = YearMonth.from(draft.invoiceClosingDate)
                    Triple(closingPeriod, draft.invoiceClosingDate, draft.invoiceDueDate)
                }
                draft.invoiceDueDate != null && account.closingDay != null -> {
                    val calculated = CreditCardBillingCycle.fromImportedInvoiceDate(
                        invoiceDate = draft.invoiceDueDate,
                        closingDay = account.closingDay,
                        configuredDueDay = account.dueDay,
                    )
                    Triple(calculated.closingPeriod, calculated.closingDate, draft.invoiceDueDate)
                }
                else -> null
            }
            if (dates != null) {
                return ensureInvoiceByDates(db, draft.localAccountId, dates.first, dates.second, dates.third)
            }
        }
        draft.billForecastPeriod?.let { period ->
            val closingDay = account.closingDay ?: return@let
            val closingDate = dateAtDay(period, closingDay)
            val dueDate = account.dueDay?.let { dueDay ->
                val duePeriod = if (dueDay <= closingDay) period.plusMonths(1) else period
                dateAtDay(duePeriod, dueDay)
            }
            return ensureInvoiceByDates(db, draft.localAccountId, period, closingDate, dueDate)
        }
        return ensureInvoice(
            db = db,
            accountId = draft.localAccountId,
            closingDay = account.closingDay,
            dueDay = account.dueDay,
            accountingDate = draft.occurredAt.toLocalDate(),
        )
    }

    private fun ensureOfficialInvoice(
        db: SQLiteDatabase,
        accountId: Long,
        account: StoredAccount,
        bill: ExternalBillImportDraft,
    ): StoredInvoice {
        val dates = if (bill.closingDate != null) {
            Triple(YearMonth.from(bill.closingDate), bill.closingDate, bill.dueDate)
        } else if (account.closingDay != null) {
            val calculated = CreditCardBillingCycle.fromImportedInvoiceDate(
                invoiceDate = bill.dueDate,
                closingDay = account.closingDay,
                configuredDueDay = account.dueDay,
            )
            Triple(calculated.closingPeriod, calculated.closingDate, bill.dueDate)
        } else {
            val period = YearMonth.from(bill.dueDate)
            Triple(period, bill.dueDate, bill.dueDate)
        }
        val id = checkNotNull(
            ensureInvoiceByDates(db, accountId, dates.first, dates.second, dates.third),
        )
        return StoredInvoice(id, dates.first)
    }

    private fun ensureInvoiceByDates(
        db: SQLiteDatabase,
        accountId: Long,
        closingPeriod: YearMonth,
        closingDate: LocalDate,
        dueDate: LocalDate?,
    ): Long? {
        val inserted = db.insertWithOnConflict(
            "credit_card_invoices",
            null,
            ContentValues().apply {
                put("account_id", accountId)
                put("closing_period", closingPeriod.toString())
                put("closing_date", closingDate.toString())
                if (dueDate == null) putNull("due_date") else put("due_date", dueDate.toString())
                put("status", CreditCardBillingCycle.status(closingDate, LocalDate.now()).name)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        val invoiceId = inserted.takeIf { it != -1L } ?: db.rawQuery(
            """SELECT id FROM credit_card_invoices
               WHERE account_id = ? AND closing_period = ?""",
            arrayOf(accountId.toString(), closingPeriod.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else return null }
        db.update(
            "credit_card_invoices",
            ContentValues().apply {
                put("closing_date", closingDate.toString())
                if (dueDate == null) putNull("due_date") else put("due_date", dueDate.toString())
            },
            "id = ?",
            arrayOf(invoiceId.toString()),
        )
        return invoiceId
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
        return ensureInvoiceByDates(
            db,
            accountId,
            dates.closingPeriod,
            dates.closingDate,
            dates.dueDate,
        )
    }

    private fun synchronizeOfficialTotal(
        db: SQLiteDatabase,
        invoiceId: Long,
        accountId: Long,
        closingPeriod: YearMonth,
        officialTotal: BigDecimal,
    ) {
        val baseTotal = db.rawQuery(
            """SELECT COALESCE(SUM(CASE
                   WHEN direction = 'EXPENSE' THEN CAST(amount AS REAL)
                   ELSE -CAST(amount AS REAL) END),0)
               FROM transactions WHERE invoice_id = ?""",
            arrayOf(invoiceId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) BigDecimal.ZERO
            else cursor.getString(0)?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        }
        val adjustment = officialTotal - baseTotal
        db.insertWithOnConflict(
            "invoice_adjustments",
            null,
            ContentValues().apply {
                put("account_id", accountId)
                put("closing_period", closingPeriod.toString())
                put("amount", adjustment.toPlainString())
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun saveExternalBillLink(
        db: SQLiteDatabase,
        bill: ExternalBillImportDraft,
        closingPeriod: YearMonth,
    ) {
        db.insertWithOnConflict(
            "external_bill_links",
            null,
            ContentValues().apply {
                put("provider", bill.provider.name)
                put("external_bill_id", bill.externalBillId)
                put("external_account_id", bill.externalAccountId)
                put("local_account_id", bill.localAccountId)
                put("closing_period", closingPeriod.toString())
                put("finance_charge_total", bill.financeChargeTotal.toPlainString())
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun previousInvoice(
        db: SQLiteDatabase,
        accountId: Long,
        beforeDueDate: LocalDate,
    ): StoredInvoice? = db.rawQuery(
        """SELECT id,closing_period FROM credit_card_invoices
           WHERE account_id = ? AND due_date IS NOT NULL AND due_date < ?
           ORDER BY due_date DESC, closing_period DESC LIMIT 1""",
        arrayOf(accountId.toString(), beforeDueDate.toString()),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null
        else StoredInvoice(cursor.getLong(0), YearMonth.parse(cursor.getString(1)))
    }

    private fun upsertExternalBillPayment(
        db: SQLiteDatabase,
        provider: ExternalDataProvider,
        sourceExternalBillId: String,
        targetAccountId: Long,
        targetClosingPeriod: YearMonth,
        payment: ExternalBillPaymentDraft,
    ): Boolean {
        val existingPaymentId = db.rawQuery(
            """SELECT invoice_payment_id FROM external_bill_payment_links
               WHERE provider = ? AND external_payment_id = ?""",
            arrayOf(provider.name, payment.externalPaymentId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

        val paymentId = if (existingPaymentId != null) {
            db.update(
                "invoice_payments",
                ContentValues().apply {
                    put("account_id", targetAccountId)
                    put("closing_period", targetClosingPeriod.toString())
                    put("amount", payment.amount.toPlainString())
                    put("paid_at", payment.paidAt.toString())
                },
                "id = ?",
                arrayOf(existingPaymentId.toString()),
            )
            existingPaymentId
        } else {
            db.insertOrThrow(
                "invoice_payments",
                null,
                ContentValues().apply {
                    put("account_id", targetAccountId)
                    put("closing_period", targetClosingPeriod.toString())
                    put("amount", payment.amount.toPlainString())
                    put("paid_at", payment.paidAt.toString())
                    put("created_at", System.currentTimeMillis())
                    putNull("source_account_id")
                },
            )
        }

        return db.insertWithOnConflict(
            "external_bill_payment_links",
            null,
            ContentValues().apply {
                put("provider", provider.name)
                put("external_payment_id", payment.externalPaymentId)
                put("source_external_bill_id", sourceExternalBillId)
                put("invoice_payment_id", paymentId)
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        ) != -1L
    }

    private fun insertExternalMetadata(
        db: SQLiteDatabase,
        transactionId: Long,
        draft: ExternalTransactionImportDraft,
    ) {
        db.insertOrThrow(
            "external_transaction_metadata",
            null,
            externalMetadataValues(transactionId, draft).apply {
                put("created_at", System.currentTimeMillis())
            },
        )
    }

    private fun updateExternalMetadata(
        db: SQLiteDatabase,
        transactionId: Long,
        draft: ExternalTransactionImportDraft,
    ) {
        db.update(
            "external_transaction_metadata",
            externalMetadataValues(transactionId, draft),
            "provider = ? AND external_transaction_id = ?",
            arrayOf(draft.provider.name, draft.externalTransactionId),
        )
    }

    private fun externalMetadataValues(
        transactionId: Long,
        draft: ExternalTransactionImportDraft,
    ): ContentValues = ContentValues().apply {
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
            """CREATE TABLE IF NOT EXISTS external_bill_links(
                provider TEXT NOT NULL,
                external_bill_id TEXT NOT NULL,
                external_account_id TEXT NOT NULL,
                local_account_id INTEGER NOT NULL,
                closing_period TEXT NOT NULL,
                finance_charge_total TEXT NOT NULL DEFAULT '0',
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(provider,external_bill_id)
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS external_bill_payment_links(
                provider TEXT NOT NULL,
                external_payment_id TEXT NOT NULL,
                source_external_bill_id TEXT NOT NULL,
                invoice_payment_id INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(provider,external_payment_id)
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
        db.execSQL(
            """CREATE INDEX IF NOT EXISTS idx_external_bill_local_account
               ON external_bill_links(local_account_id,closing_period)""",
        )
    }

    private fun dateAtDay(period: YearMonth, day: Int): LocalDate =
        period.atDay(day.coerceAtMost(period.lengthOfMonth()))

    private fun importKey(draft: ExternalTransactionImportDraft): String =
        "EXTERNAL:${draft.provider.name}:${draft.externalTransactionId}"
}
