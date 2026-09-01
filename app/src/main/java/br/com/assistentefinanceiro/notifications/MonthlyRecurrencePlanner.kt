package br.com.assistentefinanceiro.notifications

import java.time.LocalDate

data class PlannedOccurrence(
    val index: Int,
    val date: LocalDate,
    val status: TransactionStatus,
)

object MonthlyRecurrencePlanner {
    fun plan(
        firstDate: LocalDate,
        occurrences: Int,
        firstStatus: TransactionStatus,
    ): List<PlannedOccurrence> {
        require(occurrences in 1..120)
        return (0 until occurrences).map { offset ->
            PlannedOccurrence(
                index = offset + 1,
                date = firstDate.plusMonths(offset.toLong()),
                status = if (offset == 0) firstStatus else TransactionStatus.PENDING,
            )
        }
    }
}
