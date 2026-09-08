package br.com.assistentefinanceiro.data

import android.content.ContentValues
import br.com.assistentefinanceiro.notifications.CreditCardBillingCycle
import br.com.assistentefinanceiro.notifications.CreditCardInvoiceRecord
import br.com.assistentefinanceiro.notifications.CreditCardInvoiceStatus
import br.com.assistentefinanceiro.notifications.DiagnosticStore
import br.com.assistentefinanceiro.notifications.FinancialAccountRecord
import br.com.assistentefinanceiro.notifications.FinancialAccountType
import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.FinancialTransactionRecord
import br.com.assistentefinanceiro.notifications.FinancialTransactionType
import br.com.assistentefinanceiro.notifications.InvoicePaymentRecord
import br.com.assistentefinanceiro.notifications.TransactionOrigin
import br.com.assistentefinanceiro.notifications.TransactionStatus
import br.com.assistentefinanceiro.notifications.matchesCardLastFour
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Reconciles a bank debit that represents a credit-card payment with the corresponding local
 * invoice. Pluggy exposes these bank-side debits as ordinary imported expenses; without this step
 * the same economic event appears both as a bank expense and as an unpaid card liability.
 *
 * Matching is deliberately conservative: the transaction must be a realized Pluggy bank expense,
 * identify a unique card by its FINAL #### description, fall within seven days of the invoice due
 * date and agree with the invoice amount within ten cents.
 */
