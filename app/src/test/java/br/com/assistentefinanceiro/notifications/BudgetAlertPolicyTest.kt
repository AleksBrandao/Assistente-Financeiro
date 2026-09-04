package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.time.YearMonth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetAlertPolicyTest {
    @Test
    fun alertsOnlyWhenSpendingCrossesFromWithinLimitToAboveIt() {
        assertTrue(
            BudgetAlertPolicy.crossedLimit(
                limit = BigDecimal("500.00"),
                before = BigDecimal("490.00"),
                after = BigDecimal("510.00"),
            ),
        )
    }

    @Test
    fun exactLimitDoesNotCountAsExceeded() {
        assertFalse(
            BudgetAlertPolicy.crossedLimit(
                limit = BigDecimal("500.00"),
                before = BigDecimal("490.00"),
                after = BigDecimal("500.00"),
            ),
        )
    }

    @Test
    fun doesNotAlertAgainWhenCategoryWasAlreadyAboveLimit() {
        assertFalse(
            BudgetAlertPolicy.crossedLimit(
                limit = BigDecimal("500.00"),
                before = BigDecimal("510.00"),
                after = BigDecimal("540.00"),
            ),
        )
    }

    @Test
    fun alertKeyIsScopedByMonthAndCategory() {
        val august = BudgetAlertPolicy.alertKey(
            YearMonth.of(2026, 8),
            TransactionCategory.FOOD.name,
        )
        val september = BudgetAlertPolicy.alertKey(
            YearMonth.of(2026, 9),
            TransactionCategory.FOOD.name,
        )
        assertNotEquals(august, september)
    }
}
