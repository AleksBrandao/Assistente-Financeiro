package br.com.assistentefinanceiro.notifications

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MonthlyRecurrencePlannerTest {
    @Test
    fun `creates monthly occurrences and keeps only first one realized`() {
        val result = MonthlyRecurrencePlanner.plan(
            firstDate = LocalDate.of(2026, 1, 31),
            occurrences = 3,
            firstStatus = TransactionStatus.REALIZED,
        )

        assertEquals(listOf(1, 2, 3), result.map { it.index })
        assertEquals(
            listOf(
                LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31),
            ),
            result.map { it.date },
        )
        assertEquals(
            listOf(
                TransactionStatus.REALIZED,
                TransactionStatus.PENDING,
                TransactionStatus.PENDING,
            ),
            result.map { it.status },
        )
    }

    @Test
    fun `rejects more than 120 occurrences`() {
        assertThrows(IllegalArgumentException::class.java) {
            MonthlyRecurrencePlanner.plan(LocalDate.now(), 121, TransactionStatus.PENDING)
        }
    }
}
