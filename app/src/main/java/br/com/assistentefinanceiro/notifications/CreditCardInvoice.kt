package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

enum class CreditCardInvoiceStatus(val displayName: String) {
    OPEN("Aberta"),
    CLOSED("Fechada");

    companion object {
        fun fromStored(value: String?): CreditCardInvoiceStatus =
            entries.firstOrNull { it.name == value } ?: OPEN
    }
}

data class CreditCardBillingDates(
    val closingPeriod: YearMonth,
    val closingDate: LocalDate,
    val dueDate: LocalDate?,
)

data class CreditCardInvoiceRecord(
    val id: Long,
    val accountId: Long,
    val closingPeriod: YearMonth,
    val closingDate: LocalDate,
    val dueDate: LocalDate?,
    val status: CreditCardInvoiceStatus,
    val total: BigDecimal,
    val transactionCount: Int,
)

object CreditCardBillingCycle {
    fun calculate(
        purchaseDate: LocalDate,
        closingDay: Int,
        dueDay: Int?,
    ): CreditCardBillingDates {
        require(closingDay in 1..31)
        require(dueDay == null || dueDay in 1..31)

        var closingPeriod = YearMonth.from(purchaseDate)
        var closingDate = dateAtDay(closingPeriod, closingDay)
        if (purchaseDate.isAfter(closingDate)) {
            closingPeriod = closingPeriod.plusMonths(1)
            closingDate = dateAtDay(closingPeriod, closingDay)
        }
        val dueDate = dueDay?.let { day ->
            val duePeriod = if (day <= closingDay) {
                closingPeriod.plusMonths(1)
            } else {
                closingPeriod
            }
            dateAtDay(duePeriod, day)
        }
        return CreditCardBillingDates(closingPeriod, closingDate, dueDate)
    }

    fun status(closingDate: LocalDate, today: LocalDate): CreditCardInvoiceStatus =
        if (!today.isBefore(closingDate)) CreditCardInvoiceStatus.CLOSED
        else CreditCardInvoiceStatus.OPEN

    fun fromImportedInvoiceDate(
        invoiceDate: LocalDate,
        closingDay: Int,
        configuredDueDay: Int?,
    ): CreditCardBillingDates {
        require(closingDay in 1..31)
        require(configuredDueDay == null || configuredDueDay in 1..31)
        val effectiveDueDay = configuredDueDay ?: invoiceDate.dayOfMonth
        val duePeriod = YearMonth.from(invoiceDate)
        val closingPeriod = if (effectiveDueDay <= closingDay) {
            duePeriod.minusMonths(1)
        } else {
            duePeriod
        }
        return CreditCardBillingDates(
            closingPeriod = closingPeriod,
            closingDate = dateAtDay(closingPeriod, closingDay),
            dueDate = invoiceDate,
        )
    }

    private fun dateAtDay(period: YearMonth, day: Int): LocalDate =
        period.atDay(day.coerceAtMost(period.lengthOfMonth()))
}
