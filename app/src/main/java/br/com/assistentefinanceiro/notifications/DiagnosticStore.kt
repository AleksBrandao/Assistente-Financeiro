package br.com.assistentefinanceiro.notifications

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import br.com.assistentefinanceiro.importing.ImportDisposition
import br.com.assistentefinanceiro.importing.MobillsImportPreview
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import java.util.UUID
import android.database.Cursor
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class DiagnosticEvent(
    val id: Long,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val body: String,
    val postedAt: Long,
    val classification: NotificationClassification,
    val classificationReason: String?,
    val transactionType: FinancialTransactionType?,
    val occurredAt: String?,
    val cardLastFour: String?,
    val amount: String?,
    val merchant: String?,
) {
    val parsed: Boolean
        get() = classification == NotificationClassification.TRANSACTION
}

class DiagnosticStore(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE candidates(
                package_name TEXT PRIMARY KEY, app_label TEXT NOT NULL, last_seen_at INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE events(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package_name TEXT NOT NULL, app_label TEXT NOT NULL,
                title TEXT NOT NULL, body TEXT NOT NULL, posted_at INTEGER NOT NULL,
                notification_key TEXT, fingerprint TEXT,
                parsed INTEGER NOT NULL,
                classification TEXT NOT NULL DEFAULT 'PENDING_RULE', classification_reason TEXT,
                transaction_type TEXT, occurred_at TEXT,
                card_last_four TEXT, amount TEXT, merchant TEXT
            )"""
        )
        createTransactionsTable(db)
        createCategoryRulesTable(db)
        createFinancialAccountsTable(db)
        createCreditCardInvoicesTable(db)
        createInvoicePaymentsTable(db)
        createInvoiceAdjustmentsTable(db)
        createAccountMovementsTable(db)
        createDeletedTransactionsTables(db)
        createMonthlyBudgetsTable(db)
        createIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "ALTER TABLE events ADD COLUMN classification TEXT NOT NULL DEFAULT 'PENDING_RULE'"
            )
            db.execSQL("ALTER TABLE events ADD COLUMN classification_reason TEXT")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE events ADD COLUMN transaction_type TEXT")
            db.execSQL("ALTER TABLE events ADD COLUMN occurred_at TEXT")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE events ADD COLUMN notification_key TEXT")
            db.execSQL("ALTER TABLE events ADD COLUMN fingerprint TEXT")
            createTransactionsTable(db)
            createIndexes(db)
        }
        if (oldVersion == 4) {
            db.execSQL(
                "ALTER TABLE transactions ADD COLUMN category TEXT NOT NULL DEFAULT 'UNCATEGORIZED'"
            )
        }
        if (oldVersion in 4..5) {
            db.execSQL(
                "ALTER TABLE transactions ADD COLUMN category_source TEXT NOT NULL DEFAULT 'DEFAULT'"
            )
            db.execSQL("ALTER TABLE transactions ADD COLUMN rule_key TEXT")
        }
        if (oldVersion < 7) {
            migrateTransactionsForImports(db)
        } else if (oldVersion < 8) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN original_status TEXT")
            db.delete("transactions", "origin = ?", arrayOf(TransactionOrigin.MOBILLS.name))
        }
        if (oldVersion < 9) {
            createFinancialAccountsTable(db)
            if (oldVersion >= 7) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN account_id INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN invoice_id INTEGER")
            }
            initializeFinancialAccounts(db)
        }
        if (oldVersion < 10) {
            createCreditCardInvoicesTable(db)
            if (oldVersion >= 9) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN invoice_id INTEGER")
                db.execSQL("ALTER TABLE financial_accounts ADD COLUMN card_identifiers TEXT")
            }
            initializeCardIdentifiers(db)
            linkNotificationTransactionsToAccounts(db)
            rebuildAllCreditCardInvoices(db)
        }
        if (oldVersion < 11) {
            createInvoicePaymentsTable(db)
        }
        if (oldVersion == 11) {
            db.execSQL("ALTER TABLE invoice_payments ADD COLUMN source_account_id INTEGER")
        }
        if (oldVersion < 13) {
            createAccountMovementsTable(db)
            rebuildPaymentAccountMovements(db)
        }
        if (oldVersion < 14) {
            if (oldVersion >= 9) {
                db.execSQL(
                    "ALTER TABLE financial_accounts ADD COLUMN opening_balance TEXT NOT NULL DEFAULT '0'"
                )
                db.execSQL("ALTER TABLE financial_accounts ADD COLUMN opening_balance_date TEXT")
            }
            if (oldVersion >= 13) {
                db.execSQL(
                    "ALTER TABLE account_movements ADD COLUMN direction TEXT NOT NULL DEFAULT 'DEBIT'"
                )
                db.execSQL("ALTER TABLE account_movements ADD COLUMN related_account_id INTEGER")
                db.execSQL("ALTER TABLE account_movements ADD COLUMN transfer_group TEXT")
            }
        }
        if (oldVersion in 7..14) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN original_amount TEXT")
            db.execSQL("ALTER TABLE transactions ADD COLUMN due_date TEXT")
            db.execSQL("ALTER TABLE transactions ADD COLUMN paid_at TEXT")
        }
        if (oldVersion < 15) createInvoiceAdjustmentsTable(db)
        if (oldVersion in 7..15) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN planned_payment_date TEXT")
        }
        if (oldVersion in 7..16) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN series_id TEXT")
            db.execSQL("ALTER TABLE transactions ADD COLUMN series_index INTEGER")
            db.execSQL("ALTER TABLE transactions ADD COLUMN series_total INTEGER")
            initializeImportedInstallmentSeries(db)
        }
        if (oldVersion < 18) createDeletedTransactionsTables(db)
        if (oldVersion < 19) createMonthlyBudgetsTable(db)
        if (oldVersion < 21) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN custom_category TEXT")
            db.execSQL("ALTER TABLE transactions ADD COLUMN subcategory TEXT")
        }
        createIndexes(db)
        createCategoryRulesTable(db)

        reclassifyExistingEvents(db)

        if (oldVersion < 4) {
            rebuildTransactionsFromEvents(db)
        }
        if (oldVersion < 6) {
            initializeCategoryMetadata(db)
        }
    }

    fun recordCandidate(packageName: String, appLabel: String, postedAt: Long) {
        writableDatabase.insertWithOnConflict(
            "candidates",
            null,
            ContentValues().apply {
                put("package_name", packageName)
                put("app_label", appLabel)
                put("last_seen_at", postedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun recordEvent(
        packageName: String,
        appLabel: String,
        title: String,
        body: String,
        postedAt: Long,
        notificationKey: String? = null,
    ) {
        val result = FinancialNotificationClassifier.classify(packageName, appLabel, title, body)
        val transaction = result.transaction
        val fingerprint = NotificationFingerprint.create(packageName, title, body, postedAt)
        val db = writableDatabase

        db.beginTransaction()
        try {
            val eventId = db.insertWithOnConflict(
                "events",
                null,
                ContentValues().apply {
                    put("package_name", packageName)
                    put("app_label", appLabel)
                    put("title", title)
                    put("body", body)
                    put("posted_at", postedAt)
                    put("notification_key", notificationKey)
                    put("fingerprint", fingerprint)
                    put(
                        "parsed",
                        if (result.classification == NotificationClassification.TRANSACTION) 1 else 0,
                    )
                    put("classification", result.classification.name)
                    put("classification_reason", result.reason)
                    put("transaction_type", transaction?.type?.name)
                    put("occurred_at", transaction?.occurredAt?.toString())
                    put("card_last_four", transaction?.cardLastFour)
                    put("amount", transaction?.amount?.toPlainString())
                    put("merchant", transaction?.merchant)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )

            if (eventId != -1L && transaction != null) {
                insertTransaction(
                    db = db,
                    sourceEventId = eventId,
                    sourcePackage = packageName,
                    type = transaction.type,
                    amount = transaction.amount.toPlainString(),
                    occurredAt = transaction.occurredAt.toString(),
                    description = transactionDescription(transaction.type, transaction.merchant),
                    merchant = transaction.merchant,
                    cardLastFour = transaction.cardLastFour,
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun candidates(): List<Pair<String, String>> = readableDatabase.rawQuery(
        "SELECT package_name, app_label FROM candidates ORDER BY last_seen_at DESC",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
        }
    }

    fun recentEvents(limit: Int = 50): List<DiagnosticEvent> = readableDatabase.rawQuery(
        """SELECT id,package_name,app_label,title,body,posted_at,classification,
                  classification_reason,transaction_type,occurred_at,card_last_four,amount,merchant
           FROM events ORDER BY posted_at DESC LIMIT ?""",
        arrayOf(limit.coerceIn(1, 200).toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    DiagnosticEvent(
                        id = cursor.getLong(0),
                        packageName = cursor.getString(1),
                        appLabel = cursor.getString(2),
                        title = cursor.getString(3),
                        body = cursor.getString(4),
                        postedAt = cursor.getLong(5),
                        classification = NotificationClassification.fromStored(cursor.getString(6)),
                        classificationReason = cursor.getString(7),
                        transactionType = FinancialTransactionType.fromStored(cursor.getString(8)),
                        occurredAt = cursor.getString(9),
                        cardLastFour = cursor.getString(10),
                        amount = cursor.getString(11),
                        merchant = cursor.getString(12),
                    )
                )
            }
        }
    }

    fun recentTransactions(limit: Int = 100): List<FinancialTransactionRecord> =
        readableDatabase.rawQuery(
            """SELECT id,source_event_id,direction,type,amount,occurred_at,description,source_package,
                      category,category_source,rule_key,origin,status,account,original_category,
                      original_status,account_id,invoice_id,original_amount,due_date,
                      planned_payment_date,paid_at,series_id,series_index,series_total,
                      custom_category,subcategory
               FROM transactions ORDER BY occurred_at DESC, id DESC LIMIT ?""",
            arrayOf(limit.coerceIn(1, 10_000).toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val direction = FinancialTransactionDirection.fromStored(cursor.getString(2))
                    val type = FinancialTransactionType.fromStored(cursor.getString(3))
                    if (direction != null && type != null) {
                        add(
                            FinancialTransactionRecord(
                                id = cursor.getLong(0),
                                sourceEventId = if (cursor.isNull(1)) null else cursor.getLong(1),
                                direction = direction,
                                type = type,
                                amount = cursor.getString(4),
                                occurredAt = cursor.getString(5),
                                description = cursor.getString(6),
                                sourcePackage = cursor.getString(7),
                                category = TransactionCategory.fromStored(cursor.getString(8)),
                                categorySource =
                                    TransactionCategorySource.fromStored(cursor.getString(9)),
                                ruleKey = cursor.getString(10),
                                origin = TransactionOrigin.fromStored(cursor.getString(11)),
                                status = TransactionStatus.fromStored(cursor.getString(12)),
                                account = cursor.getString(13),
                                originalCategory = cursor.getString(14),
                                originalStatus = cursor.getString(15),
                                accountId = if (cursor.isNull(16)) null else cursor.getLong(16),
                                invoiceId = if (cursor.isNull(17)) null else cursor.getLong(17),
                                originalAmount = cursor.getString(18),
                                dueDate = cursor.getString(19),
                                plannedPaymentDate = cursor.getString(20),
                                paidAt = cursor.getString(21),
                                seriesId = cursor.getString(22),
                                seriesIndex = if (cursor.isNull(23)) null else cursor.getInt(23),
                                seriesTotal = if (cursor.isNull(24)) null else cursor.getInt(24),
                                customCategory = cursor.getString(25),
                                subcategory = cursor.getString(26),
                            )
                        )
                    }
                }
            }
        }

    fun customCategories(direction: FinancialTransactionDirection): List<String> =
        readableDatabase.rawQuery(
            """SELECT DISTINCT custom_category FROM transactions
               WHERE direction = ? AND custom_category IS NOT NULL
                 AND TRIM(custom_category) <> ''
               ORDER BY custom_category COLLATE NOCASE""",
            arrayOf(direction.name),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    fun updateTransactionDetails(
        transactionId: Long,
        description: String,
        category: TransactionCategory,
        customCategory: String?,
        subcategory: String?,
        status: TransactionStatus,
        amount: java.math.BigDecimal,
        dueDate: LocalDate?,
        plannedPaymentDate: LocalDate?,
        paidAt: LocalDate?,
        applyToFuture: Boolean = false,
        seriesScope: TransactionSeriesScope = TransactionSeriesScope.ONLY_THIS,
    ): Boolean {
        val normalizedDescription = description.trim()
        if (normalizedDescription.isBlank()) return false

        val db = writableDatabase
        val metadata = db.rawQuery(
            "SELECT direction,type,rule_key,series_id,series_index FROM transactions WHERE id = ?",
            arrayOf(transactionId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val direction = FinancialTransactionDirection.fromStored(cursor.getString(0))
                ?: return@use null
            val type = FinancialTransactionType.fromStored(cursor.getString(1))
                ?: return@use null
            StoredTransactionMetadata(
                direction = direction,
                type = type,
                ruleKey = cursor.getString(2),
                seriesId = cursor.getString(3),
                seriesIndex = if (cursor.isNull(4)) null else cursor.getInt(4),
            )
        } ?: return false

        if (!category.supports(metadata.direction) || amount.signum() <= 0) return false
        val shouldSaveRule = applyToFuture && TransactionCategoryRule.canApplyToFuture(
            type = metadata.type,
            category = category,
            ruleKey = metadata.ruleKey,
        )

        var updated = false
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE transactions SET original_amount = amount WHERE id = ? AND original_amount IS NULL",
                arrayOf(transactionId),
            )
            updated = db.update(
                "transactions",
                ContentValues().apply {
                    put("description", normalizedDescription)
                    put("category", category.name)
                    if (customCategory.isNullOrBlank()) putNull("custom_category")
                    else put("custom_category", customCategory.trim())
                    if (subcategory.isNullOrBlank()) putNull("subcategory")
                    else put("subcategory", subcategory.trim())
                    put("category_source", TransactionCategorySource.MANUAL.name)
                    put(
                        "status",
                        if (paidAt != null) TransactionStatus.REALIZED.name else status.name,
                    )
                    put("amount", amount.toPlainString())
                    if (dueDate == null) putNull("due_date") else put("due_date", dueDate.toString())
                    if (plannedPaymentDate == null) putNull("planned_payment_date")
                    else put("planned_payment_date", plannedPaymentDate.toString())
                    if (paidAt == null) putNull("paid_at") else put("paid_at", paidAt.toString())
                },
                "id = ?",
                arrayOf(transactionId.toString()),
            ) == 1

            if (
                updated && metadata.seriesId != null &&
                seriesScope != TransactionSeriesScope.ONLY_THIS
            ) {
                val selection: String
                val selectionArgs: Array<String>
                if (seriesScope == TransactionSeriesScope.ALL) {
                    selection = "series_id = ? AND id != ?"
                    selectionArgs = arrayOf(metadata.seriesId, transactionId.toString())
                } else {
                    selection = "series_id = ? AND series_index >= ? AND id != ?"
                    selectionArgs = arrayOf(
                        metadata.seriesId,
                        checkNotNull(metadata.seriesIndex).toString(),
                        transactionId.toString(),
                    )
                }
                db.update(
                    "transactions",
                    ContentValues().apply {
                        put("description", normalizedDescription)
                        put("category", category.name)
                        if (customCategory.isNullOrBlank()) putNull("custom_category")
                        else put("custom_category", customCategory.trim())
                        if (subcategory.isNullOrBlank()) putNull("subcategory")
                        else put("subcategory", subcategory.trim())
                        put("category_source", TransactionCategorySource.MANUAL.name)
                        put("amount", amount.toPlainString())
                    },
                    selection,
                    selectionArgs,
                )
            }

            if (updated && shouldSaveRule) {
                val ruleKey = checkNotNull(metadata.ruleKey)
                db.insertWithOnConflict(
                    "category_rules",
                    null,
                    ContentValues().apply {
                        put("rule_key", ruleKey)
                        put("direction", metadata.direction.name)
                        put("category", category.name)
                        put("updated_at", System.currentTimeMillis())
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                db.update(
                    "transactions",
                    ContentValues().apply {
                        put("category", category.name)
                        put("category_source", TransactionCategorySource.RULE.name)
                    },
                    """rule_key = ? AND direction = ? AND id <> ?
                       AND category_source IN (?, ?)""",
                    arrayOf(
                        ruleKey,
                        metadata.direction.name,
                        transactionId.toString(),
                        TransactionCategorySource.DEFAULT.name,
                        TransactionCategorySource.RULE.name,
                    ),
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return updated
    }

    fun importMobills(
        preview: MobillsImportPreview,
        includePossibleDuplicates: Boolean,
    ): MobillsImportResult {
        val accepted = preview.rows.filter { row ->
            row.disposition == ImportDisposition.READY ||
                row.disposition == ImportDisposition.PENDING ||
                (includePossibleDuplicates && row.disposition == ImportDisposition.POSSIBLE_DUPLICATE)
        }
        var imported = 0
        var alreadyImported = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            val affectedAccounts = mutableSetOf<Long>()
            accepted.forEach { row ->
                val direction = row.direction ?: return@forEach
                val date = row.date ?: return@forEach
                val amount = row.amount ?: return@forEach
                val accountId = ensureFinancialAccount(db, row.account)
                val result = db.insertWithOnConflict(
                    "transactions",
                    null,
                    ContentValues().apply {
                        putNull("source_event_id")
                        put("direction", direction.name)
                        put(
                            "type",
                            if (direction == FinancialTransactionDirection.INCOME) {
                                FinancialTransactionType.IMPORTED_INCOME.name
                            } else {
                                FinancialTransactionType.IMPORTED_EXPENSE.name
                            },
                        )
                        put("amount", amount.toPlainString())
                        put("occurred_at", date.atStartOfDay().toString())
                        put("description", row.description)
                        put("source_package", "MOBILLS")
                        put("category", row.category.name)
                        put("category_source", TransactionCategorySource.MANUAL.name)
                        putNull("rule_key")
                        put("origin", TransactionOrigin.MOBILLS.name)
                        put(
                            "status",
                            if (row.disposition == ImportDisposition.PENDING) {
                                TransactionStatus.PENDING.name
                            } else {
                                TransactionStatus.REALIZED.name
                            },
                        )
                        put("account", row.account)
                        put("original_category", row.originalCategory)
                        put("original_status", row.situation)
                        put("account_id", accountId)
                        put("import_key", row.importKey)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                if (result == -1L) alreadyImported++ else imported++
                if (result != -1L) affectedAccounts += accountId
            }
            affectedAccounts.forEach { rebuildCreditCardInvoices(db, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return MobillsImportResult(imported, alreadyImported)
    }

    fun financialAccounts(): List<FinancialAccountRecord> = readableDatabase.rawQuery(
        """SELECT id,name,type,closing_day,due_day,is_default,card_identifiers,
                  opening_balance,opening_balance_date
           FROM financial_accounts
           ORDER BY type DESC,is_default DESC,name COLLATE NOCASE""",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    FinancialAccountRecord(
                        id = cursor.getLong(0),
                        name = cursor.getString(1),
                        type = FinancialAccountType.fromStored(cursor.getString(2)),
                        closingDay = if (cursor.isNull(3)) null else cursor.getInt(3),
                        dueDay = if (cursor.isNull(4)) null else cursor.getInt(4),
                        isDefault = cursor.getInt(5) == 1,
                        cardIdentifiers = cursor.getString(6),
                        openingBalance = cursor.getString(7).toBigDecimalOrNull()
                            ?: java.math.BigDecimal.ZERO,
                        openingBalanceDate = cursor.getString(8)?.let(LocalDate::parse),
                    )
                )
            }
        }
    }

    fun saveFinancialAccount(
        id: Long?,
        name: String,
        type: FinancialAccountType,
        closingDay: Int?,
        dueDay: Int?,
        isDefault: Boolean,
        cardIdentifiers: String?,
        openingBalance: java.math.BigDecimal,
        openingBalanceDate: LocalDate?,
    ): Boolean {
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) return false
        if (closingDay != null && closingDay !in 1..31) return false
        if (dueDay != null && dueDay !in 1..31) return false
        val normalizedKey = FinancialAccountIdentity.normalize(normalizedName)
        val normalizedIdentifiers = FinancialAccountIdentity.normalizedIdentifiers(cardIdentifiers)
        if (normalizedKey.isBlank()) return false
        if (type == FinancialAccountType.BANK_ACCOUNT && openingBalanceDate == null) return false
        val db = writableDatabase
        db.beginTransaction()
        return try {
            if (isDefault && type == FinancialAccountType.CREDIT_CARD) {
                db.update(
                    "financial_accounts",
                    ContentValues().apply { put("is_default", 0) },
                    "type = ?",
                    arrayOf(FinancialAccountType.CREDIT_CARD.name),
                )
            }
            val values = ContentValues().apply {
                put("name", normalizedName)
                put("normalized_name", normalizedKey)
                put("type", type.name)
                if (closingDay == null) putNull("closing_day") else put("closing_day", closingDay)
                if (dueDay == null) putNull("due_day") else put("due_day", dueDay)
                put("is_default", if (isDefault && type == FinancialAccountType.CREDIT_CARD) 1 else 0)
                if (normalizedIdentifiers == null) putNull("card_identifiers")
                else put("card_identifiers", normalizedIdentifiers)
                put(
                    "opening_balance",
                    if (type == FinancialAccountType.BANK_ACCOUNT) openingBalance.toPlainString()
                    else "0",
                )
                if (type == FinancialAccountType.BANK_ACCOUNT) {
                    put("opening_balance_date", openingBalanceDate?.toString())
                } else {
                    putNull("opening_balance_date")
                }
            }
            val accountId = if (id == null) {
                db.insertWithOnConflict(
                    "financial_accounts", null, values, SQLiteDatabase.CONFLICT_ABORT,
                )
            } else {
                if (db.update("financial_accounts", values, "id = ?", arrayOf(id.toString())) != 1) {
                    -1L
                } else id
            }
            if (accountId == -1L) return false
            linkTransactionsToAccount(db, accountId, normalizedKey)
            rebuildCreditCardInvoices(db, accountId)
            db.setTransactionSuccessful()
            true
        } catch (_: Exception) {
            false
        } finally {
            db.endTransaction()
        }
    }

    fun creditCardInvoices(accountId: Long): List<CreditCardInvoiceRecord> {
        val db = writableDatabase
        refreshInvoiceStatuses(db, accountId, LocalDate.now())
        return db.rawQuery(
            """SELECT invoices.id,invoices.account_id,invoices.closing_period,
                      invoices.closing_date,invoices.due_date,invoices.status,
                      COALESCE(SUM(CASE
                          WHEN transactions.direction = 'EXPENSE' THEN transactions.amount
                          ELSE -transactions.amount
                      END),0),
                      COALESCE((SELECT adjustments.amount FROM invoice_adjustments AS adjustments
                                WHERE adjustments.account_id = invoices.account_id
                                  AND adjustments.closing_period = invoices.closing_period),0),
                      COALESCE((SELECT SUM(payments.amount) FROM invoice_payments AS payments
                                WHERE payments.account_id = invoices.account_id
                                  AND payments.closing_period = invoices.closing_period),0),
                      COUNT(transactions.id)
               FROM credit_card_invoices AS invoices
               LEFT JOIN transactions ON transactions.invoice_id = invoices.id
               WHERE invoices.account_id = ?
               GROUP BY invoices.id
               ORDER BY invoices.closing_period DESC""",
            arrayOf(accountId.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val baseTotal = cursor.getString(6).toBigDecimal()
                    val adjustment = cursor.getString(7).toBigDecimal()
                    val total = baseTotal + adjustment
                    val paid = cursor.getString(8).toBigDecimal()
                    add(
                        CreditCardInvoiceRecord(
                            id = cursor.getLong(0),
                            accountId = cursor.getLong(1),
                            closingPeriod = YearMonth.parse(cursor.getString(2)),
                            closingDate = LocalDate.parse(cursor.getString(3)),
                            dueDate = cursor.getString(4)?.let(LocalDate::parse),
                            status = CreditCardInvoiceStatus.fromStored(cursor.getString(5)),
                            total = total,
                            paidAmount = paid,
                            outstandingAmount = (total - paid).max(java.math.BigDecimal.ZERO),
                            transactionCount = cursor.getInt(9),
                            baseTotal = baseTotal,
                            adjustmentAmount = adjustment,
                        )
                    )
                }
            }
        }
    }

    fun adjustInvoiceTotal(
        invoice: CreditCardInvoiceRecord,
        officialTotal: java.math.BigDecimal,
    ): Boolean {
        if (officialTotal.signum() < 0) return false
        val adjustment = InvoiceAdjustmentCalculator.difference(invoice.baseTotal, officialTotal)
        val db = writableDatabase
        return if (adjustment.signum() == 0) {
            db.delete(
                "invoice_adjustments",
                "account_id = ? AND closing_period = ?",
                arrayOf(invoice.accountId.toString(), invoice.closingPeriod.toString()),
            ) >= 0
        } else {
            db.insertWithOnConflict(
                "invoice_adjustments",
                null,
                ContentValues().apply {
                    put("account_id", invoice.accountId)
                    put("closing_period", invoice.closingPeriod.toString())
                    put("amount", adjustment.toPlainString())
                    put("updated_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            ) != -1L
        }
    }

    fun recordInvoicePayment(
        invoice: CreditCardInvoiceRecord,
        amount: java.math.BigDecimal,
        paidAt: LocalDate,
        sourceAccountId: Long?,
    ): Boolean {
        if (amount.signum() <= 0 || amount > invoice.outstandingAmount) return false
        val db = writableDatabase
        if (sourceAccountId != null && !isBankAccount(db, sourceAccountId)) return false
        db.beginTransaction()
        return try {
            val paymentId = db.insertOrThrow(
                "invoice_payments",
                null,
                ContentValues().apply {
                    put("account_id", invoice.accountId)
                    put("closing_period", invoice.closingPeriod.toString())
                    put("amount", amount.toPlainString())
                    put("paid_at", paidAt.toString())
                    put("created_at", System.currentTimeMillis())
                    if (sourceAccountId == null) putNull("source_account_id")
                    else put("source_account_id", sourceAccountId)
                },
            )
            if (sourceAccountId != null) {
                insertPaymentAccountMovement(
                    db, paymentId, sourceAccountId, amount, paidAt, invoice.accountId,
                )
            }
            refreshInvoiceStatuses(db, invoice.accountId, LocalDate.now())
            db.setTransactionSuccessful()
            true
        } catch (_: Exception) {
            false
        } finally {
            db.endTransaction()
        }
    }

    fun invoicePayments(invoice: CreditCardInvoiceRecord): List<InvoicePaymentRecord> =
        readableDatabase.rawQuery(
            """SELECT payments.id,payments.amount,payments.paid_at,
                      payments.source_account_id,accounts.name
               FROM invoice_payments AS payments
               LEFT JOIN financial_accounts AS accounts
                      ON accounts.id = payments.source_account_id
               WHERE payments.account_id = ? AND payments.closing_period = ?
               ORDER BY payments.paid_at DESC,payments.id DESC""",
            arrayOf(invoice.accountId.toString(), invoice.closingPeriod.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        InvoicePaymentRecord(
                            id = cursor.getLong(0),
                            amount = cursor.getString(1).toBigDecimal(),
                            paidAt = LocalDate.parse(cursor.getString(2)),
                            sourceAccountId = if (cursor.isNull(3)) null else cursor.getLong(3),
                            sourceAccountName = cursor.getString(4),
                        )
                    )
                }
            }
        }

    fun deleteInvoicePayment(
        invoice: CreditCardInvoiceRecord,
        paymentId: Long,
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            db.delete("account_movements", "invoice_payment_id = ?", arrayOf(paymentId.toString()))
            val deleted = db.delete(
                "invoice_payments",
                "id = ? AND account_id = ? AND closing_period = ?",
                arrayOf(paymentId.toString(), invoice.accountId.toString(), invoice.closingPeriod.toString()),
            ) == 1
            if (deleted) refreshInvoiceStatuses(db, invoice.accountId, LocalDate.now())
            if (deleted) db.setTransactionSuccessful()
            deleted
        } finally {
            db.endTransaction()
        }
    }

    fun accountMovements(accountId: Long): List<AccountMovementRecord> = readableDatabase.rawQuery(
        """SELECT movements.id,movements.direction,movements.type,movements.amount,
                  movements.occurred_at,movements.description,related.name
           FROM account_movements AS movements
           LEFT JOIN financial_accounts AS related ON related.id = movements.related_account_id
           WHERE movements.account_id = ? ORDER BY movements.occurred_at DESC,movements.id DESC""",
        arrayOf(accountId.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    AccountMovementRecord(
                        id = cursor.getLong(0),
                        direction = runCatching {
                            AccountMovementDirection.valueOf(cursor.getString(1))
                        }.getOrDefault(AccountMovementDirection.DEBIT),
                        type = runCatching { AccountMovementType.valueOf(cursor.getString(2)) }
                            .getOrDefault(AccountMovementType.CARD_PAYMENT),
                        amount = cursor.getString(3).toBigDecimal(),
                        occurredAt = LocalDate.parse(cursor.getString(4)),
                        description = cursor.getString(5),
                        relatedAccountName = cursor.getString(6),
                    )
                )
            }
        }
    }

    fun accountBalance(
        account: FinancialAccountRecord,
        throughDate: LocalDate? = null,
    ): AccountBalanceSummary {
        val fromDate = account.openingBalanceDate
        val transactions = readableDatabase.rawQuery(
            "SELECT direction,amount,occurred_at,status,due_date,planned_payment_date,paid_at " +
                "FROM transactions WHERE account_id = ?",
            arrayOf(account.id.toString()),
        ).use { cursor ->
            buildList {
            while (cursor.moveToNext()) {
                val originalDate = runCatching {
                    LocalDateTime.parse(cursor.getString(2)).toLocalDate()
                }.getOrNull() ?: continue
                val status = TransactionStatus.fromStored(cursor.getString(3))
                val effectiveStoredDate = if (status == TransactionStatus.REALIZED) {
                    cursor.getString(6)
                } else cursor.getString(5) ?: cursor.getString(4)
                val date = effectiveStoredDate?.let {
                    runCatching { LocalDate.parse(it) }.getOrNull()
                } ?: originalDate
                // O saldo informado representa o fechamento da data escolhida.
                if (fromDate != null && !date.isAfter(fromDate)) continue
                if (throughDate != null && date.isAfter(throughDate)) continue
                val amount = cursor.getString(1).toBigDecimalOrNull() ?: continue
                val direction = FinancialTransactionDirection.fromStored(cursor.getString(0))
                    ?: continue
                    add(AccountBalanceEntry(direction, amount, status))
                }
            }
        }
        val movements = accountMovements(account.id).filter { movement ->
            (fromDate == null || movement.occurredAt.isAfter(fromDate)) &&
                (throughDate == null || !movement.occurredAt.isAfter(throughDate))
        }
        return AccountBalanceCalculator.calculate(account.openingBalance, transactions, movements)
    }

    fun generalProjectedBalance(throughDate: LocalDate): java.math.BigDecimal {
        val accounts = financialAccounts()
        val bankBalance = accounts
            .filter {
                it.type == FinancialAccountType.BANK_ACCOUNT &&
                    (it.openingBalanceDate == null || !it.openingBalanceDate.isAfter(throughDate))
            }
            .fold(java.math.BigDecimal.ZERO) { total, account ->
                total + accountBalance(account, throughDate).projectedBalance
            }
        val invoiceAdjustment = accounts
            .filter { it.type == FinancialAccountType.CREDIT_CARD }
            .flatMap { creditCardInvoices(it.id) }
            .fold(java.math.BigDecimal.ZERO) { total, invoice ->
                val payments = invoicePayments(invoice).filter { !it.paidAt.isAfter(throughDate) }
                val paidThroughDate = payments.fold(java.math.BigDecimal.ZERO) { sum, payment ->
                    sum + payment.amount
                }
                val outstandingAtDate = (invoice.total - paidThroughDate)
                    .max(java.math.BigDecimal.ZERO)
                val dueOutstanding = if (
                    invoice.dueDate != null && !invoice.dueDate.isAfter(throughDate)
                ) outstandingAtDate else java.math.BigDecimal.ZERO
                val paymentsWithoutAccount = payments
                    .filter { it.sourceAccountId == null }
                    .fold(java.math.BigDecimal.ZERO) { sum, payment -> sum + payment.amount }
                total + dueOutstanding + paymentsWithoutAccount
            }
        return bankBalance - invoiceAdjustment
    }

    fun recordTransfer(
        sourceAccountId: Long,
        destinationAccountId: Long,
        amount: java.math.BigDecimal,
        occurredAt: LocalDate,
        description: String,
    ): Boolean {
        if (sourceAccountId == destinationAccountId || amount.signum() <= 0) return false
        val db = writableDatabase
        if (!isBankAccount(db, sourceAccountId) || !isBankAccount(db, destinationAccountId)) {
            return false
        }
        val normalizedDescription = description.trim().ifBlank { "Transferência entre contas" }
        val transferGroup = UUID.randomUUID().toString()
        db.beginTransaction()
        return try {
            fun insert(
                accountId: Long,
                relatedAccountId: Long,
                direction: AccountMovementDirection,
            ) = db.insertOrThrow(
                "account_movements",
                null,
                ContentValues().apply {
                    put("account_id", accountId)
                    put("type", AccountMovementType.TRANSFER.name)
                    put("direction", direction.name)
                    put("amount", amount.toPlainString())
                    put("occurred_at", occurredAt.toString())
                    put("description", normalizedDescription)
                    putNull("invoice_payment_id")
                    put("related_account_id", relatedAccountId)
                    put("transfer_group", transferGroup)
                },
            )
            insert(sourceAccountId, destinationAccountId, AccountMovementDirection.DEBIT)
            insert(destinationAccountId, sourceAccountId, AccountMovementDirection.CREDIT)
            db.setTransactionSuccessful()
            true
        } catch (_: Exception) {
            false
        } finally {
            db.endTransaction()
        }
    }

    fun recordManualTransaction(
        accountId: Long,
        direction: FinancialTransactionDirection,
        amount: java.math.BigDecimal,
        occurredAt: LocalDate,
        description: String,
        status: TransactionStatus,
        occurrences: Int = 1,
    ): Boolean {
        if (amount.signum() <= 0 || description.isBlank() || occurrences !in 1..120) return false
        val db = writableDatabase
        if (!isBankAccount(db, accountId)) return false
        val account = db.rawQuery(
            "SELECT name FROM financial_accounts WHERE id = ?",
            arrayOf(accountId.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else return false }
        val seriesId = if (occurrences > 1) UUID.randomUUID().toString() else null
        db.beginTransaction()
        return try {
            MonthlyRecurrencePlanner.plan(occurredAt, occurrences, status).forEach { occurrence ->
                val occurrenceDate = occurrence.date
                val occurrenceStatus = occurrence.status
                val inserted = db.insert(
                    "transactions",
                    null,
                    ContentValues().apply {
                        putNull("source_event_id")
                        put("direction", direction.name)
                        put(
                            "type",
                            if (direction == FinancialTransactionDirection.INCOME) {
                                FinancialTransactionType.MANUAL_INCOME.name
                            } else FinancialTransactionType.MANUAL_EXPENSE.name,
                        )
                        put("amount", amount.toPlainString())
                        put("occurred_at", occurrenceDate.atStartOfDay().toString())
                        put("description", description.trim())
                        put("source_package", "MANUAL")
                        put("category", TransactionCategory.UNCATEGORIZED.name)
                        put("category_source", TransactionCategorySource.DEFAULT.name)
                        putNull("rule_key")
                        put("origin", TransactionOrigin.MANUAL.name)
                        put("status", occurrenceStatus.name)
                        put("account", account)
                        put("account_id", accountId)
                        putNull("invoice_id")
                        put("due_date", occurrenceDate.toString())
                        if (occurrenceStatus == TransactionStatus.REALIZED) {
                            put("paid_at", occurrenceDate.toString())
                        }
                        if (seriesId != null) {
                            put("series_id", seriesId)
                            put("series_index", occurrence.index)
                            put("series_total", occurrences)
                        }
                        put("import_key", "MANUAL:${UUID.randomUUID()}")
                    },
                )
                if (inserted == -1L) error("Could not insert manual transaction series")
            }
            db.setTransactionSuccessful()
            true
        } catch (_: Exception) {
            false
        } finally {
            db.endTransaction()
        }
    }

    fun deleteManualTransaction(
        transactionId: Long,
        seriesScope: TransactionSeriesScope = TransactionSeriesScope.ONLY_THIS,
    ): Boolean {
        val db = writableDatabase
        val series = db.rawQuery(
            "SELECT series_id,series_index FROM transactions WHERE id = ? AND origin = ?",
            arrayOf(transactionId.toString(), TransactionOrigin.MANUAL.name),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return false
            cursor.getString(0) to if (cursor.isNull(1)) null else cursor.getInt(1)
        }
        val seriesId = series.first
        val selection: String
        val selectionArgs: Array<String>
        when {
            seriesId == null || seriesScope == TransactionSeriesScope.ONLY_THIS -> {
                selection = "id = ? AND origin = ?"
                selectionArgs = arrayOf(transactionId.toString(), TransactionOrigin.MANUAL.name)
            }
            seriesScope == TransactionSeriesScope.ALL -> {
                selection = "series_id = ? AND origin = ?"
                selectionArgs = arrayOf(seriesId, TransactionOrigin.MANUAL.name)
            }
            else -> {
                selection = "series_id = ? AND series_index >= ? AND origin = ?"
                selectionArgs = arrayOf(
                    seriesId,
                    checkNotNull(series.second).toString(),
                    TransactionOrigin.MANUAL.name,
                )
            }
        }
        val rows = db.query(
            "transactions", null, selection, selectionArgs, null, null, "id",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursorRowToJson(cursor).toString())
            }
        }
        if (rows.isEmpty()) return false
        val description = JSONObject(rows.first()).optString("description", "Movimentação")
        val groupId = UUID.randomUUID().toString()
        db.beginTransaction()
        return try {
            db.insertOrThrow(
                "deleted_transaction_groups", null,
                ContentValues().apply {
                    put("group_id", groupId)
                    put("description", description)
                    put("item_count", rows.size)
                    put("deleted_at", System.currentTimeMillis())
                },
            )
            rows.forEach { row ->
                db.insertOrThrow(
                    "deleted_transactions", null,
                    ContentValues().apply {
                        put("group_id", groupId)
                        put("row_json", row)
                    },
                )
            }
            val deleted = db.delete("transactions", selection, selectionArgs)
            if (deleted != rows.size) error("Delete count mismatch")
            db.setTransactionSuccessful()
            true
        } catch (_: Exception) {
            false
        } finally {
            db.endTransaction()
        }
    }

    fun deletedTransactionGroups(): List<DeletedTransactionGroup> = readableDatabase.rawQuery(
        """SELECT group_id,description,item_count,deleted_at
           FROM deleted_transaction_groups ORDER BY deleted_at DESC""",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    DeletedTransactionGroup(
                        groupId = cursor.getString(0),
                        description = cursor.getString(1),
                        itemCount = cursor.getInt(2),
                        deletedAt = java.time.Instant.ofEpochMilli(cursor.getLong(3)),
                    )
                )
            }
        }
    }

    fun restoreDeletedTransactionGroup(groupId: String): Boolean {
        val db = writableDatabase
        val rows = db.rawQuery(
            "SELECT row_json FROM deleted_transactions WHERE group_id = ? ORDER BY id",
            arrayOf(groupId),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(JSONObject(cursor.getString(0))) }
        }
        if (rows.isEmpty()) return false
        val columns = tableColumns(db, "transactions")
        db.beginTransaction()
        return try {
            rows.forEach { row ->
                db.insertOrThrow("transactions", null, jsonToContentValues(row, columns))
            }
            db.delete("deleted_transactions", "group_id = ?", arrayOf(groupId))
            db.delete("deleted_transaction_groups", "group_id = ?", arrayOf(groupId))
            db.setTransactionSuccessful()
            true
        } catch (_: Exception) {
            false
        } finally {
            db.endTransaction()
        }
    }

    fun permanentlyDeleteTransactionGroup(groupId: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            db.delete("deleted_transactions", "group_id = ?", arrayOf(groupId))
            val deleted = db.delete(
                "deleted_transaction_groups", "group_id = ?", arrayOf(groupId),
            ) == 1
            if (deleted) db.setTransactionSuccessful()
            deleted
        } finally {
            db.endTransaction()
        }
    }

    fun createBackupJson(): String {
        val db = readableDatabase
        val tables = JSONObject()
        BACKUP_TABLES.forEach { table ->
            val rows = JSONArray()
            db.query(table, null, null, null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) rows.put(cursorRowToJson(cursor))
            }
            tables.put(table, rows)
        }
        return JSONObject()
            .put("format", BACKUP_FORMAT_VERSION)
            .put("databaseVersion", DATABASE_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("tables", tables)
            .toString()
    }

    fun previewBackup(content: String): BackupValidationResult = runCatching {
        val root = validatedBackup(content)
        val tables = root.getJSONObject("tables")
        BackupValidationResult.Valid(
            BackupPreview(
                createdAt = java.time.Instant.ofEpochMilli(root.getLong("createdAt")),
                databaseVersion = root.getInt("databaseVersion"),
                tableCount = BACKUP_TABLES.size,
                transactionCount = tables.getJSONArray("transactions").length(),
                accountCount = tables.getJSONArray("financial_accounts").length(),
            )
        )
    }.getOrElse { BackupValidationResult.Invalid(it.message ?: "Backup inválido") }

    fun restoreBackup(content: String): Boolean {
        val root = runCatching { validatedBackup(content) }.getOrNull() ?: return false
        val tables = root.getJSONObject("tables")
        val db = writableDatabase
        db.beginTransaction()
        return try {
            BACKUP_TABLES.asReversed().forEach { table -> db.delete(table, null, null) }
            BACKUP_TABLES.forEach { table ->
                val columns = tableColumns(db, table)
                val rows = tables.optJSONArray(table) ?: JSONArray()
                for (index in 0 until rows.length()) {
                    db.insertOrThrow(
                        table,
                        null,
                        jsonToContentValues(rows.getJSONObject(index), columns),
                    )
                }
            }
            db.setTransactionSuccessful()
            true
        } catch (_: Exception) {
            false
        } finally {
            db.endTransaction()
        }
    }

    fun exportTransactionsCsv(): String =
        TransactionCsvExporter.export(recentTransactions(10_000))

    fun monthlyBudgets(period: YearMonth): List<MonthlyBudgetRecord> = readableDatabase.rawQuery(
        """SELECT category_key,amount FROM monthly_budgets
           WHERE period = ? ORDER BY category_key""",
        arrayOf(period.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val storedKey = cursor.getString(0)
                val customCategory = storedKey
                    .takeIf { it.startsWith(CUSTOM_BUDGET_PREFIX) }
                    ?.removePrefix(CUSTOM_BUDGET_PREFIX)
                val category = storedKey
                    .takeUnless { it == TOTAL_BUDGET_KEY || customCategory != null }
                    ?.let(TransactionCategory::fromStored)
                val amount = cursor.getString(1).toBigDecimalOrNull() ?: continue
                add(MonthlyBudgetRecord(period, category, amount, customCategory))
            }
        }
    }

    fun saveMonthlyBudget(
        period: YearMonth,
        category: TransactionCategory?,
        amount: BigDecimal,
        customCategory: String? = null,
    ): Boolean {
        if (amount.signum() <= 0) return false
        return writableDatabase.insertWithOnConflict(
            "monthly_budgets",
            null,
            ContentValues().apply {
                put("period", period.toString())
                put(
                    "category_key",
                    customCategory?.let { CUSTOM_BUDGET_PREFIX + it.trim() }
                        ?: category?.name ?: TOTAL_BUDGET_KEY,
                )
                put("amount", amount.toPlainString())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        ) != -1L
    }

    fun deleteMonthlyBudget(
        period: YearMonth,
        category: TransactionCategory?,
        customCategory: String? = null,
    ): Boolean =
        writableDatabase.delete(
            "monthly_budgets",
            "period = ? AND category_key = ?",
            arrayOf(
                period.toString(),
                customCategory?.let { CUSTOM_BUDGET_PREFIX + it.trim() }
                    ?: category?.name ?: TOTAL_BUDGET_KEY,
            ),
        ) == 1

    fun copyMonthlyBudgets(source: YearMonth, target: YearMonth): Int {
        val sourceBudgets = monthlyBudgets(source)
        if (sourceBudgets.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        return try {
            db.delete("monthly_budgets", "period = ?", arrayOf(target.toString()))
            sourceBudgets.forEach { budget ->
                db.insertOrThrow(
                    "monthly_budgets",
                    null,
                    ContentValues().apply {
                        put("period", target.toString())
                        put(
                            "category_key",
                            budget.customCategory?.let { CUSTOM_BUDGET_PREFIX + it }
                                ?: budget.category?.name ?: TOTAL_BUDGET_KEY,
                        )
                        put("amount", budget.amount.toPlainString())
                    },
                )
            }
            db.setTransactionSuccessful()
            sourceBudgets.size
        } catch (_: Exception) {
            0
        } finally {
            db.endTransaction()
        }
    }

    fun deleteTransfer(movementId: Long): Boolean {
        val db = writableDatabase
        val transferGroup = db.rawQuery(
            "SELECT transfer_group FROM account_movements WHERE id = ? AND type = ?",
            arrayOf(movementId.toString(), AccountMovementType.TRANSFER.name),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return false
            cursor.getString(0) ?: return false
        }
        db.beginTransaction()
        return try {
            val deleted = db.delete(
                "account_movements",
                "transfer_group = ?",
                arrayOf(transferGroup),
            )
            if (deleted == 2) db.setTransactionSuccessful()
            deleted == 2
        } finally {
            db.endTransaction()
        }
    }

    fun invoiceTransactions(invoiceId: Long): List<FinancialTransactionRecord> =
        readableDatabase.rawQuery(
            """SELECT id,source_event_id,direction,type,amount,occurred_at,description,source_package,
                      category,category_source,rule_key,origin,status,account,original_category,
                      original_status,account_id,invoice_id,original_amount,due_date,
                      planned_payment_date,paid_at,custom_category,subcategory
               FROM transactions WHERE invoice_id = ?
               ORDER BY occurred_at DESC,id DESC""",
            arrayOf(invoiceId.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val direction = FinancialTransactionDirection.fromStored(cursor.getString(2))
                        ?: continue
                    val type = FinancialTransactionType.fromStored(cursor.getString(3)) ?: continue
                    add(
                        FinancialTransactionRecord(
                            id = cursor.getLong(0),
                            sourceEventId = if (cursor.isNull(1)) null else cursor.getLong(1),
                            direction = direction,
                            type = type,
                            amount = cursor.getString(4),
                            occurredAt = cursor.getString(5),
                            description = cursor.getString(6),
                            sourcePackage = cursor.getString(7),
                            category = TransactionCategory.fromStored(cursor.getString(8)),
                            categorySource = TransactionCategorySource.fromStored(cursor.getString(9)),
                            ruleKey = cursor.getString(10),
                            origin = TransactionOrigin.fromStored(cursor.getString(11)),
                            status = TransactionStatus.fromStored(cursor.getString(12)),
                            account = cursor.getString(13),
                            originalCategory = cursor.getString(14),
                            originalStatus = cursor.getString(15),
                            accountId = if (cursor.isNull(16)) null else cursor.getLong(16),
                            invoiceId = if (cursor.isNull(17)) null else cursor.getLong(17),
                            originalAmount = cursor.getString(18),
                            dueDate = cursor.getString(19),
                            plannedPaymentDate = cursor.getString(20),
                            paidAt = cursor.getString(21),
                            customCategory = cursor.getString(22),
                            subcategory = cursor.getString(23),
                        )
                    )
                }
            }
        }

    fun markExistingTransactions(preview: MobillsImportPreview): MobillsImportPreview {
        val existing = readableDatabase.rawQuery(
            "SELECT direction,amount,occurred_at,description FROM transactions",
            null,
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    val direction = FinancialTransactionDirection.fromStored(cursor.getString(0))
                        ?: continue
                    val date = runCatching {
                        LocalDateTime.parse(cursor.getString(2)).toLocalDate()
                    }.getOrNull() ?: continue
                    val amount = cursor.getString(1).toBigDecimalOrNull() ?: continue
                    add(existingMatchKey(direction, amount.toPlainString(), date.toString(), cursor.getString(3)))
                }
            }
        }
        return MobillsImportPreview(
            preview.rows.map { row ->
                if (
                    row.disposition != ImportDisposition.REJECTED &&
                    row.direction != null && row.amount != null && row.date != null &&
                    existingMatchKey(
                        row.direction,
                        row.amount.toPlainString(),
                        row.date.toString(),
                        row.description,
                    ) in existing
                ) {
                    row.copy(disposition = ImportDisposition.POSSIBLE_DUPLICATE)
                } else row
            }
        )
    }

    private fun existingMatchKey(
        direction: FinancialTransactionDirection,
        amount: String,
        date: String,
        description: String,
    ): String = listOf(
        direction.name,
        amount.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString().orEmpty(),
        date,
        description.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), ""),
    ).joinToString("|")

    fun clearEvents() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("transactions", "source_event_id IS NOT NULL", null)
            db.delete("events", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun reclassifyExistingEvents(db: SQLiteDatabase) {
        val events = db.rawQuery(
            "SELECT id,package_name,app_label,title,body FROM events",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        StoredEvent(
                            id = cursor.getLong(0),
                            packageName = cursor.getString(1),
                            appLabel = cursor.getString(2),
                            title = cursor.getString(3),
                            body = cursor.getString(4),
                        )
                    )
                }
            }
        }

        events.forEach { event ->
            val result = FinancialNotificationClassifier.classify(
                event.packageName,
                event.appLabel,
                event.title,
                event.body,
            )
            val transaction = result.transaction
            db.update(
                "events",
                ContentValues().apply {
                    put(
                        "parsed",
                        if (result.classification == NotificationClassification.TRANSACTION) 1 else 0,
                    )
                    put("classification", result.classification.name)
                    put("classification_reason", result.reason)
                    put("transaction_type", transaction?.type?.name)
                    put("occurred_at", transaction?.occurredAt?.toString())
                    put("card_last_four", transaction?.cardLastFour)
                    put("amount", transaction?.amount?.toPlainString())
                    put("merchant", transaction?.merchant)
                },
                "id = ?",
                arrayOf(event.id.toString()),
            )
        }
    }

    private fun rebuildTransactionsFromEvents(db: SQLiteDatabase) {
        db.rawQuery(
            """SELECT id,package_name,transaction_type,amount,occurred_at,merchant,card_last_four
               FROM events
               WHERE classification = 'TRANSACTION'
                 AND transaction_type IS NOT NULL
                 AND amount IS NOT NULL
                 AND occurred_at IS NOT NULL""",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val type = FinancialTransactionType.fromStored(cursor.getString(2)) ?: continue
                val merchant = cursor.getString(5)
                insertTransaction(
                    db = db,
                    sourceEventId = cursor.getLong(0),
                    sourcePackage = cursor.getString(1),
                    type = type,
                    amount = cursor.getString(3),
                    occurredAt = cursor.getString(4),
                    description = transactionDescription(type, merchant),
                    merchant = merchant,
                    cardLastFour = cursor.getString(6),
                )
            }
        }
    }

    private fun insertTransaction(
        db: SQLiteDatabase,
        sourceEventId: Long,
        sourcePackage: String,
        type: FinancialTransactionType,
        amount: String,
        occurredAt: String,
        description: String,
        merchant: String?,
        cardLastFour: String? = null,
    ) {
        val ruleKey = if (type == FinancialTransactionType.CARD_PURCHASE) {
            TransactionCategoryRule.normalizeMerchant(merchant)
        } else {
            null
        }
        val ruleCategory = ruleKey?.let {
            findCategoryRule(db, it, type.direction)
        }
        val matchedAccount = if (type == FinancialTransactionType.CARD_PURCHASE) {
            findAccountForCardLastFour(db, cardLastFour)
        } else null
        val transactionId = db.insertWithOnConflict(
            "transactions",
            null,
            ContentValues().apply {
                put("source_event_id", sourceEventId)
                put("direction", type.direction.name)
                put("type", type.name)
                put("amount", amount)
                put("occurred_at", occurredAt)
                put("description", description)
                put("source_package", sourcePackage)
                matchedAccount?.let { account ->
                    put("account", account.name)
                    put("account_id", account.id)
                }
                put(
                    "category",
                    (ruleCategory ?: TransactionCategory.UNCATEGORIZED).name,
                )
                put(
                    "category_source",
                    if (ruleCategory == null) {
                        TransactionCategorySource.DEFAULT.name
                    } else {
                        TransactionCategorySource.RULE.name
                    },
                )
                put("rule_key", ruleKey)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        if (transactionId != -1L && matchedAccount != null) {
            rebuildCreditCardInvoices(db, matchedAccount.id)
        }
    }

    private fun findAccountForCardLastFour(
        db: SQLiteDatabase,
        lastFour: String?,
    ): FinancialAccountRecord? = db.rawQuery(
        """SELECT id,name,type,closing_day,due_day,is_default,card_identifiers
           FROM financial_accounts WHERE type = ?""",
        arrayOf(FinancialAccountType.CREDIT_CARD.name),
    ).use { cursor ->
        while (cursor.moveToNext()) {
            val account = FinancialAccountRecord(
                id = cursor.getLong(0),
                name = cursor.getString(1),
                type = FinancialAccountType.fromStored(cursor.getString(2)),
                closingDay = if (cursor.isNull(3)) null else cursor.getInt(3),
                dueDay = if (cursor.isNull(4)) null else cursor.getInt(4),
                isDefault = cursor.getInt(5) == 1,
                cardIdentifiers = cursor.getString(6),
            )
            if (account.matchesCardLastFour(lastFour)) return@use account
        }
        null
    }

    private fun findCategoryRule(
        db: SQLiteDatabase,
        ruleKey: String,
        direction: FinancialTransactionDirection,
    ): TransactionCategory? = db.rawQuery(
        "SELECT category FROM category_rules WHERE rule_key = ? AND direction = ?",
        arrayOf(ruleKey, direction.name),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        TransactionCategory.fromStored(cursor.getString(0)).takeIf { it.supports(direction) }
    }

    private fun initializeCategoryMetadata(db: SQLiteDatabase) {
        db.execSQL(
            """UPDATE transactions
               SET category_source = 'MANUAL'
               WHERE category <> 'UNCATEGORIZED'"""
        )
        db.rawQuery(
            """SELECT transactions.id,events.merchant
               FROM transactions
               INNER JOIN events ON events.id = transactions.source_event_id
               WHERE transactions.type = 'CARD_PURCHASE'""",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val ruleKey = TransactionCategoryRule.normalizeMerchant(cursor.getString(1))
                    ?: continue
                db.update(
                    "transactions",
                    ContentValues().apply { put("rule_key", ruleKey) },
                    "id = ?",
                    arrayOf(cursor.getLong(0).toString()),
                )
            }
        }
    }

    private fun transactionDescription(
        type: FinancialTransactionType,
        merchant: String?,
    ): String = when (type) {
        FinancialTransactionType.CARD_PURCHASE ->
            merchant?.takeIf { it.isNotBlank() } ?: "Compra no cartão"
        FinancialTransactionType.PIX_RECEIVED -> "PIX recebido"
        FinancialTransactionType.IMPORTED_EXPENSE -> "Despesa importada"
        FinancialTransactionType.IMPORTED_INCOME -> "Receita importada"
        FinancialTransactionType.MANUAL_EXPENSE -> "Despesa manual"
        FinancialTransactionType.MANUAL_INCOME -> "Receita manual"
    }

    private fun migrateTransactionsForImports(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE transactions RENAME TO transactions_before_import")
        createTransactionsTable(db)
        db.execSQL(
            """INSERT INTO transactions(
                id,source_event_id,direction,type,amount,occurred_at,description,source_package,
                category,category_source,rule_key,origin,status
            )
            SELECT id,source_event_id,direction,type,amount,occurred_at,description,source_package,
                   category,category_source,rule_key,'NOTIFICATION','REALIZED'
            FROM transactions_before_import"""
        )
        db.execSQL("DROP TABLE transactions_before_import")
        createIndexes(db)
    }

    private fun createTransactionsTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS transactions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_event_id INTEGER UNIQUE,
                direction TEXT NOT NULL,
                type TEXT NOT NULL,
                amount TEXT NOT NULL,
                occurred_at TEXT NOT NULL,
                description TEXT NOT NULL,
                source_package TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT 'UNCATEGORIZED',
                category_source TEXT NOT NULL DEFAULT 'DEFAULT',
                rule_key TEXT,
                origin TEXT NOT NULL DEFAULT 'NOTIFICATION',
                status TEXT NOT NULL DEFAULT 'REALIZED',
                account TEXT,
                original_category TEXT,
                original_status TEXT,
                account_id INTEGER,
                invoice_id INTEGER,
                original_amount TEXT,
                due_date TEXT,
                planned_payment_date TEXT,
                paid_at TEXT,
                series_id TEXT,
                series_index INTEGER,
                series_total INTEGER,
                custom_category TEXT,
                subcategory TEXT,
                import_key TEXT UNIQUE
            )"""
        )
    }

    private fun createCategoryRulesTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS category_rules(
                rule_key TEXT NOT NULL,
                direction TEXT NOT NULL,
                category TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(rule_key, direction)
            )"""
        )
    }

    private fun createFinancialAccountsTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS financial_accounts(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                normalized_name TEXT NOT NULL UNIQUE,
                type TEXT NOT NULL,
                closing_day INTEGER,
                due_day INTEGER,
                is_default INTEGER NOT NULL DEFAULT 0,
                card_identifiers TEXT,
                opening_balance TEXT NOT NULL DEFAULT '0',
                opening_balance_date TEXT
            )"""
        )
    }

    private fun createCreditCardInvoicesTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS credit_card_invoices(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id INTEGER NOT NULL,
                closing_period TEXT NOT NULL,
                closing_date TEXT NOT NULL,
                due_date TEXT,
                status TEXT NOT NULL,
                UNIQUE(account_id,closing_period)
            )"""
        )
    }

    private fun createInvoicePaymentsTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS invoice_payments(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id INTEGER NOT NULL,
                closing_period TEXT NOT NULL,
                amount TEXT NOT NULL,
                paid_at TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                source_account_id INTEGER
            )"""
        )
    }

    private fun createInvoiceAdjustmentsTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS invoice_adjustments(
                account_id INTEGER NOT NULL,
                closing_period TEXT NOT NULL,
                amount TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(account_id,closing_period)
            )"""
        )
    }

    private fun createAccountMovementsTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS account_movements(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id INTEGER NOT NULL,
                type TEXT NOT NULL,
                amount TEXT NOT NULL,
                occurred_at TEXT NOT NULL,
                description TEXT NOT NULL,
                invoice_payment_id INTEGER UNIQUE,
                direction TEXT NOT NULL DEFAULT 'DEBIT',
                related_account_id INTEGER,
                transfer_group TEXT
            )"""
        )
    }

    private fun createDeletedTransactionsTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS deleted_transaction_groups(
                group_id TEXT PRIMARY KEY,
                description TEXT NOT NULL,
                item_count INTEGER NOT NULL,
                deleted_at INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS deleted_transactions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                group_id TEXT NOT NULL,
                row_json TEXT NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE INDEX IF NOT EXISTS idx_deleted_transactions_group
               ON deleted_transactions(group_id)"""
        )
    }

    private fun createMonthlyBudgetsTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS monthly_budgets(
                period TEXT NOT NULL,
                category_key TEXT NOT NULL,
                amount TEXT NOT NULL,
                PRIMARY KEY(period,category_key)
            )"""
        )
    }

    private fun insertPaymentAccountMovement(
        db: SQLiteDatabase,
        paymentId: Long,
        sourceAccountId: Long,
        amount: java.math.BigDecimal,
        paidAt: LocalDate,
        cardAccountId: Long,
    ) {
        val cardName = db.rawQuery(
            "SELECT name FROM financial_accounts WHERE id = ?",
            arrayOf(cardAccountId.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "cartão" }
        db.insertOrThrow(
            "account_movements",
            null,
            ContentValues().apply {
                put("account_id", sourceAccountId)
                put("type", "CARD_PAYMENT")
                put("direction", AccountMovementDirection.DEBIT.name)
                put("amount", amount.toPlainString())
                put("occurred_at", paidAt.toString())
                put("description", "Pagamento da fatura $cardName")
                put("invoice_payment_id", paymentId)
                put("related_account_id", cardAccountId)
            },
        )
    }

    private fun rebuildPaymentAccountMovements(db: SQLiteDatabase) {
        db.rawQuery(
            """SELECT id,source_account_id,amount,paid_at,account_id FROM invoice_payments
               WHERE source_account_id IS NOT NULL""",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                db.insertWithOnConflict(
                    "account_movements",
                    null,
                    ContentValues().apply {
                        val paymentId = cursor.getLong(0)
                        val sourceAccountId = cursor.getLong(1)
                        val amount = cursor.getString(2)
                        val paidAt = cursor.getString(3)
                        val cardAccountId = cursor.getLong(4)
                        val cardName = db.rawQuery(
                            "SELECT name FROM financial_accounts WHERE id = ?",
                            arrayOf(cardAccountId.toString()),
                        ).use { nameCursor ->
                            if (nameCursor.moveToFirst()) nameCursor.getString(0) else "cartão"
                        }
                        put("account_id", sourceAccountId)
                        put("type", "CARD_PAYMENT")
                        put("direction", AccountMovementDirection.DEBIT.name)
                        put("amount", amount)
                        put("occurred_at", paidAt)
                        put("description", "Pagamento da fatura $cardName")
                        put("invoice_payment_id", paymentId)
                        put("related_account_id", cardAccountId)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
        }
    }

    private fun isBankAccount(db: SQLiteDatabase, accountId: Long): Boolean = db.rawQuery(
        "SELECT type FROM financial_accounts WHERE id = ?",
        arrayOf(accountId.toString()),
    ).use { cursor ->
        cursor.moveToFirst() &&
            FinancialAccountType.fromStored(cursor.getString(0)) == FinancialAccountType.BANK_ACCOUNT
    }

    private fun initializeFinancialAccounts(db: SQLiteDatabase) {
        db.rawQuery(
            "SELECT DISTINCT account FROM transactions WHERE account IS NOT NULL AND TRIM(account) <> ''",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) ensureFinancialAccount(db, cursor.getString(0))
        }
        db.rawQuery("SELECT id,normalized_name FROM financial_accounts", null).use { cursor ->
            while (cursor.moveToNext()) {
                linkTransactionsToAccount(db, cursor.getLong(0), cursor.getString(1))
            }
        }
    }

    private fun ensureFinancialAccount(db: SQLiteDatabase, name: String): Long {
        val trimmed = name.trim().ifBlank { "Sem conta" }
        val normalized = FinancialAccountIdentity.normalize(trimmed)
        db.rawQuery(
            "SELECT id FROM financial_accounts WHERE normalized_name = ?",
            arrayOf(normalized),
        ).use { cursor -> if (cursor.moveToFirst()) return cursor.getLong(0) }

        val preset = knownAccountPreset(normalized)
        return db.insertOrThrow(
            "financial_accounts",
            null,
            ContentValues().apply {
                put("name", trimmed)
                put("normalized_name", normalized)
                put("type", (preset?.type ?: FinancialAccountIdentity.inferredType(trimmed)).name)
                preset?.closingDay?.let { put("closing_day", it) }
                preset?.dueDay?.let { put("due_day", it) }
                put("is_default", if (preset?.isDefault == true) 1 else 0)
                preset?.cardIdentifiers?.let { put("card_identifiers", it) }
            },
        )
    }

    private fun initializeCardIdentifiers(db: SQLiteDatabase) {
        listOf(
            "CINZA" to "6426,5253",
            "PRETO" to "3409,6101",
            "VERMELHO" to "7107,7691",
        ).forEach { (normalizedName, identifiers) ->
            db.update(
                "financial_accounts",
                ContentValues().apply { put("card_identifiers", identifiers) },
                "normalized_name = ? AND card_identifiers IS NULL",
                arrayOf(normalizedName),
            )
        }
    }

    private fun linkNotificationTransactionsToAccounts(db: SQLiteDatabase) {
        db.rawQuery(
            """SELECT transactions.id,events.card_last_four
               FROM transactions
               INNER JOIN events ON events.id = transactions.source_event_id
               WHERE transactions.type = ? AND transactions.account_id IS NULL""",
            arrayOf(FinancialTransactionType.CARD_PURCHASE.name),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val account = findAccountForCardLastFour(db, cursor.getString(1)) ?: continue
                db.update(
                    "transactions",
                    ContentValues().apply {
                        put("account", account.name)
                        put("account_id", account.id)
                    },
                    "id = ?",
                    arrayOf(cursor.getLong(0).toString()),
                )
            }
        }
    }

    private fun rebuildAllCreditCardInvoices(db: SQLiteDatabase) {
        db.rawQuery(
            "SELECT id FROM financial_accounts WHERE type = ?",
            arrayOf(FinancialAccountType.CREDIT_CARD.name),
        ).use { cursor ->
            while (cursor.moveToNext()) rebuildCreditCardInvoices(db, cursor.getLong(0))
        }
    }

    private fun rebuildCreditCardInvoices(db: SQLiteDatabase, accountId: Long) {
        val account = db.rawQuery(
            "SELECT type,closing_day,due_day FROM financial_accounts WHERE id = ?",
            arrayOf(accountId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return
            Triple(
                FinancialAccountType.fromStored(cursor.getString(0)),
                if (cursor.isNull(1)) null else cursor.getInt(1),
                if (cursor.isNull(2)) null else cursor.getInt(2),
            )
        }
        db.update(
            "transactions",
            ContentValues().apply { putNull("invoice_id") },
            "account_id = ?",
            arrayOf(accountId.toString()),
        )
        db.delete("credit_card_invoices", "account_id = ?", arrayOf(accountId.toString()))
        if (account.first != FinancialAccountType.CREDIT_CARD || account.second == null) return

        db.rawQuery(
            """SELECT id,occurred_at,origin FROM transactions
               WHERE account_id = ?""",
            arrayOf(accountId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val purchaseDate = runCatching {
                    LocalDateTime.parse(cursor.getString(1)).toLocalDate()
                }.getOrNull() ?: continue
                val origin = TransactionOrigin.fromStored(cursor.getString(2))
                val dates = if (origin == TransactionOrigin.MOBILLS) {
                    CreditCardBillingCycle.fromImportedInvoiceDate(
                        invoiceDate = purchaseDate,
                        closingDay = account.second!!,
                        configuredDueDay = account.third,
                    )
                } else {
                    CreditCardBillingCycle.calculate(
                        purchaseDate = purchaseDate,
                        closingDay = account.second!!,
                        dueDay = account.third,
                    )
                }
                val status = CreditCardBillingCycle.status(dates.closingDate, LocalDate.now())
                val invoiceId = db.insertWithOnConflict(
                    "credit_card_invoices",
                    null,
                    ContentValues().apply {
                        put("account_id", accountId)
                        put("closing_period", dates.closingPeriod.toString())
                        put("closing_date", dates.closingDate.toString())
                        put("due_date", dates.dueDate?.toString())
                        put("status", status.name)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                ).takeIf { it != -1L } ?: db.rawQuery(
                    """SELECT id FROM credit_card_invoices
                       WHERE account_id = ? AND closing_period = ?""",
                    arrayOf(accountId.toString(), dates.closingPeriod.toString()),
                ).use { invoiceCursor ->
                    check(invoiceCursor.moveToFirst())
                    invoiceCursor.getLong(0)
                }
                if (dates.dueDate != null) {
                    db.update(
                        "credit_card_invoices",
                        ContentValues().apply { put("due_date", dates.dueDate.toString()) },
                        "id = ?",
                        arrayOf(invoiceId.toString()),
                    )
                }
                db.update(
                    "transactions",
                    ContentValues().apply { put("invoice_id", invoiceId) },
                    "id = ?",
                    arrayOf(cursor.getLong(0).toString()),
                )
            }
        }
    }

    private fun refreshInvoiceStatuses(
        db: SQLiteDatabase,
        accountId: Long,
        today: LocalDate,
    ) {
        db.rawQuery(
            """SELECT invoices.id,invoices.closing_date,invoices.due_date,
                      COALESCE(SUM(CASE WHEN transactions.direction = 'EXPENSE'
                           THEN transactions.amount ELSE -transactions.amount END),0),
                      COALESCE((SELECT adjustments.amount FROM invoice_adjustments AS adjustments
                                WHERE adjustments.account_id = invoices.account_id
                                  AND adjustments.closing_period = invoices.closing_period),0),
                      COALESCE((SELECT SUM(payments.amount) FROM invoice_payments AS payments
                                WHERE payments.account_id = invoices.account_id
                                  AND payments.closing_period = invoices.closing_period),0)
               FROM credit_card_invoices AS invoices
               LEFT JOIN transactions ON transactions.invoice_id = invoices.id
               WHERE invoices.account_id = ?
               GROUP BY invoices.id""",
            arrayOf(accountId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val closingDate = LocalDate.parse(cursor.getString(1))
                val dueDate = cursor.getString(2)?.let(LocalDate::parse)
                val total = cursor.getString(3).toBigDecimal() + cursor.getString(4).toBigDecimal()
                val paidAmount = cursor.getString(5).toBigDecimal()
                db.update(
                    "credit_card_invoices",
                    ContentValues().apply {
                        put(
                            "status",
                            CreditCardBillingCycle.paymentStatus(
                                total, paidAmount, closingDate, dueDate, today,
                            ).name,
                        )
                    },
                    "id = ?",
                    arrayOf(cursor.getLong(0).toString()),
                )
            }
        }
    }

    private fun linkTransactionsToAccount(
        db: SQLiteDatabase,
        accountId: Long,
        normalizedName: String,
    ) {
        db.rawQuery(
            "SELECT id,account FROM transactions WHERE account IS NOT NULL",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (FinancialAccountIdentity.normalize(cursor.getString(1)) == normalizedName) {
                    db.update(
                        "transactions",
                        ContentValues().apply { put("account_id", accountId) },
                        "id = ?",
                        arrayOf(cursor.getLong(0).toString()),
                    )
                }
            }
        }
    }

    private fun knownAccountPreset(normalizedName: String): KnownAccountPreset? = when (normalizedName) {
        "CINZA" -> KnownAccountPreset(FinancialAccountType.CREDIT_CARD, 26, 5, true, "6426,5253")
        "VERMELHO" -> KnownAccountPreset(FinancialAccountType.CREDIT_CARD, 11, null, false, "7107,7691")
        "PRETO" -> KnownAccountPreset(FinancialAccountType.CREDIT_CARD, 8, null, false, "3409,6101")
        "CARREFOUR" -> KnownAccountPreset(FinancialAccountType.CREDIT_CARD, 20, null, false, null)
        else -> null
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE UNIQUE INDEX IF NOT EXISTS idx_events_fingerprint
               ON events(fingerprint) WHERE fingerprint IS NOT NULL"""
        )
        db.execSQL(
            """CREATE INDEX IF NOT EXISTS idx_transactions_occurred_at
               ON transactions(occurred_at DESC)"""
        )
        db.execSQL(
            """CREATE INDEX IF NOT EXISTS idx_transactions_series
               ON transactions(series_id,series_index) WHERE series_id IS NOT NULL"""
        )
        db.execSQL(
            """CREATE INDEX IF NOT EXISTS idx_transactions_status_dates
               ON transactions(status,planned_payment_date,due_date,paid_at)"""
        )
        db.execSQL(
            """CREATE INDEX IF NOT EXISTS idx_transactions_account
               ON transactions(account_id,occurred_at DESC)"""
        )
        db.execSQL(
            """CREATE INDEX IF NOT EXISTS idx_transactions_invoice
               ON transactions(invoice_id) WHERE invoice_id IS NOT NULL"""
        )
        db.execSQL(
            """CREATE INDEX IF NOT EXISTS idx_invoices_account_due
               ON credit_card_invoices(account_id,due_date DESC)"""
        )
        db.execSQL(
            """CREATE INDEX IF NOT EXISTS idx_movements_account_date
               ON account_movements(account_id,occurred_at DESC)"""
        )
    }

    private fun initializeImportedInstallmentSeries(db: SQLiteDatabase) {
        data class Candidate(
            val id: Long,
            val key: String,
            val index: Int,
            val total: Int,
        )

        val candidates = db.rawQuery(
            """SELECT id,description,direction,amount,account_id,account
               FROM transactions
               WHERE origin = ? AND series_id IS NULL""",
            arrayOf(TransactionOrigin.MOBILLS.name),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val installment = ImportedInstallmentParser.parse(cursor.getString(1))
                        ?: continue
                    val accountKey = if (cursor.isNull(4)) cursor.getString(5).orEmpty()
                    else cursor.getLong(4).toString()
                    val key = listOf(
                        accountKey,
                        cursor.getString(2),
                        cursor.getString(3),
                        installment.baseDescription.lowercase(Locale.ROOT),
                        installment.total.toString(),
                    ).joinToString("|")
                    add(Candidate(cursor.getLong(0), key, installment.index, installment.total))
                }
            }
        }

        candidates.groupBy { it.key }.values.forEach { group ->
            if (group.size < 2 || group.map { it.index }.distinct().size != group.size) {
                return@forEach
            }
            val seriesId = UUID.randomUUID().toString()
            group.forEach { candidate ->
                db.update(
                    "transactions",
                    ContentValues().apply {
                        put("series_id", seriesId)
                        put("series_index", candidate.index)
                        put("series_total", candidate.total)
                    },
                    "id = ?",
                    arrayOf(candidate.id.toString()),
                )
            }
        }
    }

    private fun validatedBackup(content: String): JSONObject {
        require(content.length <= MAX_BACKUP_CHARACTERS) { "Arquivo de backup muito grande" }
        val root = JSONObject(content)
        require(root.optInt("format", -1) == BACKUP_FORMAT_VERSION) {
            "Formato de backup incompatível"
        }
        val version = root.optInt("databaseVersion", -1)
        require(version in 1..DATABASE_VERSION) { "Versão de banco incompatível" }
        require(root.optLong("createdAt", -1L) > 0L) { "Data do backup inválida" }
        val tables = root.optJSONObject("tables") ?: error("Tabelas ausentes")
        BackupTableCompatibility.requiredTables(version, BACKUP_TABLES).forEach { table ->
            require(tables.optJSONArray(table) != null) { "Tabela ausente: $table" }
        }
        return root
    }

    private fun cursorRowToJson(cursor: Cursor): JSONObject = JSONObject().apply {
        cursor.columnNames.forEachIndexed { index, name ->
            when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> put(name, JSONObject.NULL)
                Cursor.FIELD_TYPE_INTEGER -> put(name, cursor.getLong(index))
                Cursor.FIELD_TYPE_FLOAT -> put(name, cursor.getDouble(index))
                Cursor.FIELD_TYPE_BLOB -> put(
                    name,
                    JSONObject().put(
                        "base64",
                        Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP),
                    ),
                )
                else -> put(name, cursor.getString(index))
            }
        }
    }

    private fun tableColumns(db: SQLiteDatabase, table: String): Set<String> = db.rawQuery(
        "PRAGMA table_info($table)", null,
    ).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
    }

    private fun jsonToContentValues(row: JSONObject, allowedColumns: Set<String>): ContentValues =
        ContentValues().apply {
            row.keys().forEach { key ->
                require(key in allowedColumns) { "Coluna incompatível: $key" }
                val value = row.get(key)
                when (value) {
                    JSONObject.NULL -> putNull(key)
                    is Int -> put(key, value)
                    is Long -> put(key, value)
                    is Double -> put(key, value)
                    is String -> put(key, value)
                    is JSONObject -> put(
                        key,
                        Base64.decode(value.getString("base64"), Base64.DEFAULT),
                    )
                    else -> error("Tipo de dado incompatível")
                }
            }
        }

    private data class StoredEvent(
        val id: Long,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val body: String,
    )

    private data class StoredTransactionMetadata(
        val direction: FinancialTransactionDirection,
        val type: FinancialTransactionType,
        val ruleKey: String?,
        val seriesId: String?,
        val seriesIndex: Int?,
    )

    private data class KnownAccountPreset(
        val type: FinancialAccountType,
        val closingDay: Int?,
        val dueDay: Int?,
        val isDefault: Boolean,
        val cardIdentifiers: String?,
    )

    private companion object {
        const val DATABASE_NAME = "notification_diagnostics.db"
        const val DATABASE_VERSION = 21
        const val BACKUP_FORMAT_VERSION = 1
        const val MAX_BACKUP_CHARACTERS = 50_000_000
        val BACKUP_TABLES = listOf(
            "candidates",
            "events",
            "category_rules",
            "financial_accounts",
            "credit_card_invoices",
            "invoice_payments",
            "invoice_adjustments",
            "account_movements",
            "transactions",
            "deleted_transaction_groups",
            "deleted_transactions",
            "monthly_budgets",
        )
        const val TOTAL_BUDGET_KEY = "__TOTAL__"
        const val CUSTOM_BUDGET_PREFIX = "CUSTOM:"
    }
}

data class MobillsImportResult(
    val imported: Int,
    val alreadyImported: Int,
)
