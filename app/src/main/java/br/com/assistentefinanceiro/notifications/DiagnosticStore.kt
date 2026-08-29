package br.com.assistentefinanceiro.notifications

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

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
                parsed INTEGER NOT NULL,
                classification TEXT NOT NULL DEFAULT 'PENDING_RULE', classification_reason TEXT,
                transaction_type TEXT, occurred_at TEXT,
                card_last_four TEXT, amount TEXT, merchant TEXT
            )"""
        )
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
        reclassifyExistingEvents(db)
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

    fun recordEvent(packageName: String, appLabel: String, title: String, body: String, postedAt: Long) {
        val result = FinancialNotificationClassifier.classify(packageName, appLabel, title, body)
        val transaction = result.transaction
        writableDatabase.insert("events", null, ContentValues().apply {
            put("package_name", packageName)
            put("app_label", appLabel)
            put("title", title)
            put("body", body)
            put("posted_at", postedAt)
            put("parsed", if (result.classification == NotificationClassification.TRANSACTION) 1 else 0)
            put("classification", result.classification.name)
            put("classification_reason", result.reason)
            put("transaction_type", transaction?.type?.name)
            put("occurred_at", transaction?.occurredAt?.toString())
            put("card_last_four", transaction?.cardLastFour)
            put("amount", transaction?.amount?.toPlainString())
            put("merchant", transaction?.merchant)
        })
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

    fun clearEvents() = writableDatabase.delete("events", null, null)

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

    private data class StoredEvent(
        val id: Long,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val body: String,
    )

    private companion object {
        const val DATABASE_NAME = "notification_diagnostics.db"
        const val DATABASE_VERSION = 3
    }
}
