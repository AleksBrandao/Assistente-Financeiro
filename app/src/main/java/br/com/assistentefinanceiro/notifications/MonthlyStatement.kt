package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

data class DailyTransactionGroup(
    val date: LocalDate,
    val transactions: List<FinancialTransactionRecord>,
)

data class CategoryExpenseSummary(
    val category: TransactionCategory,
    val total: BigDecimal,
    val transactionCount: Int,
    val sharePercent: Int,
)

data class MonthlyStatement(
    val period: YearMonth,
    val totalIncome: BigDecimal,
    val totalExpense: BigDecimal,
    val balance: BigDecimal,
    val groups: List<DailyTransactionGroup>,
    val categoryExpenses: List<CategoryExpenseSummary> = emptyList(),
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

        val incomeItems = normalized.filter {
            it.transaction.direction == FinancialTransactionDirection.INCOME
        }
        val expenseItems = normalized.filter {
            it.transaction.direction == FinancialTransactionDirection.EXPENSE
        }
        val totalIncome = incomeItems.fold(BigDecimal.ZERO) { total, item ->
            total + item.amount
        }
        val totalExpense = expenseItems.fold(BigDecimal.ZERO) { total, item ->
            total + item.amount
        }
        val categoryExpenses = expenseItems
            .groupBy { it.transaction.category }
            .map { (category, items) ->
                val categoryTotal = items.fold(BigDecimal.ZERO) { total, item ->
                    total + item.amount
                }
                CategoryExpenseSummary(
                    category = category,
                    total = categoryTotal,
                    transactionCount = items.size,
                    sharePercent = percentageOf(categoryTotal, totalExpense),
                )
            }
            .sortedWith(
                compareByDescending<CategoryExpenseSummary> { it.total }
                    .thenBy { it.category.displayName }
            )

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
            categoryExpenses = categoryExpenses,
            groups = groups,
        )
    }

    private fun percentageOf(value: BigDecimal, total: BigDecimal): Int {
        if (total.signum() == 0) return 0
        return value
            .multiply(BigDecimal(100))
            .divide(total, 0, RoundingMode.HALF_UP)
            .toInt()
            .coerceIn(0, 100)
    }

    private data class NormalizedTransaction(
        val transaction: FinancialTransactionRecord,
        val occurredAt: LocalDateTime,
        val amount: BigDecimal,
    )
}
