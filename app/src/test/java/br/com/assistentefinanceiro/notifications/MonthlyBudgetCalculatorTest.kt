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

    @Test
    fun keepsCustomCategorySeparateFromFallbackCategory() {
        val period = YearMonth.of(2026, 9)
        val custom = expense(
            1, "120", TransactionStatus.PENDING,
            TransactionCategory.OTHER_EXPENSE, "2026-09-10",
        ).copy(customCategory = "Animais", subcategory = "Ração")
        val ordinary = expense(
            2, "80", TransactionStatus.PENDING,
            TransactionCategory.OTHER_EXPENSE, "2026-09-11",
        )

        val progress = MonthlyBudgetCalculator.calculate(
            period,
            listOf(
                MonthlyBudgetRecord(
                    period, TransactionCategory.OTHER_EXPENSE,
                    BigDecimal("200"), customCategory = "Animais",
                ),
                MonthlyBudgetRecord(
                    period, TransactionCategory.OTHER_EXPENSE, BigDecimal("200"),
                ),
            ),
            listOf(custom, ordinary),
        )

        assertEquals("Animais", progress[0].displayName)
        assertEquals(BigDecimal("120"), progress[0].pending)
        assertEquals(BigDecimal("80"), progress[1].pending)
    }

    @Test
    fun keepsCreditCardPurchasesInTheirOriginalBudgetCategories() {
        val period = YearMonth.of(2026, 9)
        val invoiceId = 42L
        val transactions = listOf(
            expense(
                1, "300", TransactionStatus.REALIZED,
                TransactionCategory.FOOD, "2026-09-10",
            ).copy(
                type = FinancialTransactionType.CARD_PURCHASE,
                accountId = 7L,
                invoiceId = invoiceId,
            ),
            expense(
                2, "150", TransactionStatus.REALIZED,
                TransactionCategory.TRANSPORT, "2026-09-10",
            ).copy(
                type = FinancialTransactionType.CARD_PURCHASE,
                accountId = 7L,
                invoiceId = invoiceId,
            ),
        )

        val progress = MonthlyBudgetCalculator.calculate(
            period,
            listOf(
                MonthlyBudgetRecord(period, null, BigDecimal("1000")),
                MonthlyBudgetRecord(period, TransactionCategory.FOOD, BigDecimal("500")),
                MonthlyBudgetRecord(period, TransactionCategory.TRANSPORT, BigDecimal("300")),
            ),
            transactions,
        ).associateBy { it.category }

        assertEquals(BigDecimal("450"), progress.getValue(null).realized)
        assertEquals(BigDecimal("300"), progress.getValue(TransactionCategory.FOOD).realized)
        assertEquals(BigDecimal("150"), progress.getValue(TransactionCategory.TRANSPORT).realized)
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
