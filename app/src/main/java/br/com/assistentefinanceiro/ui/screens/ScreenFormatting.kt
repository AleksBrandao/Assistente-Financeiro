package br.com.assistentefinanceiro.ui.screens

import br.com.assistentefinanceiro.notifications.FinancialTransactionType
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val PT_BR: Locale = Locale("pt", "BR")
internal val MONTH_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMMM 'de' yyyy", PT_BR)
internal val DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd 'de' MMMM", PT_BR)
internal val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", PT_BR)
internal val SHORT_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy", PT_BR)
internal const val DESCRIPTION_MAX_LENGTH = 80

internal fun transactionTypeLabel(type: FinancialTransactionType): String =
    when (type) {
        FinancialTransactionType.CARD_PURCHASE -> "Compra no cartão"
        FinancialTransactionType.PIX_RECEIVED -> "PIX recebido"
        FinancialTransactionType.IMPORTED_EXPENSE -> "Despesa importada"
        FinancialTransactionType.IMPORTED_INCOME -> "Receita importada"
        FinancialTransactionType.MANUAL_EXPENSE -> "Despesa manual"
        FinancialTransactionType.MANUAL_INCOME -> "Receita manual"
    }

internal fun formatCurrency(amount: String?): String =
    amount?.toBigDecimalOrNull()?.let {
        NumberFormat.getCurrencyInstance(PT_BR).format(it)
    } ?: "valor indisponível"

internal fun formatMonth(period: YearMonth): String {
    val formatted = period.format(MONTH_FORMATTER)
    return formatted.take(1).uppercase(PT_BR) + formatted.drop(1)
}

internal fun formatDate(date: LocalDate): String =
    date.format(DATE_FORMATTER)

internal fun formatTime(occurredAt: String): String =
    runCatching {
        LocalDateTime.parse(occurredAt).format(TIME_FORMATTER)
    }.getOrDefault("--:--")
