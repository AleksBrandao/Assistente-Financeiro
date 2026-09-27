package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class BalanceAdjustmentCalculatorTest {
    @Test
    fun createsPositiveAdjustmentWhenInformedBalanceIsHigher() {
        assertEquals(
            BigDecimal("150.00"),
            BalanceAdjustmentCalculator.difference(
                currentBalance = BigDecimal("1000.00"),
                informedBalance = BigDecimal("1150.00"),
            ),
        )
    }

    @Test
    fun createsNegativeAdjustmentWhenInformedBalanceIsLower() {
        assertEquals(
            BigDecimal("-125.50"),
            BalanceAdjustmentCalculator.difference(
                currentBalance = BigDecimal("1000.00"),
                informedBalance = BigDecimal("874.50"),
            ),
        )
    }
}
