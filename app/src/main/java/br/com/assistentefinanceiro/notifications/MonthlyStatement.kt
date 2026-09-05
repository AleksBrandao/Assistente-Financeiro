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

data class StatementCalculationEntry(
    val transaction: FinancialTransactionRecord,
    val realizedAmount: BigDecimal? = null,
    val pendingAmount: BigDecimal? = null,
)

data class MonthlyStatement(
    val period: YearMonth,
    val totalIncome: BigDecimal,
    val totalExpense: BigDecimal,
    val balance: BigDecimal,
    val pendingIncome: BigDecimal = BigDecimal.ZERO,
    val pendingExpense: BigDecimal = BigDecimal.ZERO,
    val groups: List<DailyTransactionGroup>,
    val categoryExpenses: List<CategoryExpenseSummary> = emptyList(),
) {
    val transactionCount: Int
        get() = groups.sumOf { it.transactions.size }

    val projectedIncome: BigDecimal
        get() = totalIncome + pendingIncome

    val projectedExpense: BigDecimal
        get() = totalExpense + pendingExpense

    val projectedBalance: BigDecimal
        get() = projectedIncome - projectedExpense
}

object MonthlyStatementCalculator {
    fun calculate(
        period: YearMonth,
        transactions: List<FinancialTransactionRecord>,
    ): MonthlyStatement = calculateEntries(
        period = period,
        entries = transactions.map { transaction -> StatementCalculationEntry(transaction) },
    )

    fun calculateEntries(
        period: YearMonth,
        entries: List<StatementCalculationEntry>,
    ): MonthlyStatement {
        val normalized = entries.mapNotNull { entry ->
            val transaction = entry.transaction
            val originalOccurredAt = runCatching {
                LocalDateTime.parse(transaction.occurredAt)
            }.getOrNull() ?: return@mapNotNull null
            val splitAmounts = entry.realizedAmount != null || entry.pendingAmount != null
            val effectiveDate = if (splitAmounts) {
                null
            } else {
                when (transaction.status) {
                    TransactionStatus.REALIZED -> transaction.paidAt?.let {
                        runCatching { LocalDate.parse(it) }.getOrNull()
                    }
                    TransactionStatus.PENDING ->
                        (transaction.plannedPaymentDate ?: transaction.dueDate)?.let {
                            runCatching { LocalDate.parse(it) }.getOrNull()
                        }
                }
            }
            val occurredAt = effectiveDate?.atStartOfDay() ?: originalOccurredAt
            val amount = transaction.amount.toBigDecimalOrNull()
                ?.takeIf { it.signum() >= 0 }
                ?: return@mapNotNull null
            val realizedAmount = (entry.realizedAmount ?: if (
                transaction.status == TransactionStatus.REALIZED
            ) amount else BigDecimal.ZERO).takeIf { it.signum() >= 0 }
                ?: return@mapNotNull null
            val pendingAmount = (entry.pendingAmount ?: if (
                transaction.status == TransactionStatus.PENDING
            ) amount else BigDecimal.ZERO).takeIf { it.signum() >= 0 }
                ?: return@mapNotNull null

            NormalizedTransaction(
                transaction = transaction,
                occurredAt = occurredAt,
                realizedAmount = realizedAmount,
                pendingAmount = pendingAmount,
            )
        }.filter { YearMonth.from(it.occurredAt) == period }

        val incomeItems = normalized.filter {
            it.transaction.direction == FinancialTransactionDirection.INCOME
        }
        val expenseItems = normalized.filter {
            it.transaction.direction == FinancialTransactionDirection.EXPENSE
        }
        val totalIncome = incomeItems.fold(BigDecimal.ZERO) { total, item ->
            total + item.realizedAmount
        }
        val totalExpense = expenseItems.fold(BigDecimal.ZERO) { total, item ->
            total + item.realizedAmount
        }
        val pendingIncome = incomeItems.fold(BigDecimal.ZERO) { total, item ->
            total + item.pendingAmount
        }
        val pendingExpense = expenseItems.fold(BigDecimal.ZERO) { total, item ->
            total + item.pendingAmount
        }
        val projectedExpense = totalExpense + pendingExpense
        val categoryExpenses = expenseItems
            .groupBy { it.transaction.category }
            .map { (category, items) ->
                val categoryTotal = items.fold(BigDecimal.ZERO) { total, item ->
                    total + item.realizedAmount + item.pendingAmount
                }
                CategoryExpenseSummary(
                    category = category,
                    total = categoryTotal,
                    transactionCount = items.size,
                    sharePercent = percentageOf(categoryTotal, projectedExpense),
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
            pendingIncome = pendingIncome,
            pendingExpense = pendingExpense,
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
        val realizedAmount: BigDecimal,
        val pendingAmount: BigDecimal,
    )
}
