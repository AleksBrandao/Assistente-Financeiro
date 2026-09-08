package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class FutureCardInstallmentProjectionCalculatorTest {
    private val card = FinancialAccountRecord(
        id = 5L,
        name = "SANTANDER ELITE VISA (2)",
        type = FinancialAccountType.CREDIT_CARD,
        closingDay = 14,
        dueDay = 21,
        cardIdentifiers = "7107",
    )

    @Test
    fun projectsOnlyRemainingInstallmentsIntoTheirFutureDueMonths() {
        val currentInvoice = invoice(
            id = 10L,
            closingPeriod = YearMonth.of(2026, 8),
            dueDate = LocalDate.of(2026, 8, 21),
            total = "171.70",
        )
        val transaction = installment(
            amount = "171.70",
            occurredAt = "2026-08-10T12:00:00",
            installment = 2,
            totalInstallments = 6,
            invoiceId = currentInvoice.id,
        )

        val result = FutureCardInstallmentProjectionCalculator.calculate(
            asOfDate = LocalDate.of(2026, 9, 8),
            accounts = listOf(card),
            transactions = listOf(transaction),
            invoices = listOf(currentInvoice),
        )

        assertEquals(
            listOf(
                LocalDate.of(2026, 9, 21),
                LocalDate.of(2026, 10, 21),
                LocalDate.of(2026, 11, 21),
                LocalDate.of(2026, 12, 21),
            ),
            result.map { it.dueDate },
        )
        result.forEach { projection ->
            assertEquals(0, projection.amount.compareTo(BigDecimal("171.70")))
        }
    }

    @Test
    fun officialInvoiceReplacesProjectionForSameCardAndMonth() {
        val currentInvoice = invoice(
            id = 10L,
            closingPeriod = YearMonth.of(2026, 8),
            dueDate = LocalDate.of(2026, 8, 21),
            total = "171.70",
        )
        val octoberInvoice = invoice(
            id = 11L,
            closingPeriod = YearMonth.of(2026, 10),
            dueDate = LocalDate.of(2026, 10, 21),
            total = "500.00",
        )

        val result = FutureCardInstallmentProjectionCalculator.calculate(
            asOfDate = LocalDate.of(2026, 9, 8),
            accounts = listOf(card),
            transactions = listOf(
                installment(
                    amount = "171.70",
                    occurredAt = "2026-08-10T12:00:00",
                    installment = 2,
                    totalInstallments = 6,
                    invoiceId = currentInvoice.id,
                ),
            ),
            invoices = listOf(currentInvoice, octoberInvoice),
        )

        assertEquals(
            listOf(
                LocalDate.of(2026, 9, 21),
                LocalDate.of(2026, 11, 21),
                LocalDate.of(2026, 12, 21),
            ),
            result.map { it.dueDate },
        )
    }

    @Test
    fun ignoresRealizedAndSingleInstallmentPurchases() {
        val realized = installment(
            amount = "80.00",
            occurredAt = "2026-08-10T12:00:00",
            installment = 1,
            totalInstallments = 4,
            invoiceId = null,
        ).copy(status = TransactionStatus.REALIZED)
        val single = installment(
            amount = "50.00",
            occurredAt = "2026-08-10T12:00:00",
            installment = 1,
            totalInstallments = 1,
            invoiceId = null,
        )

        val result = FutureCardInstallmentProjectionCalculator.calculate(
            asOfDate = LocalDate.of(2026, 9, 8),
            accounts = listOf(card),
            transactions = listOf(realized, single),
            invoices = emptyList(),
        )

        assertEquals(emptyList<FutureCardInstallmentProjection>(), result)
    }

    private fun installment(
        amount: String,
        occurredAt: String,
        installment: Int,
        totalInstallments: Int,
        invoiceId: Long?,
    ) = FinancialTransactionRecord(
        id = installment.toLong(),
        sourceEventId = null,
        direction = FinancialTransactionDirection.EXPENSE,
        type = FinancialTransactionType.CARD_PURCHASE,
        amount = amount,
        occurredAt = occurredAt,
        description = "Compra parcelada",
        sourcePackage = "PLUGGY",
        origin = TransactionOrigin.PLUGGY,
        status = TransactionStatus.PENDING,
        account = card.name,
        accountId = card.id,
        invoiceId = invoiceId,
        seriesIndex = installment,
        seriesTotal = totalInstallments,
    )

    private fun invoice(
        id: Long,
        closingPeriod: YearMonth,
        dueDate: LocalDate,
        total: String,
    ) = CreditCardInvoiceRecord(
        id = id,
        accountId = card.id,
        closingPeriod = closingPeriod,
        closingDate = dueDate.minusDays(7),
        dueDate = dueDate,
        status = CreditCardInvoiceStatus.OPEN,
        total = BigDecimal(total),
        paidAmount = BigDecimal.ZERO,
        outstandingAmount = BigDecimal(total),
        transactionCount = 1,
    )
}
