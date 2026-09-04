package br.com.assistentefinanceiro.notifications

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object BradescoCardsNotificationParser : BankNotificationParser {
    private const val BRADESCO_CARDS_PACKAGE = "br.com.bradesco.cartoes"

    private val purchasePattern = Regex(
        """BRADESCO\s+CARTOES:\s+COMPRA\s+APROVADA\s+NO\s+CARTAO\s+FINAL\s+(\d{4})\s+EM\s+(\d{2}/\d{2}/\d{4})\s+(\d{2}:\d{2})\.?\s+(?:NO\s+)?VALOR\s+DE\s+R\$\s*([\d.]+,\d{2})(?:\s+EM\s+\d+\s*X)?\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale("pt", "BR"))

    override fun canHandle(notification: BankNotification): Boolean =
        notification.packageName == BRADESCO_CARDS_PACKAGE

    override fun parse(notification: BankNotification): ParsedFinancialTransaction? {
        val fullText = listOf(notification.title, notification.body)
            .filterNotNull()
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
        val match = purchasePattern.find(fullText) ?: return null
        val (lastFour, rawDate, rawTime, rawAmount, rawMerchant) = match.destructured

        return runCatching {
            ParsedFinancialTransaction(
                type = FinancialTransactionType.CARD_PURCHASE,
                amount = rawAmount.replace(".", "").replace(",", ".").toBigDecimal(),
                occurredAt = LocalDateTime.parse("$rawDate $rawTime", dateFormatter),
                cardLastFour = lastFour,
                merchant = rawMerchant.trim().trimEnd('.'),
            )
        }.getOrNull()
    }
}
