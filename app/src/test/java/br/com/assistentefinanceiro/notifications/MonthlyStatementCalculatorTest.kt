package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class MonthlyStatementCalculatorTest {
    @Test
    fun calculatesSelectedMonthTotalsAndBalance() {
        val statement = MonthlyStatementCalculator.calculate(
            period = YearMonth.of(2026, 8),
            transactions = listOf(
                transaction(
                    id = 1,
                    direction = FinancialTransactionDirection.INCOME,
                    type = FinancialTransactionType.PIX_RECEIVED,
                    amount = "58.00",
                    occurredAt = "2026-08-29T12:38",
                ),
                transaction(
                    id = 2,
                    direction = FinancialTransactionDirection.EXPENSE,
                    type = FinancialTransactionType.CARD_PURCHASE,
                    amount = "10.00",
                    occurredAt = "2026-08-29T13:45",
                ),
                transaction(
                    id = 3,
                    direction = FinancialTransactionDirection.EXPENSE,
                    type = FinancialTransactionType.CARD_PURCHASE,
                    amount = "20.00",
                    occurredAt = "2026-08-28T09:00",
                ),
                transaction(
                    id = 4,
                    direction = FinancialTransactionDirection.INCOME,
                    type = FinancialTransactionType.PIX_RECEIVED,
                    amount = "100.00",
                    occurredAt = "2026-07-31T23:59",
                ),
            ),
        )

        assertEquals("58.00", statement.totalIncome.toPlainString())
        assertEquals("30.00", statement.totalExpense.toPlainString())
        assertEquals("28.00", statement.balance.toPlainString())
        assertEquals(3, statement.transactionCount)
    }

    @Test
    fun groupsDaysAndOrdersNewestFirst() {
        val statement = MonthlyStatementCalculator.calculate(
            period = YearMonth.of(2026, 8),
            transactions = listOf(
                transaction(id = 1, occurredAt = "2026-08-28T10:00"),
                transaction(id = 2, occurredAt = "2026-08-29T09:00"),
                transaction(id = 3, occurredAt = "2026-08-29T11:00"),
            ),
        )

        assertEquals(
            listOf(LocalDate.of(2026, 8, 29), LocalDate.of(2026, 8, 28)),
            statement.groups.map { it.date },
        )
        assertEquals(listOf(3L, 2L), statement.groups.first().transactions.map { it.id })
    }

    @Test
    fun ignoresMalformedAndNegativeTransactions() {
        val statement = MonthlyStatementCalculator.calculate(
            period = YearMonth.of(2026, 8),
            transactions = listOf(
                transaction(id = 1, amount = "inválido"),
                transaction(id = 2, amount = "-10.00"),
                transaction(id = 3, occurredAt = "data inválida"),
            ),
        )

        assertEquals("0", statement.totalIncome.toPlainString())
        assertEquals("0", statement.totalExpense.toPlainString())
        assertEquals(0, statement.transactionCount)
    }

    @Test
    fun usesDueDateForPendingAndPaidDateForRealizedTransactions() {
        val transactions = listOf(
            transaction(
                id = 1, direction = FinancialTransactionDirection.EXPENSE,
                status = TransactionStatus.PENDING, dueDate = "2026-09-10",
            ),
            transaction(
                id = 2, direction = FinancialTransactionDirection.EXPENSE,
                status = TransactionStatus.REALIZED, paidAt = "2026-09-12",
            ),
        )

        assertEquals(
            0,
            MonthlyStatementCalculator.calculate(YearMonth.of(2026, 8), transactions)
                .transactionCount,
        )
        assertEquals(
            2,
            MonthlyStatementCalculator.calculate(YearMonth.of(2026, 9), transactions)
                .transactionCount,
        )
    }

    @Test
    fun `pagamento previsto substitui vencimento sem marcar como pago`() {
        val transaction = transaction(
            id = 10,
            direction = FinancialTransactionDirection.EXPENSE,
            status = TransactionStatus.PENDING,
            dueDate = "2026-09-05",
            plannedPaymentDate = "2026-10-05",
        )

        assertEquals(
            0,
            MonthlyStatementCalculator.calculate(YearMonth.of(2026, 9), listOf(transaction))
                .transactionCount,
        )
        assertEquals(
            1,
            MonthlyStatementCalculator.calculate(YearMonth.of(2026, 10), listOf(transaction))
                .transactionCount,
        )
    }

    @Test
    fun partiallyPaidInvoiceCountsOnlyOutstandingAmountAsPending() {
        val invoice = transaction(
            id = -55,
            direction = FinancialTransactionDirection.EXPENSE,
            type = FinancialTransactionType.IMPORTED_EXPENSE,
            amount = "5739.65",
            occurredAt = "2025-12-21T23:59:59",
            status = TransactionStatus.PENDING,
            dueDate = "2025-12-21",
        ).copy(sourcePackage = "credit-card-invoice")

        val statement = MonthlyStatementCalculator.calculateEntries(
            period = YearMonth.of(2025, 12),
            entries = listOf(
                StatementCalculationEntry(
                    transaction = invoice,
                    realizedAmount = BigDecimal("5739.60"),
                    pendingAmount = BigDecimal("0.05"),
                ),
            ),
        )

        assertEquals("5739.60", statement.totalExpense.toPlainString())
        assertEquals("0.05", statement.pendingExpense.toPlainString())
        assertEquals("5739.65", statement.projectedExpense.toPlainString())
        assertEquals(1, statement.transactionCount)
        assertEquals(LocalDate.of(2025, 12, 21), statement.groups.single().date)
    }

    private fun transaction(
        id: Long,
        direction: FinancialTransactionDirection = FinancialTransactionDirection.INCOME,
        type: FinancialTransactionType = FinancialTransactionType.PIX_RECEIVED,
        amount: String = "1.00",
        occurredAt: String = "2026-08-29T12:00",
        status: TransactionStatus = TransactionStatus.REALIZED,
        dueDate: String? = null,
        plannedPaymentDate: String? = null,
        paidAt: String? = null,
    ): FinancialTransactionRecord = FinancialTransactionRecord(
        id = id,
        sourceEventId = id,
        direction = direction,
        type = type,
        amount = amount,
        occurredAt = occurredAt,
        description = if (type == FinancialTransactionType.PIX_RECEIVED) {
            "PIX recebido"
        } else {
            "Compra no cartão"
        },
        sourcePackage = "com.santander.app",
        status = status,
        dueDate = dueDate,
        plannedPaymentDate = plannedPaymentDate,
        paidAt = paidAt,
    )
}
