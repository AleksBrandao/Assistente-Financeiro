package br.com.assistentefinanceiro.data

import android.database.sqlite.SQLiteDatabase
import br.com.assistentefinanceiro.notifications.DiagnosticStore
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Read-only CSV diagnostic focused on credit-card invoice reconciliation.
 *
 * It deliberately reads both the core invoice tables and the optional Open Finance side tables so
 * mismatches between local invoices, Pluggy Bills, payments and individual card transactions can be
 * inspected without changing application data.
 */
internal class InvoiceDiagnosticCsvExporter(
    private val store: DiagnosticStore,
) {
    fun export(): String {
        val db = store.readableDatabase
        val hasTransactionMetadata = tableExists(db, "external_transaction_metadata")
        val hasBillLinks = tableExists(db, "external_bill_links")
        val hasPaymentLinks = tableExists(db, "external_bill_payment_links")
        val rows = mutableListOf<List<String>>()

        db.rawQuery(
            """SELECT invoices.id,
                      accounts.id,
                      accounts.name,
                      invoices.closing_period,
                      invoices.closing_date,
                      invoices.due_date,
                      invoices.status,
                      COUNT(transactions.id),
                      COALESCE(SUM(CASE
                          WHEN transactions.direction = 'EXPENSE' THEN CAST(transactions.amount AS NUMERIC)
                          ELSE -CAST(transactions.amount AS NUMERIC)
                      END),0),
                      COALESCE(adjustments.amount,'0'),
                      COALESCE((SELECT SUM(CAST(payments.amount AS NUMERIC))
                                FROM invoice_payments AS payments
                                WHERE payments.account_id = invoices.account_id
                                  AND payments.closing_period = invoices.closing_period),0)
               FROM credit_card_invoices AS invoices
               INNER JOIN financial_accounts AS accounts ON accounts.id = invoices.account_id
               LEFT JOIN transactions ON transactions.invoice_id = invoices.id
               LEFT JOIN invoice_adjustments AS adjustments
                      ON adjustments.account_id = invoices.account_id
                     AND adjustments.closing_period = invoices.closing_period
               GROUP BY invoices.id
               ORDER BY invoices.due_date DESC, invoices.closing_period DESC, accounts.name""",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val invoiceId = cursor.getLong(0)
                val accountId = cursor.getLong(1)
                val accountName = cursor.getString(2)
                val closingPeriod = cursor.getString(3)
                val closingDate = cursor.getString(4)
                val dueDate = cursor.getString(5)
                val storedStatus = cursor.getString(6)
                val transactionCount = cursor.getInt(7)
                val baseTotal = decimal(cursor.getString(8))
                val adjustment = decimal(cursor.getString(9))
                val total = baseTotal + adjustment
                val paid = decimal(cursor.getString(10))
                val outstanding = total - paid
                val externalBills = if (hasBillLinks) {
                    externalBills(db, accountId, closingPeriod)
                } else emptyList()
                val payments = invoicePayments(
                    db = db,
                    accountId = accountId,
                    closingPeriod = closingPeriod,
                    hasPaymentLinks = hasPaymentLinks,
                )
                val transactionBillIds = if (hasTransactionMetadata) {
                    transactionExternalBillIds(db, invoiceId)
                } else emptyList()
                val mismatchCount = if (hasTransactionMetadata && hasBillLinks) {
                    transactionExternalBillMismatchCount(
                        db = db,
                        invoiceId = invoiceId,
                        expectedBillIds = externalBills.map { it.externalBillId }.toSet(),
                    )
                } else 0
                val diagnostics = buildList {
                    if (transactionCount == 0 && total.signum() > 0) {
                        add("TOTAL_OFICIAL_SEM_COMPRAS_LOCAIS")
                    }
                    if (storedStatus == "PAID" && paid < total) {
                        add("STATUS_PAGO_COM_PAGAMENTO_INSUFICIENTE")
                    }
                    if (storedStatus == "PAID" && LocalDate.parse(closingDate).isAfter(LocalDate.now())) {
                        add("FATURA_FUTURA_MARCADA_COMO_PAGA")
                    }
                    if (paid > total && total.signum() >= 0) {
                        add("PAGAMENTO_MAIOR_QUE_TOTAL")
                    }
                    if (mismatchCount > 0) {
                        add("COMPRAS_VINCULADAS_A_BILL_DIVERGENTE")
                    }
                    if (externalBills.size > 1) {
                        add("MULTIPLAS_BILLS_PARA_MESMA_COMPETENCIA")
                    }
                }

                rows += row(
                    recordType = "FATURA",
                    accountName = accountName,
                    accountId = accountId.toString(),
                    invoiceId = invoiceId.toString(),
                    closingPeriod = closingPeriod,
                    closingDate = closingDate,
                    dueDate = dueDate.orEmpty(),
                    invoiceStatus = storedStatus,
                    transactionCount = transactionCount.toString(),
                    baseTotal = baseTotal.toPlainString(),
                    adjustment = adjustment.toPlainString(),
                    officialTotal = total.toPlainString(),
                    paidAmount = paid.toPlainString(),
                    outstandingAmount = outstanding.toPlainString(),
                    externalBillIds = externalBills.joinToString(" | ") { it.externalBillId },
                    externalProviders = externalBills.joinToString(" | ") { it.provider },
                    financeCharges = externalBills.joinToString(" | ") { it.financeChargeTotal },
                    paymentCount = payments.size.toString(),
                    payments = payments.joinToString(" | ") { it.summary },
                    paymentSourceBillIds = payments.mapNotNull { it.sourceExternalBillId }
                        .distinct()
                        .joinToString(" | "),
                    transactionExternalBillIds = transactionBillIds.joinToString(" | "),
                    mismatchCount = mismatchCount.toString(),
                    diagnostic = diagnostics.joinToString(" | "),
                )
            }
        }

        if (hasTransactionMetadata) {
            val billJoin = if (hasBillLinks) {
                """LEFT JOIN external_bill_links AS bill
                          ON bill.provider = metadata.provider
                         AND bill.external_bill_id = metadata.external_bill_id
                         AND bill.local_account_id = transactions.account_id"""
            } else ""
            val expectedPeriodColumn = if (hasBillLinks) "bill.closing_period" else "NULL"
            db.rawQuery(
                """SELECT transactions.id,
                          transactions.account_id,
                          accounts.name,
                          transactions.invoice_id,
                          invoices.closing_period,
                          metadata.external_transaction_id,
                          metadata.external_bill_id,
                          transactions.occurred_at,
                          transactions.description,
                          transactions.status,
                          transactions.amount,
                          $expectedPeriodColumn
                   FROM transactions
                   INNER JOIN financial_accounts AS accounts ON accounts.id = transactions.account_id
                   INNER JOIN external_transaction_metadata AS metadata
                           ON metadata.transaction_id = transactions.id
                   LEFT JOIN credit_card_invoices AS invoices ON invoices.id = transactions.invoice_id
                   $billJoin
                   WHERE accounts.type = 'CREDIT_CARD'
                     AND metadata.external_bill_id IS NOT NULL
                     AND TRIM(metadata.external_bill_id) <> ''
                     AND (
                         transactions.invoice_id IS NULL
                         ${if (hasBillLinks) "OR bill.closing_period IS NULL OR invoices.closing_period <> bill.closing_period" else ""}
                     )
                   ORDER BY transactions.occurred_at DESC""",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val currentInvoiceId = if (cursor.isNull(3)) "" else cursor.getLong(3).toString()
                    val currentPeriod = if (cursor.isNull(4)) "" else cursor.getString(4)
                    val expectedPeriod = if (cursor.isNull(11)) "" else cursor.getString(11)
                    val diagnostic = when {
                        currentInvoiceId.isBlank() -> "TRANSACAO_COM_BILL_SEM_FATURA_LOCAL"
                        expectedPeriod.isBlank() -> "EXTERNAL_BILL_SEM_MAPEAMENTO_LOCAL"
                        currentPeriod != expectedPeriod -> "TRANSACAO_VINCULADA_A_COMPETENCIA_DIVERGENTE"
                        else -> "REVISAR_VINCULO"
                    }
                    rows += row(
                        recordType = "TRANSACAO_DIVERGENTE",
                        accountName = cursor.getString(2),
                        accountId = cursor.getLong(1).toString(),
                        invoiceId = currentInvoiceId,
                        closingPeriod = currentPeriod,
                        transactionId = cursor.getLong(0).toString(),
                        externalTransactionId = cursor.getString(5),
                        transactionDate = cursor.getString(7),
                        transactionDescription = cursor.getString(8),
                        transactionStatus = cursor.getString(9),
                        transactionAmount = cursor.getString(10),
                        transactionExternalBillId = cursor.getString(6),
                        expectedClosingPeriod = expectedPeriod,
                        diagnostic = diagnostic,
                    )
                }
            }
        }

        return buildString {
            append('\uFEFF')
            appendLine(HEADERS.joinToString(SEPARATOR) { csv(it) })
            rows.forEach { values ->
                appendLine(values.joinToString(SEPARATOR) { csv(it) })
            }
        }
    }

    private fun externalBills(
        db: SQLiteDatabase,
        accountId: Long,
        closingPeriod: String,
    ): List<ExternalBillInfo> = db.rawQuery(
        """SELECT provider,external_bill_id,finance_charge_total
           FROM external_bill_links
           WHERE local_account_id = ? AND closing_period = ?
           ORDER BY provider,external_bill_id""",
        arrayOf(accountId.toString(), closingPeriod),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    ExternalBillInfo(
                        provider = cursor.getString(0),
                        externalBillId = cursor.getString(1),
                        financeChargeTotal = cursor.getString(2),
                    ),
                )
            }
        }
    }

    private fun invoicePayments(
        db: SQLiteDatabase,
        accountId: Long,
        closingPeriod: String,
        hasPaymentLinks: Boolean,
    ): List<PaymentInfo> {
        val select = if (hasPaymentLinks) {
            """SELECT payments.id,payments.amount,payments.paid_at,
                      links.external_payment_id,links.source_external_bill_id
               FROM invoice_payments AS payments
               LEFT JOIN external_bill_payment_links AS links
                      ON links.invoice_payment_id = payments.id
               WHERE payments.account_id = ? AND payments.closing_period = ?
               ORDER BY payments.paid_at,payments.id"""
        } else {
            """SELECT payments.id,payments.amount,payments.paid_at,NULL,NULL
               FROM invoice_payments AS payments
               WHERE payments.account_id = ? AND payments.closing_period = ?
               ORDER BY payments.paid_at,payments.id"""
        }
        return db.rawQuery(select, arrayOf(accountId.toString(), closingPeriod)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PaymentInfo(
                            id = cursor.getLong(0),
                            amount = cursor.getString(1),
                            paidAt = cursor.getString(2),
                            externalPaymentId = if (cursor.isNull(3)) null else cursor.getString(3),
                            sourceExternalBillId = if (cursor.isNull(4)) null else cursor.getString(4),
                        ),
                    )
                }
            }
        }
    }

    private fun transactionExternalBillIds(
        db: SQLiteDatabase,
        invoiceId: Long,
    ): List<String> = db.rawQuery(
        """SELECT DISTINCT metadata.external_bill_id
           FROM transactions
           INNER JOIN external_transaction_metadata AS metadata
                   ON metadata.transaction_id = transactions.id
           WHERE transactions.invoice_id = ?
             AND metadata.external_bill_id IS NOT NULL
             AND TRIM(metadata.external_bill_id) <> ''
           ORDER BY metadata.external_bill_id""",
        arrayOf(invoiceId.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    private fun transactionExternalBillMismatchCount(
        db: SQLiteDatabase,
        invoiceId: Long,
        expectedBillIds: Set<String>,
    ): Int {
        if (expectedBillIds.isEmpty()) return 0
        val actual = transactionExternalBillIds(db, invoiceId)
        return actual.count { it !in expectedBillIds }
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
        arrayOf(table),
    ).use { it.moveToFirst() }

    private fun decimal(value: String?): BigDecimal = value?.toBigDecimalOrNull() ?: BigDecimal.ZERO

    private fun row(
        recordType: String = "",
        accountName: String = "",
        accountId: String = "",
        invoiceId: String = "",
        closingPeriod: String = "",
        closingDate: String = "",
        dueDate: String = "",
        invoiceStatus: String = "",
        transactionCount: String = "",
        baseTotal: String = "",
        adjustment: String = "",
        officialTotal: String = "",
        paidAmount: String = "",
        outstandingAmount: String = "",
        externalBillIds: String = "",
        externalProviders: String = "",
        financeCharges: String = "",
        paymentCount: String = "",
        payments: String = "",
        paymentSourceBillIds: String = "",
        transactionExternalBillIds: String = "",
        mismatchCount: String = "",
        transactionId: String = "",
        externalTransactionId: String = "",
        transactionDate: String = "",
        transactionDescription: String = "",
        transactionStatus: String = "",
        transactionAmount: String = "",
        transactionExternalBillId: String = "",
        expectedClosingPeriod: String = "",
        diagnostic: String = "",
    ): List<String> = listOf(
        recordType,
        accountName,
        accountId,
        invoiceId,
        closingPeriod,
        closingDate,
        dueDate,
        invoiceStatus,
        transactionCount,
        baseTotal,
        adjustment,
        officialTotal,
        paidAmount,
        outstandingAmount,
        externalBillIds,
        externalProviders,
        financeCharges,
        paymentCount,
        payments,
        paymentSourceBillIds,
        transactionExternalBillIds,
        mismatchCount,
        transactionId,
        externalTransactionId,
        transactionDate,
        transactionDescription,
        transactionStatus,
        transactionAmount,
        transactionExternalBillId,
        expectedClosingPeriod,
        diagnostic,
    )

    private fun csv(value: String): String = '"' + value.replace("\"", "\"\"") + '"'

    private data class ExternalBillInfo(
        val provider: String,
        val externalBillId: String,
        val financeChargeTotal: String,
    )

    private data class PaymentInfo(
        val id: Long,
        val amount: String,
        val paidAt: String,
        val externalPaymentId: String?,
        val sourceExternalBillId: String?,
    ) {
        val summary: String
            get() = buildString {
                append("id=").append(id)
                append(",data=").append(paidAt)
                append(",valor=").append(amount)
                externalPaymentId?.let { append(",externalPaymentId=").append(it) }
                sourceExternalBillId?.let { append(",sourceBill=").append(it) }
            }
    }

    private companion object {
        const val SEPARATOR = ";"
        val HEADERS = listOf(
            "Tipo registro",
            "Conta",
            "Account ID",
            "Invoice ID",
            "Competência",
            "Fechamento",
            "Vencimento",
            "Status fatura",
            "Qtd compras vinculadas",
            "Base total",
            "Ajuste",
            "Total oficial",
            "Valor pago",
            "Em aberto",
            "External Bill IDs da fatura",
            "Providers",
            "Encargos financeiros",
            "Qtd pagamentos",
            "Pagamentos",
            "Bills origem dos pagamentos",
            "External Bill IDs das compras",
            "Qtd compras com Bill divergente",
            "Transaction ID",
            "External Transaction ID",
            "Data transação",
            "Descrição transação",
            "Status transação",
            "Valor transação",
            "External Bill ID da transação",
            "Competência esperada pela Bill",
            "Diagnóstico",
        )
    }
}
