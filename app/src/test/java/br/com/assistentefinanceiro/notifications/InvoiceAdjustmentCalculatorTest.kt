package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class InvoiceAdjustmentCalculatorTest {
    @Test
    fun `total oficial maior gera debito`() {
        assertEquals(
            BigDecimal("15.50"),
            InvoiceAdjustmentCalculator.difference(BigDecimal("100.00"), BigDecimal("115.50")),
        )
    }

    @Test
    fun `total oficial menor gera credito`() {
        assertEquals(
            BigDecimal("-20.00"),
            InvoiceAdjustmentCalculator.difference(BigDecimal("100.00"), BigDecimal("80.00")),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `total oficial negativo e rejeitado`() {
        InvoiceAdjustmentCalculator.difference(BigDecimal("100.00"), BigDecimal("-1.00"))
    }
}