internal class BankInvoicePaymentReconciler(
    private val store: DiagnosticStore,
) {
    fun reconcile() {
        val accounts = store.financialAccounts()
        val bankAccountIds = accounts
            .filter { it.type == FinancialAccountType.BANK_ACCOUNT }
            .mapTo(mutableSetOf()) { it.id }
        val cards = accounts.filter { it.type == FinancialAccountType.CREDIT_CARD }
        if (bankAccountIds.isEmpty() || cards.isEmpty()) return

        val candidates = store.recentTransactions(10_000)
            .filter { transaction ->
                transaction.accountId in bankAccountIds && transaction.isBankCardPaymentCandidate()
            }
        if (candidates.isEmpty()) return

        val db = store.writableDatabase
        db.beginTransaction()
        try {
            candidates.forEach { transaction ->
                val lastFour = FINAL_CARD_PATTERN.find(transaction.description)?.groupValues?.get(1)
                    ?: return@forEach
                val matchingCards = cards.filter { it.matchesCardLastFour(lastFour) }
                if (matchingCards.size != 1) return@forEach
                val card = matchingCards.single()
                val paymentDate = transaction.occurredAt.take(10).let { raw ->
                    runCatching { LocalDate.parse(raw) }.getOrNull()
                } ?: return@forEach
                val bankAmount = transaction.amount.toBigDecimalOrNull() ?: return@forEach
                if (bankAmount.signum() <= 0) return@forEach

                val invoice = bestInvoiceMatch(card, paymentDate, bankAmount) ?: return@forEach
                reconcilePayment(
                    transaction = transaction,
                    invoice = invoice,
                    paymentDate = paymentDate,
                    bankAmount = bankAmount,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun bestInvoiceMatch(
        card: FinancialAccountRecord,
        paymentDate: LocalDate,
        bankAmount: BigDecimal,
    ): CreditCardInvoiceRecord? = store.creditCardInvoices(card.id)
        .asSequence()
        .filter { invoice ->
            val dueDate = invoice.dueDate ?: return@filter false
            invoice.total.signum() > 0 &&
                dayDifference(dueDate, paymentDate) <= PAYMENT_DATE_WINDOW_DAYS &&
                amountDifference(invoice.total, bankAmount) <= PAYMENT_AMOUNT_TOLERANCE
        }
        .minWithOrNull(
            compareBy<CreditCardInvoiceRecord> { invoice ->
                dayDifference(checkNotNull(invoice.dueDate), paymentDate)
            }.thenBy { invoice -> amountDifference(invoice.total, bankAmount) },
        )

    private fun reconcilePayment(
        transaction: FinancialTransactionRecord,
        invoice: CreditCardInvoiceRecord,
        paymentDate: LocalDate,
        bankAmount: BigDecimal,
    ) {
        val db = store.writableDatabase
        val payments = store.invoicePayments(invoice)
        val existing = payments
            .filter { payment ->
                dayDifference(payment.paidAt, paymentDate) <= PAYMENT_DATE_WINDOW_DAYS &&
                    (
                        amountDifference(payment.amount, bankAmount) <= PAYMENT_AMOUNT_TOLERANCE ||
                            amountDifference(payment.amount, invoice.total) <= PAYMENT_AMOUNT_TOLERANCE
                    )
            }
            .minWithOrNull(
                compareBy<InvoicePaymentRecord> { payment ->
                    dayDifference(payment.paidAt, paymentDate)
                }.thenBy { payment -> amountDifference(payment.amount, bankAmount) },
            )

        if (existing != null) {
            val totalPaid = payments.fold(BigDecimal.ZERO) { sum, payment -> sum + payment.amount }
            val residual = invoice.total - totalPaid
            val normalizedAmount = if (
                residual.abs() <= PAYMENT_AMOUNT_TOLERANCE && residual.signum() != 0
            ) {
                existing.amount + residual
            } else {
                existing.amount
            }
            db.update(
                "invoice_payments",
                ContentValues().apply {
                    put("amount", normalizedAmount.toPlainString())
                    if (existing.sourceAccountId == null) {
                        put("source_account_id", transaction.accountId)
                    }
                },
                "id = ?",
                arrayOf(existing.id.toString()),
            )
        } else {
            val alreadyPaid = payments.fold(BigDecimal.ZERO) { sum, payment -> sum + payment.amount }
            val remaining = (invoice.total - alreadyPaid).max(BigDecimal.ZERO)
            if (
                remaining.signum() == 0 ||
                amountDifference(remaining, bankAmount) > PAYMENT_AMOUNT_TOLERANCE
            ) return

            db.insertOrThrow(
                "invoice_payments",
                null,
                ContentValues().apply {
                    put("account_id", invoice.accountId)
                    put("closing_period", invoice.closingPeriod.toString())
                    // Store the official remaining liability. The actual cents debited from the
                    // bank stay represented by the bank transaction itself.
                    put("amount", remaining.toPlainString())
                    put("paid_at", paymentDate.toString())
                    put("created_at", System.currentTimeMillis())
                    put("source_account_id", transaction.accountId)
                },
            )
        }

        val paidAfter = db.rawQuery(
            """SELECT amount FROM invoice_payments
               WHERE account_id = ? AND closing_period = ?""",
            arrayOf(invoice.accountId.toString(), invoice.closingPeriod.toString()),
        ).use { cursor ->
            var total = BigDecimal.ZERO
            while (cursor.moveToNext()) {
                total += cursor.getString(0).toBigDecimalOrNull() ?: BigDecimal.ZERO
            }
            total
        }
        if (CreditCardBillingCycle.outstandingAmount(invoice.total, paidAfter).signum() == 0) {
            db.update(
                "credit_card_invoices",
                ContentValues().apply { put("status", CreditCardInvoiceStatus.PAID.name) },
                "id = ?",
                arrayOf(invoice.id.toString()),
            )
        }
    }

    private fun FinancialTransactionRecord.isBankCardPaymentCandidate(): Boolean {
        if (
            origin != TransactionOrigin.PLUGGY ||
            direction != FinancialTransactionDirection.EXPENSE ||
            type != FinancialTransactionType.IMPORTED_EXPENSE ||
            status != TransactionStatus.REALIZED
        ) return false

        val categoryMatches = originalCategory.equals("Credit card payment", ignoreCase = true) ||
            customCategory.equals("Credit card payment", ignoreCase = true)
        return categoryMatches || CARD_PAYMENT_DESCRIPTION_PATTERN.containsMatchIn(description)
    }

    private fun dayDifference(first: LocalDate, second: LocalDate): Long =
        abs(ChronoUnit.DAYS.between(first, second))

    private fun amountDifference(first: BigDecimal, second: BigDecimal): BigDecimal =
        (first - second).abs()

    private companion object {
        val PAYMENT_AMOUNT_TOLERANCE: BigDecimal = BigDecimal("0.10")
        const val PAYMENT_DATE_WINDOW_DAYS = 7L
        val FINAL_CARD_PATTERN = Regex("""\bFINAL\s*(\d{4})\b""", RegexOption.IGNORE_CASE)
        val CARD_PAYMENT_DESCRIPTION_PATTERN = Regex(
            """\bFATURA\s+CARTAO\b.*\bFINAL\s*\d{4}\b""",
            RegexOption.IGNORE_CASE,
        )
    }
}
