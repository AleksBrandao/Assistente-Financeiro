package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class GeneralProjectedBalanceCalculatorTest {
    private val throughDate = LocalDate.of(2026, 9, 30)

    @Test
    fun combinesOpeningBalanceWithRealizedAndPendingBankTransactions() {
        val bank = bankAccount(openingBalance = "1000.00")
        val transactions = listOf(
            transaction(FinancialTransactionDirection.INCOME, "200.00", TransactionStatus.REALIZED),
            transaction(FinancialTransactionDirection.EXPENSE, "50.00", TransactionStatus.REALIZED),
            transaction(FinancialTransactionDirection.INCOME, "100.00", TransactionStatus.PENDING),
            transaction(FinancialTransactionDirection.EXPENSE, "80.00", TransactionStatus.PENDING),
            transaction(
                FinancialTransactionDirection.EXPENSE,
                "900.00",
                TransactionStatus.REALIZED,
                LocalDate.of(2026, 8, 31),
            ),
            transaction(
                FinancialTransactionDirection.EXPENSE,
                "800.00",
                TransactionStatus.PENDING,
                LocalDate.of(2026, 10, 1),
            ),
        )

        val result = calculate(
            accounts = listOf(bank),
            transactionsByAccount = mapOf(bank.id to transactions),
        )

        assertEquals(BigDecimal("1170.00"), result)
    }

    @Test
    fun subtractsOverdueUnpaidInvoice() {
        val bank = bankAccount(openingBalance = "1000.00")
        val card = creditCard()
        val invoice = invoice(total = "300.00", status = CreditCardInvoiceStatus.OVERDUE)

        val result = calculate(
            accounts = listOf(bank, card),
            invoices = listOf(invoice),
        )

        assertEquals(BigDecimal("700.00"), result)
    }

    @Test
    fun combinesSourceAccountDebitWithPartiallyPaidInvoiceOutstandingAmount() {
        val bank = bankAccount(openingBalance = "1000.00")
        val card = creditCard()
        val invoice = invoice(
            total = "300.00",
            paidAmount = "100.00",
            status = CreditCardInvoiceStatus.PARTIALLY_PAID,
        )
        val payment = payment(amount = "100.00", sourceAccountId = bank.id)
        val movement = AccountMovementRecord(
            id = 1L,
            direction = AccountMovementDirection.DEBIT,
            type = AccountMovementType.CARD_PAYMENT,
            amount = payment.amount,
            occurredAt = payment.paidAt,
            description = "Pagamento de fatura",
        )

        val result = calculate(
            accounts = listOf(bank, card),
            movementsByAccount = mapOf(bank.id to listOf(movement)),
            invoices = listOf(invoice),
            paymentsByInvoice = mapOf(invoice.id to listOf(payment)),
        )

        assertEquals(BigDecimal("700.00"), result)
    }

    @Test
    fun keepsUnlinkedInvoicePaymentInTotalDeduction() {
        val bank = bankAccount(openingBalance = "1000.00")
        val card = creditCard()
        val invoice = invoice(
            total = "300.00",
            paidAmount = "100.00",
            status = CreditCardInvoiceStatus.PARTIALLY_PAID,
        )
        val payment = payment(amount = "100.00", sourceAccountId = null)

        val result = calculate(
            accounts = listOf(bank, card),
            invoices = listOf(invoice),
            paymentsByInvoice = mapOf(invoice.id to listOf(payment)),
        )

        assertEquals(BigDecimal("700.00"), result)
    }

    @Test
    fun usesExplicitAccountTypesRegardlessOfAccountNames() {
        val bank = bankAccount(openingBalance = "1000.00").copy(name = "Cartão principal")
        val card = creditCard().copy(name = "Conta corrente")

        val result = calculate(
            accounts = listOf(bank, card),
            invoices = listOf(invoice(total = "300.00", status = CreditCardInvoiceStatus.OPEN)),
        )

        assertEquals(BigDecimal("700.00"), result)
    }

    private fun calculate(
        accounts: List<FinancialAccountRecord>,
        transactionsByAccount: Map<Long, List<ProjectedBalanceTransaction>> = emptyMap(),
        movementsByAccount: Map<Long, List<AccountMovementRecord>> = emptyMap(),
        invoices: List<CreditCardInvoiceRecord> = emptyList(),
        paymentsByInvoice: Map<Long, List<InvoicePaymentRecord>> = emptyMap(),
    ): BigDecimal = GeneralProjectedBalanceCalculator.calculate(
        throughDate = throughDate,
        accounts = accounts,
        transactionsByAccount = transactionsByAccount,
        movementsByAccount = movementsByAccount,
        invoices = invoices,
        paymentsByInvoice = paymentsByInvoice,
    )

    private fun bankAccount(openingBalance: String) = FinancialAccountRecord(
        id = 1L,
        name = "Conta principal",
        type = FinancialAccountType.BANK_ACCOUNT,
        openingBalance = BigDecimal(openingBalance),
        openingBalanceDate = LocalDate.of(2026, 8, 31),
    )

    private fun creditCard() = FinancialAccountRecord(
        id = 2L,
        name = "Cartão",
        type = FinancialAccountType.CREDIT_CARD,
    )

    private fun transaction(
        direction: FinancialTransactionDirection,
        amount: String,
        status: TransactionStatus,
        occurredAt: LocalDate = LocalDate.of(2026, 9, 10),
    ) = ProjectedBalanceTransaction(
        direction = direction,
        amount = BigDecimal(amount),
        occurredAt = occurredAt,
        status = status,
    )

    private fun invoice(
        total: String,
        status: CreditCardInvoiceStatus,
        paidAmount: String = "0.00",
    ) = CreditCardInvoiceRecord(
        id = 10L,
        accountId = 2L,
        closingPeriod = YearMonth.of(2026, 8),
        closingDate = LocalDate.of(2026, 8, 25),
        dueDate = LocalDate.of(2026, 9, 5),
        status = status,
        total = BigDecimal(total),
        paidAmount = BigDecimal(paidAmount),
        outstandingAmount = BigDecimal(total) - BigDecimal(paidAmount),
        transactionCount = 1,
    )

    private fun payment(
        amount: String,
        sourceAccountId: Long?,
    ) = InvoicePaymentRecord(
        id = 20L,
        amount = BigDecimal(amount),
        paidAt = LocalDate.of(2026, 9, 3),
        sourceAccountId = sourceAccountId,
        sourceAccountName = sourceAccountId?.let { "Conta principal" },
    )
}
