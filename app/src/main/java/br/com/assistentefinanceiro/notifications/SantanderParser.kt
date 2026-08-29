package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ParsedCardPurchase(
    val cardLastFour: String,
    val amount: BigDecimal,
    val occurredAt: LocalDateTime,
    val merchant: String,
    val approved: Boolean,
)

object SantanderParser {
    private val purchasePattern = Regex(
        """Compra\s+no\s+cart[aã]o\s+final\s+(\d{4}),\s+de\s+R\$\s*([\d.]+,\d{2}),\s+em\s+(\d{2}/\d{2}/\d{2}),\s+[àa]s\s+(\d{2}:\d{2}),\s+em\s+(.+?),\s+aprovada\.??""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm", Locale("pt", "BR"))

    fun parse(title: String?, body: String?): ParsedCardPurchase? {
        if (!title.orEmpty().contains("Compra aprovada", ignoreCase = true)) return null
        val match = purchasePattern.find(body.orEmpty().trim()) ?: return null
        val (lastFour, rawAmount, rawDate, rawTime, rawMerchant) = match.destructured
        val normalizedAmount = rawAmount.replace(".", "").replace(",", ".")
        return ParsedCardPurchase(
            cardLastFour = lastFour,
            amount = normalizedAmount.toBigDecimal(),
            occurredAt = LocalDateTime.parse("$rawDate $rawTime", dateFormatter),
            merchant = rawMerchant.trim(),
            approved = true,
        )
    }
}

