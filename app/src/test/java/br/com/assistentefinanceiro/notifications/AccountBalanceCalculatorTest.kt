package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun forwardProjectionUsesCurrentRealizedBalanceAndOnlyOpenPendingItems() {
        val current = AccountBalanceSummary(
            realizedBalance = BigDecimal("9252.95"),
            projectedBalance = BigDecimal("9252.95"),
            pendingIncome = BigDecimal.ZERO,
            pendingExpense = BigDecimal.ZERO,
        )
        val throughMonthEnd = AccountBalanceSummary(
            // Um valor realizado futuro não deve ser reaplicado depois de o saldo atual
            // já ter sido conciliado manualmente.
            realizedBalance = BigDecimal("16875.90"),
            projectedBalance = BigDecimal("16652.08"),
            pendingIncome = BigDecimal.ZERO,
            pendingExpense = BigDecimal("223.82"),
        )

        assertEquals(
            BigDecimal("9029.13"),
            ForwardProjectedBalanceCalculator.calculate(current, throughMonthEnd),
        )
    }

    @Test
    fun pendingExpenseBeforeBalanceDateStillAffectsFutureProjection() {
        val openingBalanceDate = LocalDate.of(2026, 9, 29)
        val pendingDueDate = LocalDate.of(2026, 9, 20)
        val throughDate = LocalDate.of(2026, 9, 30)

        assertTrue(
            AccountBalanceDatePolicy.includesTransaction(
                status = TransactionStatus.PENDING,
                effectiveDate = pendingDueDate,
                openingBalanceDate = openingBalanceDate,
                throughDate = throughDate,
            ),
        )
        assertFalse(
            AccountBalanceDatePolicy.includesTransaction(
                status = TransactionStatus.REALIZED,
                effectiveDate = pendingDueDate,
                openingBalanceDate = openingBalanceDate,
                throughDate = throughDate,
            ),
        )
    }

    @Test
    fun checkpointAbsorbsTransactionsAlreadyRealizedAtReconciliation() {
        assertFalse(
            BalanceCheckpointPolicy.includesRealizedTransaction(
                reconciledAdjustmentId = 10L,
                checkpointId = 10L,
            ),
        )
        assertFalse(
            BalanceCheckpointPolicy.includesRealizedTransaction(
                reconciledAdjustmentId = 8L,
                checkpointId = 10L,
            ),
        )
        assertTrue(
            BalanceCheckpointPolicy.includesRealizedTransaction(
                reconciledAdjustmentId = null,
                checkpointId = 10L,
            ),
        )
        assertTrue(
            BalanceCheckpointPolicy.includesRealizedTransaction(
                reconciledAdjustmentId = 12L,
                checkpointId = 10L,
            ),
        )
    }

    @Test
    fun checkpointOnlyAppliesMovementsCreatedAfterIt() {
        assertFalse(BalanceCheckpointPolicy.includesMovement(10L, 10L))
        assertFalse(BalanceCheckpointPolicy.includesMovement(9L, 10L))
        assertTrue(BalanceCheckpointPolicy.includesMovement(11L, 10L))
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
