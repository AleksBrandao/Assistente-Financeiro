package br.com.assistentefinanceiro.notifications

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransactionRealizationPolicyTest {
    private val today = LocalDate.of(2026, 10, 2)

    @Test
    fun pendingBecomingRealizedUsesConfirmationDateWhenNoDateWasProvided() {
        assertEquals(
            today,
            TransactionRealizationPolicy.resolvedPaidAt(
                previousStatus = TransactionStatus.PENDING,
                newStatus = TransactionStatus.REALIZED,
                requestedPaidAt = null,
                today = today,
            ),
        )
    }

    @Test
    fun explicitPaymentDateIsPreserved() {
        val explicit = LocalDate.of(2026, 10, 1)

        assertEquals(
            explicit,
            TransactionRealizationPolicy.resolvedPaidAt(
                previousStatus = TransactionStatus.PENDING,
                newStatus = TransactionStatus.REALIZED,
                requestedPaidAt = explicit,
                today = today,
            ),
        )
    }

    @Test
    fun realizedEntryWithoutTransitionDoesNotInventANewDate() {
        assertNull(
            TransactionRealizationPolicy.resolvedPaidAt(
                previousStatus = TransactionStatus.REALIZED,
                newStatus = TransactionStatus.REALIZED,
                requestedPaidAt = null,
                today = today,
            ),
        )
    }

    @Test
    fun changingBackToPendingClearsPaymentDate() {
        assertNull(
            TransactionRealizationPolicy.resolvedPaidAt(
                previousStatus = TransactionStatus.REALIZED,
                newStatus = TransactionStatus.PENDING,
                requestedPaidAt = today,
                today = today,
            ),
        )
    }
}
