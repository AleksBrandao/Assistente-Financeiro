package br.com.assistentefinanceiro.notifications

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingTransactionTest {
    @Test
    fun separatesPendingTransactionFromRealizedResult() {
        val transaction = FinancialTransactionRecord(
            id = 1,
            sourceEventId = null,
            direction = FinancialTransactionDirection.EXPENSE,
            type = FinancialTransactionType.IMPORTED_EXPENSE,
            amount = "100.00",
            occurredAt = "2026-09-10T00:00:00",
            description = "Parcela",
            sourcePackage = "MOBILLS",
            status = TransactionStatus.PENDING,
            origin = TransactionOrigin.MOBILLS,
        )

        val statement = MonthlyStatementCalculator.calculate(
            YearMonth.of(2026, 9), listOf(transaction)
        )

        assertEquals(1, statement.transactionCount)
        assertEquals("0", statement.totalExpense.toPlainString())
        assertEquals("100.00", statement.pendingExpense.toPlainString())
        assertEquals("100.00", statement.projectedExpense.toPlainString())
    }
}
