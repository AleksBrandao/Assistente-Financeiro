package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountBalanceCalculatorTest {
    @Test
    fun combinesOpeningBalanceRealizedAndPendingTransactions() {
        val result = AccountBalanceCalculator.calculate(
            openingBalance = BigDecimal("1000.00"),
            transactions = listOf(
                AccountBalanceEntry(
                    FinancialTransactionDirection.INCOME,
                    BigDecimal("200.00"),
                    TransactionStatus.REALIZED,
                ),
                AccountBalanceEntry(
                    FinancialTransactionDirection.EXPENSE,
                    BigDecimal("50.00"),
                    TransactionStatus.REALIZED,
                ),
                AccountBalanceEntry(
                    FinancialTransactionDirection.EXPENSE,
                    BigDecimal("80.00"),
                    TransactionStatus.PENDING,
                ),
            ),
            movements = emptyList(),
        )

        assertEquals(BigDecimal("1150.00"), result.realizedBalance)
        assertEquals(BigDecimal("1070.00"), result.projectedBalance)
        assertEquals(BigDecimal("80.00"), result.pendingExpense)
    }

    @Test
    fun transferChangesAccountsButNotConsolidatedBalance() {
        val debit = AccountMovementRecord(
            id = 1,
            direction = AccountMovementDirection.DEBIT,
            type = AccountMovementType.TRANSFER,
            amount = BigDecimal("250.00"),
            occurredAt = LocalDate.of(2026, 8, 30),
            description = "Transferência",
        )
        val credit = debit.copy(id = 2, direction = AccountMovementDirection.CREDIT)

        val source = AccountBalanceCalculator.calculate(
            BigDecimal("1000.00"), emptyList(), listOf(debit),
        )
        val destination = AccountBalanceCalculator.calculate(
            BigDecimal("500.00"), emptyList(), listOf(credit),
        )

        assertEquals(BigDecimal("750.00"), source.realizedBalance)
        assertEquals(BigDecimal("750.00"), destination.realizedBalance)
        assertEquals(
            BigDecimal("1500.00"),
            source.realizedBalance + destination.realizedBalance,
        )
    }
}
