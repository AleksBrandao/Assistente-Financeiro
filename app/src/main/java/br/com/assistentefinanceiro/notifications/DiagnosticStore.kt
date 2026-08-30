package br.com.assistentefinanceiro.notifications

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import br.com.assistentefinanceiro.importing.ImportDisposition
import br.com.assistentefinanceiro.importing.MobillsImportPreview
import java.time.LocalDate
import java.time.LocalDateTime
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
                      category,category_source,rule_key,origin,status,account,original_category
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
                row.disposition == ImportDisposition.PLANNED ||
                (includePossibleDuplicates && row.disposition == ImportDisposition.POSSIBLE_DUPLICATE)
        }
        var imported = 0
        var alreadyImported = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            accepted.forEach { row ->
                val direction = row.direction ?: return@forEach
                val date = row.date ?: return@forEach
                val amount = row.amount ?: return@forEach
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
                            if (date.isAfter(LocalDate.now())) {
                                TransactionStatus.PLANNED.name
                            } else {
                                TransactionStatus.REALIZED.name
                            },
                        )
                        put("account", row.account)
                        put("original_category", row.originalCategory)
                        put("import_key", row.importKey)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                if (result == -1L) alreadyImported++ else imported++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return MobillsImportResult(imported, alreadyImported)
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
            """SELECT id,package_name,transaction_type,amount,occurred_at,merchant
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
    ) {
        val ruleKey = if (type == FinancialTransactionType.CARD_PURCHASE) {
            TransactionCategoryRule.normalizeMerchant(merchant)
        } else {
            null
        }
        val ruleCategory = ruleKey?.let {
            findCategoryRule(db, it, type.direction)
        }
        db.insertWithOnConflict(
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

    private companion object {
        const val DATABASE_NAME = "notification_diagnostics.db"
        const val DATABASE_VERSION = 7
    }
}

data class MobillsImportResult(
    val imported: Int,
    val alreadyImported: Int,
)
