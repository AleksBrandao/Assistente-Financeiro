package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class DiagnosticMessageHint(
    val direction: FinancialTransactionDirection,
    val amount: BigDecimal,
    val occurredAt: LocalDateTime,
    val description: String,
    val pending: Boolean,
    val currency: String = "BRL",
    val cardLastFour: String? = null,
)

object DiagnosticMessageParser {
    private val fullDateTime = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm", Locale("pt", "BR"))
    private val cardPattern = Regex(
        """cart[aã]o\s+final\s+(\d{4}),\s+de\s+(R\$|USD)\s*([\d.]+,\d{2}),\s+em\s+(\d{2}/\d{2}/\d{2}),\s+[àa]s\s+(\d{2}:\d{2}),\s+em\s+(.+?),\s+aprovada""",
        RegexOption.IGNORE_CASE,
    )
    private val automaticDebitPattern = Regex(
        """(.+?)\s+agendou\s+um\s+d[eé]bito\s+autom[aá]tico.*?para\s+(\d{2}/\d{2}),\s+no\s+valor\s+de\s+R\$\s*([\d.]+,\d{2})""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun parse(event: DiagnosticEvent, today: LocalDate = LocalDate.now()): DiagnosticMessageHint? {
        cardPattern.find(event.body)?.let { match ->
            val (lastFour, currencyToken, rawAmount, rawDate, rawTime, merchant) = match.destructured
            return DiagnosticMessageHint(
                direction = FinancialTransactionDirection.EXPENSE,
                amount = parseAmount(rawAmount),
                occurredAt = LocalDateTime.parse("$rawDate $rawTime", fullDateTime),
                description = merchant.trim(),
                pending = false,
                currency = if (currencyToken.equals("USD", true)) "USD" else "BRL",
                cardLastFour = lastFour,
            )
        }
        automaticDebitPattern.find(event.body)?.let { match ->
            val (rawDescription, rawDayMonth, rawAmount) = match.destructured
            val parts = rawDayMonth.split("/")
            var date = LocalDate.of(today.year, parts[1].toInt(), parts[0].toInt())
            if (date.isBefore(today.minusMonths(6))) date = date.plusYears(1)
            return DiagnosticMessageHint(
                direction = FinancialTransactionDirection.EXPENSE,
                amount = parseAmount(rawAmount),
                occurredAt = LocalDateTime.of(date, LocalTime.MIDNIGHT),
                description = rawDescription.trim(),
                pending = true,
            )
        }
        return null
    }

    private fun parseAmount(value: String): BigDecimal =
        value.replace(".", "").replace(",", ".").toBigDecimal()
}
