package br.com.assistentefinanceiro.notifications

import java.time.LocalDate
import java.time.YearMonth
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreditCardBillingCycleTest {
    @Test
    fun purchaseBeforeClosingUsesCurrentClosingAndNextMonthDueDate() {
        val dates = CreditCardBillingCycle.calculate(
            purchaseDate = LocalDate.of(2026, 8, 14),
            closingDay = 26,
            dueDay = 5,
        )

        assertEquals(YearMonth.of(2026, 8), dates.closingPeriod)
        assertEquals(LocalDate.of(2026, 8, 26), dates.closingDate)
        assertEquals(LocalDate.of(2026, 9, 5), dates.dueDate)
    }

    @Test
    fun purchaseAfterClosingMovesToNextInvoice() {
        val dates = CreditCardBillingCycle.calculate(
            purchaseDate = LocalDate.of(2026, 8, 29),
            closingDay = 26,
            dueDay = 5,
        )

        assertEquals(YearMonth.of(2026, 9), dates.closingPeriod)
        assertEquals(LocalDate.of(2026, 9, 26), dates.closingDate)
        assertEquals(LocalDate.of(2026, 10, 5), dates.dueDate)
    }

    @Test
    fun supportsCardsWithoutKnownDueDayAndShortMonths() {
        val dates = CreditCardBillingCycle.calculate(
            purchaseDate = LocalDate.of(2027, 2, 27),
            closingDay = 31,
            dueDay = null,
        )

        assertEquals(LocalDate.of(2027, 2, 28), dates.closingDate)
        assertNull(dates.dueDate)
    }

    @Test
    fun closesInvoiceOnClosingDate() {
        val closingDate = LocalDate.of(2026, 8, 26)
        assertEquals(
            CreditCardInvoiceStatus.CLOSED,
            CreditCardBillingCycle.status(closingDate, closingDate),
        )
        assertEquals(
            CreditCardInvoiceStatus.OPEN,
            CreditCardBillingCycle.status(closingDate, closingDate.minusDays(1)),
        )
    }

    @Test
    fun importedMobillsDateRepresentsInvoiceDueDate() {
        val dates = CreditCardBillingCycle.fromImportedInvoiceDate(
            invoiceDate = LocalDate.of(2026, 9, 5),
            closingDay = 26,
            configuredDueDay = 5,
        )

        assertEquals(YearMonth.of(2026, 8), dates.closingPeriod)
        assertEquals(LocalDate.of(2026, 8, 26), dates.closingDate)
        assertEquals(LocalDate.of(2026, 9, 5), dates.dueDate)
    }

    @Test
    fun importedInvoiceDateCanSupplyUnknownDueDay() {
        val dates = CreditCardBillingCycle.fromImportedInvoiceDate(
            invoiceDate = LocalDate.of(2026, 9, 18),
            closingDay = 8,
            configuredDueDay = null,
        )

        assertEquals(YearMonth.of(2026, 9), dates.closingPeriod)
        assertEquals(LocalDate.of(2026, 9, 8), dates.closingDate)
        assertEquals(LocalDate.of(2026, 9, 18), dates.dueDate)
    }

    @Test
    fun paymentStatusPrioritizesPaidAndOverdue() {
        val closing = LocalDate.of(2026, 8, 26)
        val due = LocalDate.of(2026, 9, 5)
        val total = BigDecimal("100.00")

        assertEquals(
            CreditCardInvoiceStatus.PARTIALLY_PAID,
            CreditCardBillingCycle.paymentStatus(
                total, BigDecimal("40.00"), closing, due, LocalDate.of(2026, 9, 1),
            ),
        )
        assertEquals(
            CreditCardInvoiceStatus.OVERDUE,
            CreditCardBillingCycle.paymentStatus(
                total, BigDecimal("40.00"), closing, due, LocalDate.of(2026, 9, 6),
            ),
        )
        assertEquals(
            CreditCardInvoiceStatus.PAID,
            CreditCardBillingCycle.paymentStatus(
                total, total, closing, due, LocalDate.of(2026, 9, 6),
            ),
        )
    }
}
