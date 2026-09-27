package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

data class MonthlyBudgetRecord(
    val period: YearMonth,
    val category: TransactionCategory?,
    val amount: BigDecimal,
    val customCategory: String? = null,
) {
    val categoryKey: String?
        get() = customCategory?.let { "CUSTOM:$it" } ?: category?.name
    val displayName: String
        get() = customCategory ?: category?.displayName ?: "Limite total do mês"
}

data class MonthlyBudgetProgress(
    val category: TransactionCategory?,
    val limit: BigDecimal,
    val realized: BigDecimal,
    val pending: BigDecimal,
    val customCategory: String? = null,
) {
    val categoryKey: String?
        get() = customCategory?.let { "CUSTOM:$it" } ?: category?.name
    val displayName: String
        get() = customCategory ?: category?.displayName ?: "Orçamento total"
    val projected: BigDecimal get() = realized + pending
    val remaining: BigDecimal get() = limit - projected
    val usagePercent: Int
        get() = if (limit.signum() <= 0) 0 else projected
            .multiply(BigDecimal(100))
            .divide(limit, 0, RoundingMode.HALF_UP)
            .toInt()
}

data class MonthlyCategorySpending(
    val category: TransactionCategory,
    val amount: BigDecimal,
    val sharePercent: Int,
    val transactionCount: Int,
    val customCategory: String? = null,
) {
    val categoryKey: String
        get() = customCategory?.let { "CUSTOM:$it" } ?: category.name
    val displayName: String
        get() = customCategory ?: category.displayName
}

object MonthlyBudgetCalculator {
    fun calculate(
        period: YearMonth,
        budgets: List<MonthlyBudgetRecord>,
        transactions: List<FinancialTransactionRecord>,
    ): List<MonthlyBudgetProgress> {
        val expenses = expensesFor(period, transactions)
        return budgets.sortedWith(compareBy<MonthlyBudgetRecord> { it.categoryKey != null }
            .thenBy { it.displayName })
            .map { budget ->
                val matching = if (budget.categoryKey == null) expenses else expenses.filter {
                    if (budget.customCategory != null) {
                        it.customCategory == budget.customCategory
                    } else {
                        it.customCategory == null && it.category == budget.category
                    }
                }
                MonthlyBudgetProgress(
                    category = budget.category,
                    customCategory = budget.customCategory,
                    limit = budget.amount,
                    realized = matching.filter { it.status == TransactionStatus.REALIZED }
                        .fold(BigDecimal.ZERO) { total, item -> total + item.amount },
                    pending = matching.filter { it.status == TransactionStatus.PENDING }
                        .fold(BigDecimal.ZERO) { total, item -> total + item.amount },
                )
            }
    }

    fun spendingByCategory(
        period: YearMonth,
        transactions: List<FinancialTransactionRecord>,
    ): List<MonthlyCategorySpending> {
        val expenses = expensesFor(period, transactions).filter { it.amount.signum() > 0 }
        val total = expenses.fold(BigDecimal.ZERO) { sum, expense -> sum + expense.amount }
        return expenses
            .groupBy { BudgetCategory(it.category, it.customCategory) }
            .map { (category, items) ->
                val categoryTotal = items.fold(BigDecimal.ZERO) { sum, item ->
                    sum + item.amount
                }
                MonthlyCategorySpending(
                    category = category.category,
                    customCategory = category.customCategory,
                    amount = categoryTotal,
                    sharePercent = percentageOf(categoryTotal, total),
                    transactionCount = items.size,
                )
            }
            .sortedWith(
                compareByDescending<MonthlyCategorySpending> { it.amount }
                    .thenBy { it.displayName },
            )
    }

    private fun expensesFor(
        period: YearMonth,
        transactions: List<FinancialTransactionRecord>,
    ): List<BudgetExpense> = transactions.mapNotNull { transaction ->
        if (transaction.direction != FinancialTransactionDirection.EXPENSE) return@mapNotNull null
        val date = effectiveDate(transaction) ?: return@mapNotNull null
        if (YearMonth.from(date) != period) return@mapNotNull null
        val amount = transaction.amount.toBigDecimalOrNull()
            ?.takeIf { it.signum() >= 0 } ?: return@mapNotNull null
        BudgetExpense(
            transaction.category,
            transaction.customCategory?.trim()?.takeIf(String::isNotEmpty),
            transaction.status,
            amount,
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

    private fun effectiveDate(transaction: FinancialTransactionRecord): LocalDate? {
        val stored = if (transaction.status == TransactionStatus.REALIZED) {
            transaction.paidAt
        } else transaction.plannedPaymentDate ?: transaction.dueDate
        return stored?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: runCatching { LocalDateTime.parse(transaction.occurredAt).toLocalDate() }.getOrNull()
    }

    private data class BudgetExpense(
        val category: TransactionCategory,
        val customCategory: String?,
        val status: TransactionStatus,
        val amount: BigDecimal,
    )

    private data class BudgetCategory(
        val category: TransactionCategory,
        val customCategory: String?,
    )
}
