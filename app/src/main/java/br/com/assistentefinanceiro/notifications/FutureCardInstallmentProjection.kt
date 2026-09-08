package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * A future card liability inferred from an installment purchase already known locally.
 *
 * Pluggy exposes the current installment transaction and its installment position, but it does not
 * create one local transaction for every future installment. These projections fill that planning
 * gap without persisting synthetic purchases into the database.
 */
data class FutureCardInstallmentProjection(
    val accountId: Long,
    val accountName: String,
    val dueDate: LocalDate,
    val amount: BigDecimal,
)

object FutureCardInstallmentProjectionCalculator {
    fun calculate(
        asOfDate: LocalDate,
        accounts: List<FinancialAccountRecord>,
        transactions: List<FinancialTransactionRecord>,
        invoices: List<CreditCardInvoiceRecord>,
    ): List<FutureCardInstallmentProjection> {
        val cardsById = accounts
            .filter { account ->
                account.type == FinancialAccountType.CREDIT_CARD &&
                    account.closingDay != null &&
                    account.dueDay != null
            }
            .associateBy { it.id }
        if (cardsById.isEmpty()) return emptyList()

        val invoicesById = invoices.associateBy { it.id }
        val authoritativeInvoiceMonths = invoices
            .filter { it.total.signum() != 0 && it.dueDate != null }
            .mapTo(mutableSetOf()) { invoice ->
                invoice.accountId to YearMonth.from(checkNotNull(invoice.dueDate))
            }

        val raw = buildList {
            transactions.forEach { transaction ->
                if (
                    transaction.type != FinancialTransactionType.CARD_PURCHASE ||
                    transaction.status != TransactionStatus.PENDING
                ) return@forEach

                val accountId = transaction.accountId ?: return@forEach
                val account = cardsById[accountId] ?: return@forEach
                val installment = transaction.seriesIndex ?: return@forEach
                val totalInstallments = transaction.seriesTotal ?: return@forEach
                if (installment !in 1 until totalInstallments) return@forEach

                val amount = transaction.amount.toBigDecimalOrNull()
                    ?.takeIf { it.signum() > 0 }
                    ?: return@forEach
                val transactionDate = transaction.occurredAt.take(10).let { rawDate ->
                    runCatching { LocalDate.parse(rawDate) }.getOrNull()
                } ?: return@forEach

                val linkedDueDate = transaction.invoiceId
                    ?.let(invoicesById::get)
                    ?.dueDate
                    // A purchase cannot belong to an invoice that was already due before it
                    // occurred. Old malformed links must not shift the future schedule backwards.
                    ?.takeIf { !it.isBefore(transactionDate) }
                val currentDueDate = linkedDueDate ?: CreditCardBillingCycle.calculate(
                    purchaseDate = transactionDate,
                    closingDay = checkNotNull(account.closingDay),
                    dueDay = checkNotNull(account.dueDay),
                ).dueDate ?: return@forEach

                val remaining = totalInstallments - installment
                for (offset in 1..remaining) {
                    val dueDate = currentDueDate.plusMonths(offset.toLong())
                    if (!dueDate.isAfter(asOfDate)) continue
                    // Once Pluggy supplies an actual non-zero invoice for that card/month, the
                    // official invoice is authoritative and replaces our inferred projection.
                    if (accountId to YearMonth.from(dueDate) in authoritativeInvoiceMonths) continue
                    add(
                        FutureCardInstallmentProjection(
                            accountId = accountId,
                            accountName = account.name,
                            dueDate = dueDate,
                            amount = amount,
                        ),
                    )
                }
            }
        }

        return raw
            .groupBy { Triple(it.accountId, it.accountName, it.dueDate) }
            .map { (key, items) ->
                FutureCardInstallmentProjection(
                    accountId = key.first,
                    accountName = key.second,
                    dueDate = key.third,
                    amount = items.fold(BigDecimal.ZERO) { total, item -> total + item.amount },
                )
            }
            .sortedWith(compareBy(FutureCardInstallmentProjection::dueDate, FutureCardInstallmentProjection::accountId))
    }
}
