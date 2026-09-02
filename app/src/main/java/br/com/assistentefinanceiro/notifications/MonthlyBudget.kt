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

object MonthlyBudgetCalculator {
    fun calculate(
        period: YearMonth,
        budgets: List<MonthlyBudgetRecord>,
        transactions: List<FinancialTransactionRecord>,
    ): List<MonthlyBudgetProgress> {
        val expenses = transactions.mapNotNull { transaction ->
            if (transaction.direction != FinancialTransactionDirection.EXPENSE) return@mapNotNull null
            val date = effectiveDate(transaction) ?: return@mapNotNull null
            if (YearMonth.from(date) != period) return@mapNotNull null
            val amount = transaction.amount.toBigDecimalOrNull()
                ?.takeIf { it.signum() >= 0 } ?: return@mapNotNull null
            BudgetExpense(
                transaction.category,
                transaction.customCategory,
                transaction.status,
                amount,
            )
        }
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
}
