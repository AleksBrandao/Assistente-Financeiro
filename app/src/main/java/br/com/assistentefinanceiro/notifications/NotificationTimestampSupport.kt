package br.com.assistentefinanceiro.notifications

import android.content.ContentValues
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

internal object NotificationReceivedAtContext {
    private val currentReceivedAt = ThreadLocal<LocalDateTime?>()

    fun currentOrNow(): LocalDateTime = currentReceivedAt.get() ?: LocalDateTime.now()

    fun fromPostedAt(postedAt: Long): LocalDateTime = Instant.ofEpochMilli(postedAt)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()

    fun <T> withPostedAt(postedAt: Long, block: () -> T): T {
        val previous = currentReceivedAt.get()
        currentReceivedAt.set(fromPostedAt(postedAt))
        return try {
            block()
        } finally {
            if (previous == null) currentReceivedAt.remove() else currentReceivedAt.set(previous)
        }
    }
}

/**
 * Repairs notification-backed rows created before the listener started propagating postedAt
 * into parsers that depend on the Android notification timestamp (currently Nubank).
 */
internal object NotificationTimestampRepair {
    private const val NUBANK_PACKAGE = "com.nu.production"
    private val repairedThisProcess = AtomicBoolean(false)

    fun repairOnce(store: DiagnosticStore) {
        if (!repairedThisProcess.compareAndSet(false, true)) return
        runCatching { repair(store) }
            .onFailure { repairedThisProcess.set(false) }
    }

    internal fun repair(store: DiagnosticStore): Int {
        data class Candidate(
            val eventId: Long,
            val appLabel: String,
            val title: String,
            val body: String,
            val postedAt: Long,
            val transactionId: Long,
            val occurredAt: String,
            val accountId: Long?,
        )

        val db = store.writableDatabase
        val candidates = db.rawQuery(
            """SELECT events.id,events.app_label,events.title,events.body,events.posted_at,
                      transactions.id,transactions.occurred_at,transactions.account_id
               FROM events
               INNER JOIN transactions ON transactions.source_event_id = events.id
               WHERE events.package_name = ?
                 AND transactions.origin = ?
                 AND transactions.type = ?""",
            arrayOf(
                NUBANK_PACKAGE,
                TransactionOrigin.NOTIFICATION.name,
                FinancialTransactionType.CARD_PURCHASE.name,
            ),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Candidate(
                            eventId = cursor.getLong(0),
                            appLabel = cursor.getString(1),
                            title = cursor.getString(2),
                            body = cursor.getString(3),
                            postedAt = cursor.getLong(4),
                            transactionId = cursor.getLong(5),
                            occurredAt = cursor.getString(6),
                            accountId = if (cursor.isNull(7)) null else cursor.getLong(7),
                        )
                    )
                }
            }
        }

        if (candidates.isEmpty()) return 0

        var repaired = 0
        val affectedAccountIds = linkedSetOf<Long>()
        db.beginTransaction()
        try {
            candidates.forEach { candidate ->
                val expected = NotificationReceivedAtContext.fromPostedAt(candidate.postedAt)
                val parsed = NubankNotificationParser.parse(
                    BankNotification(
                        packageName = NUBANK_PACKAGE,
                        appLabel = candidate.appLabel,
                        title = candidate.title,
                        body = candidate.body,
                        receivedAt = expected,
                    )
                ) ?: return@forEach
                val expectedStored = parsed.occurredAt.toString()
                if (candidate.occurredAt == expectedStored) return@forEach

                db.update(
                    "events",
                    ContentValues().apply { put("occurred_at", expectedStored) },
                    "id = ?",
                    arrayOf(candidate.eventId.toString()),
                )
                db.update(
                    "transactions",
                    ContentValues().apply { put("occurred_at", expectedStored) },
                    "id = ?",
                    arrayOf(candidate.transactionId.toString()),
                )
                candidate.accountId?.let(affectedAccountIds::add)
                repaired += 1
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        if (affectedAccountIds.isNotEmpty()) {
            store.financialAccounts()
                .filter { it.id in affectedAccountIds && it.type == FinancialAccountType.CREDIT_CARD }
                .forEach { account ->
                    store.saveFinancialAccount(
                        id = account.id,
                        name = account.name,
                        type = account.type,
                        closingDay = account.closingDay,
                        dueDay = account.dueDay,
                        isDefault = account.isDefault,
                        cardIdentifiers = account.cardIdentifiers,
                        openingBalance = account.openingBalance,
                        openingBalanceDate = account.openingBalanceDate,
                    )
                }
        }

        return repaired
    }
}
