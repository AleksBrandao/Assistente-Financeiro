package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.math.RoundingMode

object InstallmentPlanCalculator {
    fun split(total: BigDecimal, installments: Int): List<BigDecimal> {
        require(installments in 2..48)
        require(total.signum() > 0)

        val normalized = total.setScale(2, RoundingMode.HALF_UP)
        val cents = normalized.movePointRight(2).longValueExact()
        val base = cents / installments
        val remainder = cents % installments

        return List(installments) { index ->
            val installmentCents = base + if (index < remainder) 1 else 0
            BigDecimal.valueOf(installmentCents, 2)
        }
    }
}
