package br.com.assistentefinanceiro.notifications

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import br.com.assistentefinanceiro.importing.ImportDisposition
import br.com.assistentefinanceiro.importing.MobillsImportPreview
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

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
                      original_status,account_id,invoice_id
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
                            )
                        )
                    }
                }
            }
        }

    fun updateTransactionDetails(
        transactionId: Long,
        description: String,
        category: TransactionCategory,
        status: TransactionStatus,
        applyToFuture: Boolean = false,
    ): Boolean {
        val normalizedDescription = description.trim()
        if (normalizedDescription.isBlank()) return false

        val db = writableDatabase
        val metadata = db.rawQuery(
            "SELECT direction,type,rule_key FROM transactions WHERE id = ?",
            arrayOf(transactionId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val direction = FinancialTransactionDirection.fromStored(cursor.getString(0))
                ?: return@use null
            val type = FinancialTransactionType.fromStored(cursor.getString(1))
                ?: return@use null
            StoredTransactionMetadata(direction, type, cursor.getString(2))
        } ?: return false

        if (!category.supports(metadata.direction)) return false
        val shouldSaveRule = applyToFuture && TransactionCategoryRule.canApplyToFuture(
            type = metadata.type,
            category = category,
            ruleKey = metadata.ruleKey,
        )

        var updated = false
        db.beginTransaction()
        try {
            updated = db.update(
                "transactions",
                ContentValues().apply {
                    put("description", normalizedDescription)
                    put("category", category.name)
                    put("category_source", TransactionCategorySource.MANUAL.name)
                    put("status", status.name)
                },
                "id = ?",
                arrayOf(transactionId.toString()),
            ) == 1

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
        """SELECT id,name,type,closing_day,due_day,is_default,card_identifiers
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
    ): Boolean {
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) return false
        if (closingDay != null && closingDay !in 1..31) return false
        if (dueDay != null && dueDay !in 1..31) return false
        val normalizedKey = FinancialAccountIdentity.normalize(normalizedName)
        val normalizedIdentifiers = FinancialAccountIdentity.normalizedIdentifiers(cardIdentifiers)
        if (normalizedKey.isBlank()) return false
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
                      END),0),COUNT(transactions.id)
               FROM credit_card_invoices AS invoices
               LEFT JOIN transactions ON transactions.invoice_id = invoices.id
               WHERE invoices.account_id = ?
               GROUP BY invoices.id
               ORDER BY invoices.closing_period DESC""",
            arrayOf(accountId.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        CreditCardInvoiceRecord(
                            id = cursor.getLong(0),
                            accountId = cursor.getLong(1),
                            closingPeriod = YearMonth.parse(cursor.getString(2)),
                            closingDate = LocalDate.parse(cursor.getString(3)),
                            dueDate = cursor.getString(4)?.let(LocalDate::parse),
                            status = CreditCardInvoiceStatus.fromStored(cursor.getString(5)),
                            total = cursor.getString(6).toBigDecimal(),
                            transactionCount = cursor.getInt(7),
                        )
                    )
                }
            }
        }
    }

    fun invoiceTransactions(invoiceId: Long): List<FinancialTransactionRecord> =
        readableDatabase.rawQuery(
            """SELECT id,source_event_id,direction,type,amount,occurred_at,description,source_package,
                      category,category_source,rule_key,origin,status,account,original_category,
                      original_status,account_id,invoice_id
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
                card_identifiers TEXT
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
            "SELECT id,closing_date FROM credit_card_invoices WHERE account_id = ?",
            arrayOf(accountId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val closingDate = LocalDate.parse(cursor.getString(1))
                db.update(
                    "credit_card_invoices",
                    ContentValues().apply {
                        put("status", CreditCardBillingCycle.status(closingDate, today).name)
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
        const val DATABASE_VERSION = 10
    }
}

data class MobillsImportResult(
    val imported: Int,
    val alreadyImported: Int,
)
