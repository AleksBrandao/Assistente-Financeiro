package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

data class DailyTransactionGroup(
    val date: LocalDate,
    val transactions: List<FinancialTransactionRecord>,
)

data class MonthlyStatement(
    val period: YearMonth,
    val totalIncome: BigDecimal,
    val totalExpense: BigDecimal,
    val balance: BigDecimal,
    val groups: List<DailyTransactionGroup>,
) {
    val transactionCount: Int
        get() = groups.sumOf { it.transactions.size }
}

object MonthlyStatementCalculator {
    fun calculate(
        period: YearMonth,
        transactions: List<FinancialTransactionRecord>,
    ): MonthlyStatement {
        val normalized = transactions.mapNotNull { transaction ->
            val occurredAt = runCatching {
                LocalDateTime.parse(transaction.occurredAt)
            }.getOrNull() ?: return@mapNotNull null
            val amount = transaction.amount.toBigDecimalOrNull()
                ?.takeIf { it.signum() >= 0 }
                ?: return@mapNotNull null

            NormalizedTransaction(transaction, occurredAt, amount)
        }.filter { YearMonth.from(it.occurredAt) == period }

        val totalIncome = normalized
            .filter { it.transaction.direction == FinancialTransactionDirection.INCOME }
            .fold(BigDecimal.ZERO) { total, item -> total + item.amount }
        val totalExpense = normalized
            .filter { it.transaction.direction == FinancialTransactionDirection.EXPENSE }
            .fold(BigDecimal.ZERO) { total, item -> total + item.amount }

        val groups = normalized
            .groupBy { it.occurredAt.toLocalDate() }
            .toList()
            .sortedByDescending { (date, _) -> date }
            .map { (date, items) ->
                DailyTransactionGroup(
                    date = date,
                    transactions = items
                        .sortedByDescending { it.occurredAt }
                        .map { it.transaction },
                )
            }

        return MonthlyStatement(
            period = period,
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            balance = totalIncome - totalExpense,
            groups = groups,
        )
    }

    private data class NormalizedTransaction(
        val transaction: FinancialTransactionRecord,
        val occurredAt: LocalDateTime,
        val amount: BigDecimal,
    )
}
