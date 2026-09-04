package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BudgetAlertPolicyTest {
    @Test
    fun exactBudgetLimitDoesNotTriggerExceededBand() {
        assertNull(
            BudgetAlertPolicy.highestReachedLevel(
                limit = BigDecimal("100.00"),
                projected = BigDecimal("100.00"),
            ),
        )
    }

    @Test
    fun firstExceededBandStartsAboveOneHundredPercent() {
        assertEquals(
            100,
            BudgetAlertPolicy.highestReachedLevel(
                limit = BigDecimal("100.00"),
                projected = BigDecimal("100.01"),
            ),
        )
    }

    @Test
    fun returnsHighestReachedProgressiveBand() {
        assertEquals(
            110,
            BudgetAlertPolicy.highestReachedLevel(
                limit = BigDecimal("100.00"),
                projected = BigDecimal("112.00"),
            ),
        )
        assertEquals(
            125,
            BudgetAlertPolicy.highestReachedLevel(
                limit = BigDecimal("100.00"),
                projected = BigDecimal("130.00"),
            ),
        )
        assertEquals(
            150,
            BudgetAlertPolicy.highestReachedLevel(
                limit = BigDecimal("100.00"),
                projected = BigDecimal("175.00"),
            ),
        )
        assertEquals(
            200,
            BudgetAlertPolicy.highestReachedLevel(
                limit = BigDecimal("100.00"),
                projected = BigDecimal("250.00"),
            ),
        )
    }

    @Test
    fun nextBandRequiresItsOwnThreshold() {
        assertEquals(
            110,
            BudgetAlertPolicy.highestReachedLevel(
                limit = BigDecimal("100.00"),
                projected = BigDecimal("124.99"),
            ),
        )
        assertEquals(
            125,
            BudgetAlertPolicy.highestReachedLevel(
                limit = BigDecimal("100.00"),
                projected = BigDecimal("125.00"),
            ),
        )
    }

    @Test
    fun alertStateIsScopedByMonthAndCategory() {
        val august = BudgetAlertPolicy.stateKey(
            YearMonth.of(2026, 8),
            TransactionCategory.FOOD.name,
        )
        val september = BudgetAlertPolicy.stateKey(
            YearMonth.of(2026, 9),
            TransactionCategory.FOOD.name,
        )
        assertNotEquals(august, september)
    }

    @Test
    fun budgetLimitStateIsSeparateFromAlertLevelState() {
        val period = YearMonth.of(2026, 9)
        val category = TransactionCategory.FOOD.name
        assertNotEquals(
            BudgetAlertPolicy.stateKey(period, category),
            BudgetAlertPolicy.limitKey(period, category),
        )
    }

    @Test
    fun notificationKeyChangesForEachBand() {
        val period = YearMonth.of(2026, 9)
        val category = TransactionCategory.FOOD.name
        assertNotEquals(
            BudgetAlertPolicy.notificationKey(period, category, 100),
            BudgetAlertPolicy.notificationKey(period, category, 110),
        )
    }
}
