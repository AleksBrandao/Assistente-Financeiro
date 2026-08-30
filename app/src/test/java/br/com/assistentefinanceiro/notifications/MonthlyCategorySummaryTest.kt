package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthlyCategorySummaryTest {
    @Test
    fun calculate_groupsOnlyExpensesByCategory() {
        val statement = MonthlyStatementCalculator.calculate(
            period = YearMonth.of(2026, 8),
            transactions = listOf(
                transaction(
                    id = 1,
                    direction = FinancialTransactionDirection.EXPENSE,
                    type = FinancialTransactionType.CARD_PURCHASE,
                    amount = "30.00",
                    category = TransactionCategory.FOOD,
                ),
                transaction(
                    id = 2,
                    direction = FinancialTransactionDirection.EXPENSE,
                    type = FinancialTransactionType.CARD_PURCHASE,
                    amount = "20.00",
                    category = TransactionCategory.FOOD,
                ),
                transaction(
                    id = 3,
                    direction = FinancialTransactionDirection.EXPENSE,
                    type = FinancialTransactionType.CARD_PURCHASE,
                    amount = "50.00",
                    category = TransactionCategory.TRANSPORT,
                ),
                transaction(
                    id = 4,
                    direction = FinancialTransactionDirection.INCOME,
                    type = FinancialTransactionType.PIX_RECEIVED,
                    amount = "100.00",
                    category = TransactionCategory.TRANSFER_IN,
                ),
            ),
        )

        assertEquals(2, statement.categoryExpenses.size)
        assertEquals(TransactionCategory.FOOD, statement.categoryExpenses[0].category)
        assertEquals(0, statement.categoryExpenses[0].total.compareTo(BigDecimal("50.00")))
        assertEquals(2, statement.categoryExpenses[0].transactionCount)
        assertEquals(50, statement.categoryExpenses[0].sharePercent)
        assertEquals(TransactionCategory.TRANSPORT, statement.categoryExpenses[1].category)
        assertEquals(50, statement.categoryExpenses[1].sharePercent)
    }

    @Test
    fun calculate_sortsCategoriesByDescendingTotal() {
        val statement = MonthlyStatementCalculator.calculate(
            period = YearMonth.of(2026, 8),
            transactions = listOf(
                transaction(
                    id = 1,
                    direction = FinancialTransactionDirection.EXPENSE,
                    type = FinancialTransactionType.CARD_PURCHASE,
                    amount = "25.00",
                    category = TransactionCategory.FOOD,
                ),
                transaction(
                    id = 2,
                    direction = FinancialTransactionDirection.EXPENSE,
                    type = FinancialTransactionType.CARD_PURCHASE,
                    amount = "75.00",
                    category = TransactionCategory.SHOPPING,
                ),
            ),
        )

        assertEquals(TransactionCategory.SHOPPING, statement.categoryExpenses[0].category)
        assertEquals(75, statement.categoryExpenses[0].sharePercent)
        assertEquals(TransactionCategory.FOOD, statement.categoryExpenses[1].category)
        assertEquals(25, statement.categoryExpenses[1].sharePercent)
    }

    @Test
    fun calculate_returnsNoCategoriesWhenMonthHasNoExpenses() {
        val statement = MonthlyStatementCalculator.calculate(
            period = YearMonth.of(2026, 8),
            transactions = listOf(
                transaction(
                    id = 1,
                    direction = FinancialTransactionDirection.INCOME,
                    type = FinancialTransactionType.PIX_RECEIVED,
                    amount = "100.00",
                    category = TransactionCategory.TRANSFER_IN,
                ),
            ),
        )

        assertTrue(statement.categoryExpenses.isEmpty())
    }

    private fun transaction(
        id: Long,
        direction: FinancialTransactionDirection,
        type: FinancialTransactionType,
        amount: String,
        category: TransactionCategory,
    ) = FinancialTransactionRecord(
        id = id,
        sourceEventId = id,
        direction = direction,
        type = type,
        amount = amount,
        occurredAt = "2026-08-29T12:30:00",
        description = "Teste",
        sourcePackage = "com.santander.app",
        category = category,
    )
}
