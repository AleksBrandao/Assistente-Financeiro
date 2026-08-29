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
    val parsed: Boolean,
    val cardLastFour: String?,
    val amount: String?,
    val merchant: String?,
)

class DiagnosticStore(context: Context) : SQLiteOpenHelper(context, "notification_diagnostics.db", null, 1) {
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
                parsed INTEGER NOT NULL, card_last_four TEXT, amount TEXT, merchant TEXT
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun recordCandidate(packageName: String, appLabel: String, postedAt: Long) {
        writableDatabase.insertWithOnConflict("candidates", null, ContentValues().apply {
            put("package_name", packageName); put("app_label", appLabel); put("last_seen_at", postedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun recordEvent(packageName: String, appLabel: String, title: String, body: String, postedAt: Long) {
        val parsed = SantanderParser.parse(title, body)
        writableDatabase.insert("events", null, ContentValues().apply {
            put("package_name", packageName); put("app_label", appLabel); put("title", title)
            put("body", body); put("posted_at", postedAt); put("parsed", if (parsed != null) 1 else 0)
            put("card_last_four", parsed?.cardLastFour); put("amount", parsed?.amount?.toPlainString())
            put("merchant", parsed?.merchant)
        })
    }

    fun candidates(): List<Pair<String, String>> = readableDatabase.rawQuery(
        "SELECT package_name, app_label FROM candidates ORDER BY last_seen_at DESC", null
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) } }

    fun recentEvents(limit: Int = 50): List<DiagnosticEvent> = readableDatabase.rawQuery(
        """SELECT id,package_name,app_label,title,body,posted_at,parsed,card_last_four,amount,merchant
           FROM events ORDER BY posted_at DESC LIMIT ?""", arrayOf(limit.coerceIn(1, 200).toString())
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(DiagnosticEvent(
            cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
            cursor.getString(4), cursor.getLong(5), cursor.getInt(6) == 1,
            cursor.getString(7), cursor.getString(8), cursor.getString(9),
        ))
    } }

    fun clearEvents() = writableDatabase.delete("events", null, null)
}

