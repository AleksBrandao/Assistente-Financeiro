package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class MonthlyCategorySpendingCalculatorTest {
    @Test
    fun ranksEverySpentCategoryWithoutDependingOnConfiguredBudgets() {
        val period = YearMonth.of(2026, 9)
        val invoiceId = 42L
        val transactions = listOf(
            expense(1, "300", TransactionCategory.FOOD, invoiceId),
            expense(2, "150", TransactionCategory.TRANSPORT, invoiceId),
            expense(3, "999", TransactionCategory.HOUSING, invoiceId)
                .copy(occurredAt = "2026-08-10T00:00", paidAt = "2026-08-10"),
            expense(4, "1000", TransactionCategory.SALARY, invoiceId)
                .copy(direction = FinancialTransactionDirection.INCOME),
        )

        val spending = MonthlyBudgetCalculator.spendingByCategory(period, transactions)

        assertEquals(2, spending.size)
        assertEquals(TransactionCategory.FOOD, spending[0].category)
        assertEquals(BigDecimal("300"), spending[0].amount)
        assertEquals(67, spending[0].sharePercent)
        assertEquals(TransactionCategory.TRANSPORT, spending[1].category)
        assertEquals(BigDecimal("150"), spending[1].amount)
        assertEquals(33, spending[1].sharePercent)
    }

    private fun expense(
        id: Long,
        amount: String,
        category: TransactionCategory,
        invoiceId: Long,
    ) = FinancialTransactionRecord(
        id = id,
        sourceEventId = null,
        direction = FinancialTransactionDirection.EXPENSE,
        type = FinancialTransactionType.CARD_PURCHASE,
        amount = amount,
        occurredAt = "2026-09-10T00:00",
        description = "Compra no cartão",
        sourcePackage = "TEST",
        category = category,
        status = TransactionStatus.REALIZED,
        accountId = 7L,
        invoiceId = invoiceId,
        paidAt = "2026-09-10",
    )
}
