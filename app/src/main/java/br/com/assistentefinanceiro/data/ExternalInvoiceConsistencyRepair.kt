package br.com.assistentefinanceiro.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import br.com.assistentefinanceiro.notifications.CreditCardBillingCycle
import br.com.assistentefinanceiro.notifications.DiagnosticStore
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * Reconciles the additive Open Finance metadata with the core invoice tables after a Bill sync.
 *
 * Two invariants are enforced here:
 * 1. a transaction whose externalBillId points to an official Bill is linked to that Bill only
 *    when its accounting date is not after the Bill closing date;
 * 2. a payment reported by Bill N+1 can settle only a previous invoice that is itself backed by an
 *    official external Bill link, never an unrelated provisional invoice.
 *
 * The official Bill total remains authoritative: after transactions are relinked, the local
 * adjustment is recalculated so base + adjustment still equals the provider total.
 */
internal class ExternalInvoiceConsistencyRepair(
    private val store: DiagnosticStore,
) {
    fun repairAfterBillImport(drafts: List<ExternalBillImportDraft>) {
        if (drafts.isEmpty()) return
        val db = store.writableDatabase
        if (
            !tableExists(db, "external_bill_links") ||
            !tableExists(db, "external_transaction_metadata") ||
            !tableExists(db, "external_bill_payment_links")
        ) return

        db.beginTransaction()
        try {
            drafts.sortedBy { it.dueDate }.forEach { bill ->
                repairBillTransactions(db, bill)
                repairBillPayments(db, bill)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun repairBillTransactions(
        db: SQLiteDatabase,
        bill: ExternalBillImportDraft,
    ) {
        val official = officialInvoice(db, bill) ?: return
        val cycle = accountCycle(db, bill.localAccountId)
        val linked = db.rawQuery(
            """SELECT transactions.id,transactions.occurred_at,
                      metadata.bill_forecast_period
               FROM external_transaction_metadata AS metadata
               INNER JOIN transactions ON transactions.id = metadata.transaction_id
               WHERE metadata.provider = ?
                 AND metadata.external_bill_id = ?
                 AND transactions.account_id = ?""",
            arrayOf(
                bill.provider.name,
                bill.externalBillId,
                bill.localAccountId.toString(),
            ),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        LinkedTransaction(
                            id = cursor.getLong(0),
                            occurredAt = cursor.getString(1),
                            billForecastPeriod = if (cursor.isNull(2)) null else cursor.getString(2),
                        ),
                    )
                }
            }
        }

        linked.forEach { transaction ->
            val occurredDate = runCatching {
                LocalDateTime.parse(transaction.occurredAt).toLocalDate()
            }.getOrNull() ?: return@forEach
            val targetInvoiceId = if (!occurredDate.isAfter(official.closingDate)) {
                official.id
            } else {
                fallbackInvoice(
                    db = db,
                    accountId = bill.localAccountId,
                    cycle = cycle,
                    occurredDate = occurredDate,
                    billForecastPeriod = transaction.billForecastPeriod,
                )
            }
            if (targetInvoiceId != null) {
                db.update(
                    "transactions",
                    ContentValues().apply { put("invoice_id", targetInvoiceId) },
                    "id = ?",
                    arrayOf(transaction.id.toString()),
                )
            }
        }

        synchronizeOfficialTotal(
            db = db,
            invoiceId = official.id,
            accountId = bill.localAccountId,
            closingPeriod = official.closingPeriod,
            officialTotal = bill.totalAmount,
        )
    }

    private fun repairBillPayments(
        db: SQLiteDatabase,
        sourceBill: ExternalBillImportDraft,
    ) {
        val paymentIds = db.rawQuery(
            """SELECT invoice_payment_id
               FROM external_bill_payment_links
               WHERE provider = ? AND source_external_bill_id = ?""",
            arrayOf(sourceBill.provider.name, sourceBill.externalBillId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        }
        if (paymentIds.isEmpty()) return

        val target = previousOfficialInvoice(db, sourceBill)
        if (target == null) {
            // The imported history does not contain an authoritative previous Bill. Keeping a
            // payment attached to a provisional invoice would create a false PAID status.
            paymentIds.forEach { paymentId ->
                db.delete("invoice_payments", "id = ?", arrayOf(paymentId.toString()))
            }
            db.delete(
                "external_bill_payment_links",
                "provider = ? AND source_external_bill_id = ?",
                arrayOf(sourceBill.provider.name, sourceBill.externalBillId),
            )
            return
        }

        paymentIds.forEach { paymentId ->
            db.update(
                "invoice_payments",
                ContentValues().apply {
                    put("account_id", sourceBill.localAccountId)
                    put("closing_period", target.closingPeriod.toString())
                },
                "id = ?",
                arrayOf(paymentId.toString()),
            )
        }
    }

    private fun officialInvoice(
        db: SQLiteDatabase,
        bill: ExternalBillImportDraft,
    ): StoredInvoice? = db.rawQuery(
        """SELECT invoices.id,invoices.closing_period,invoices.closing_date,invoices.due_date
           FROM external_bill_links AS links
           INNER JOIN credit_card_invoices AS invoices
                   ON invoices.account_id = links.local_account_id
                  AND invoices.closing_period = links.closing_period
           WHERE links.provider = ?
             AND links.external_bill_id = ?
             AND links.local_account_id = ?
           LIMIT 1""",
        arrayOf(
            bill.provider.name,
            bill.externalBillId,
            bill.localAccountId.toString(),
        ),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        StoredInvoice(
            id = cursor.getLong(0),
            closingPeriod = YearMonth.parse(cursor.getString(1)),
            closingDate = LocalDate.parse(cursor.getString(2)),
            dueDate = if (cursor.isNull(3)) null else LocalDate.parse(cursor.getString(3)),
        )
    }

    private fun previousOfficialInvoice(
        db: SQLiteDatabase,
        sourceBill: ExternalBillImportDraft,
    ): StoredInvoice? = db.rawQuery(
        """SELECT invoices.id,invoices.closing_period,invoices.closing_date,invoices.due_date
           FROM external_bill_links AS links
           INNER JOIN credit_card_invoices AS invoices
                   ON invoices.account_id = links.local_account_id
                  AND invoices.closing_period = links.closing_period
           WHERE links.provider = ?
             AND links.local_account_id = ?
             AND invoices.due_date IS NOT NULL
             AND invoices.due_date < ?
           ORDER BY invoices.due_date DESC,invoices.closing_period DESC
           LIMIT 1""",
        arrayOf(
            sourceBill.provider.name,
            sourceBill.localAccountId.toString(),
            sourceBill.dueDate.toString(),
        ),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        StoredInvoice(
            id = cursor.getLong(0),
            closingPeriod = YearMonth.parse(cursor.getString(1)),
            closingDate = LocalDate.parse(cursor.getString(2)),
            dueDate = if (cursor.isNull(3)) null else LocalDate.parse(cursor.getString(3)),
        )
    }

    private fun fallbackInvoice(
        db: SQLiteDatabase,
        accountId: Long,
        cycle: AccountCycle?,
        occurredDate: LocalDate,
        billForecastPeriod: String?,
    ): Long? {
        val accountCycle = cycle ?: return null
        val closingDay = accountCycle.closingDay ?: return null
        val forecast = billForecastPeriod?.let { value ->
            runCatching { YearMonth.parse(value) }.getOrNull()
        }
        val dates = if (forecast != null) {
            val dueDay = accountCycle.dueDay
            val closingPeriod = if (dueDay != null && dueDay <= closingDay) {
                forecast.minusMonths(1)
            } else {
                forecast
            }
            Triple(
                closingPeriod,
                dateAtDay(closingPeriod, closingDay),
                dueDay?.let { dateAtDay(forecast, it) },
            )
        } else {
            val calculated = CreditCardBillingCycle.calculate(
                purchaseDate = occurredDate,
                closingDay = closingDay,
                dueDay = accountCycle.dueDay,
            )
            Triple(calculated.closingPeriod, calculated.closingDate, calculated.dueDate)
        }
        return ensureInvoiceByDates(
            db = db,
            accountId = accountId,
            closingPeriod = dates.first,
            closingDate = dates.second,
            dueDate = dates.third,
        )
    }

    private fun accountCycle(db: SQLiteDatabase, accountId: Long): AccountCycle? = db.rawQuery(
        "SELECT closing_day,due_day FROM financial_accounts WHERE id = ?",
        arrayOf(accountId.toString()),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        AccountCycle(
            closingDay = if (cursor.isNull(0)) null else cursor.getInt(0),
            dueDay = if (cursor.isNull(1)) null else cursor.getInt(1),
        )
    }

    private fun ensureInvoiceByDates(
        db: SQLiteDatabase,
        accountId: Long,
        closingPeriod: YearMonth,
        closingDate: LocalDate,
        dueDate: LocalDate?,
    ): Long {
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
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
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

    private fun synchronizeOfficialTotal(
        db: SQLiteDatabase,
        invoiceId: Long,
        accountId: Long,
        closingPeriod: YearMonth,
        officialTotal: BigDecimal,
    ) {
        val baseTotal = db.rawQuery(
            "SELECT direction,amount FROM transactions WHERE invoice_id = ?",
            arrayOf(invoiceId.toString()),
        ).use { cursor ->
            var total = BigDecimal.ZERO
            while (cursor.moveToNext()) {
                val amount = cursor.getString(1).toBigDecimalOrNull() ?: BigDecimal.ZERO
                total = if (cursor.getString(0) == "EXPENSE") total + amount else total - amount
            }
            total
        }
        db.insertWithOnConflict(
            "invoice_adjustments",
            null,
            ContentValues().apply {
                put("account_id", accountId)
                put("closing_period", closingPeriod.toString())
                put("amount", (officialTotal - baseTotal).toPlainString())
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
        arrayOf(table),
    ).use { it.moveToFirst() }

    private fun dateAtDay(period: YearMonth, day: Int): LocalDate =
        period.atDay(day.coerceAtMost(period.lengthOfMonth()))

    private data class LinkedTransaction(
        val id: Long,
        val occurredAt: String,
        val billForecastPeriod: String?,
    )

    private data class AccountCycle(
        val closingDay: Int?,
        val dueDay: Int?,
    )

    private data class StoredInvoice(
        val id: Long,
        val closingPeriod: YearMonth,
        val closingDate: LocalDate,
        val dueDate: LocalDate?,
    )
}
