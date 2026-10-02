package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class InstallmentPlanCalculatorTest {
    @Test
    fun splitsTotalExactlyAcrossInstallments() {
        val parts = InstallmentPlanCalculator.split(BigDecimal("1418.00"), 3)

        assertEquals(
            listOf(
                BigDecimal("472.67"),
                BigDecimal("472.67"),
                BigDecimal("472.66"),
            ),
            parts,
        )
        assertEquals(BigDecimal("1418.00"), parts.reduce(BigDecimal::add))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSingleInstallment() {
        InstallmentPlanCalculator.split(BigDecimal("100.00"), 1)
    }
}
