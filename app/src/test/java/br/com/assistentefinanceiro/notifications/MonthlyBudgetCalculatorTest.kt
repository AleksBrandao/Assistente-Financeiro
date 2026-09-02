package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class MonthlyBudgetCalculatorTest {
    @Test
    fun separatesRealizedAndPendingAndCalculatesTotalBudget() {
        val period = YearMonth.of(2026, 9)
        val transactions = listOf(
            expense(1, "300", TransactionStatus.REALIZED, TransactionCategory.FOOD, "2026-09-03"),
            expense(2, "250", TransactionStatus.PENDING, TransactionCategory.FOOD, "2026-09-20"),
            expense(3, "400", TransactionStatus.PENDING, TransactionCategory.HOUSING, "2026-09-10"),
            expense(4, "999", TransactionStatus.REALIZED, TransactionCategory.FOOD, "2026-08-03"),
        )
        val progress = MonthlyBudgetCalculator.calculate(
            period,
            listOf(
                MonthlyBudgetRecord(period, null, BigDecimal("2000")),
                MonthlyBudgetRecord(period, TransactionCategory.FOOD, BigDecimal("500")),
            ),
            transactions,
        )

        assertEquals(BigDecimal("300"), progress[0].realized)
        assertEquals(BigDecimal("650"), progress[0].pending)
        assertEquals(BigDecimal("1050"), progress[0].remaining)
        assertEquals(48, progress[0].usagePercent)
        assertEquals(BigDecimal("-50"), progress[1].remaining)
        assertEquals(110, progress[1].usagePercent)
    }

    private fun expense(
        id: Long,
        amount: String,
        status: TransactionStatus,
        category: TransactionCategory,
        date: String,
    ) = FinancialTransactionRecord(
        id = id,
        sourceEventId = null,
        direction = FinancialTransactionDirection.EXPENSE,
        type = FinancialTransactionType.MANUAL_EXPENSE,
        amount = amount,
        occurredAt = date + "T00:00",
        description = "Teste",
        sourcePackage = "TEST",
        category = category,
        status = status,
        dueDate = date,
        paidAt = date.takeIf { status == TransactionStatus.REALIZED },
        plannedPaymentDate = date.takeIf { status == TransactionStatus.PENDING },
    )
}
