package br.com.assistentefinanceiro.openfinance

import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluggyDomainTest {
    @Test
    fun installmentKeepsOriginalPurchaseAndBillingPeriod() {
        val postedAt = Instant.parse("2026-08-14T03:00:00Z")
        val purchasedAt = Instant.parse("2026-07-19T18:12:09Z")
        val transaction = PluggyTransactionSnapshot(
            externalId = "tx-1",
            accountExternalId = "account-1",
            amount = BigDecimal("349.85"),
            date = postedAt,
            purchaseDate = purchasedAt,
            direction = PluggyTransactionDirection.DEBIT,
            status = PluggyTransactionStatus.PENDING,
            description = "Compra",
            installmentNumber = 2,
            totalInstallments = 12,
            billForecastDate = YearMonth.of(2026, 8),
        )

        assertTrue(transaction.isInstallment)
        assertEquals(purchasedAt, transaction.effectivePurchaseInstant)
        assertEquals(YearMonth.of(2026, 8), transaction.billForecastDate)
        assertEquals(BigDecimal("349.85"), transaction.absoluteAmount)
    }

    @Test
    fun nonInstallmentFallsBackToPostingDate() {
        val postedAt = Instant.parse("2026-09-04T13:27:23Z")
        val transaction = PluggyTransactionSnapshot(
            externalId = "tx-2",
            accountExternalId = "account-1",
            amount = BigDecimal("-751.40"),
            date = postedAt,
            direction = PluggyTransactionDirection.DEBIT,
            status = PluggyTransactionStatus.POSTED,
            description = "PIX",
            operationType = "PIX",
            paymentMethod = "PIX",
        )

        assertFalse(transaction.isInstallment)
        assertEquals(postedAt, transaction.effectivePurchaseInstant)
        assertEquals(BigDecimal("751.40"), transaction.absoluteAmount)
        assertNull(transaction.billForecastDate)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncompleteInstallmentMetadata() {
        PluggyTransactionSnapshot(
            externalId = "tx-3",
            accountExternalId = "account-1",
            amount = BigDecimal.ONE,
            date = Instant.EPOCH,
            direction = PluggyTransactionDirection.DEBIT,
            status = PluggyTransactionStatus.POSTED,
            description = "Compra",
            installmentNumber = 1,
        )
    }

    @Test
    fun sanitizerNeverKeepsFullCardNumber() {
        assertEquals("1234", PluggyDataSanitizer.cardLastFour("**** **** **** 1234"))
        assertEquals("9876", PluggyDataSanitizer.cardLastFour("411111119876"))
        assertNull(PluggyDataSanitizer.cardLastFour("12"))
    }
}
