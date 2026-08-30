package br.com.assistentefinanceiro.notifications

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class PlannedTransactionTest {
    @Test
    fun excludesPlannedTransactionUntilItsDate() {
        val transaction = FinancialTransactionRecord(
            id = 1,
            sourceEventId = null,
            direction = FinancialTransactionDirection.EXPENSE,
            type = FinancialTransactionType.IMPORTED_EXPENSE,
            amount = "100.00",
            occurredAt = "2026-09-10T00:00:00",
            description = "Parcela",
            sourcePackage = "MOBILLS",
            status = TransactionStatus.PLANNED,
            origin = TransactionOrigin.MOBILLS,
        )

        val before = MonthlyStatementCalculator.calculate(
            YearMonth.of(2026, 9), listOf(transaction), LocalDate.of(2026, 8, 30)
        )
        val onDate = MonthlyStatementCalculator.calculate(
            YearMonth.of(2026, 9), listOf(transaction), LocalDate.of(2026, 9, 10)
        )

        assertEquals(0, before.transactionCount)
        assertEquals(1, onDate.transactionCount)
    }
}
