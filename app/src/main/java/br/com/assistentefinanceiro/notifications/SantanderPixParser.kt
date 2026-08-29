package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ParsedPixReceipt(
    val amount: BigDecimal,
    val occurredAt: LocalDateTime,
)

object SantanderPixParser {
    private val receiptPattern = Regex(
        """PIX\s+recebido\s+em\s+(\d{2}/\d{2}/\d{4})\s+[àa]s\s+(\d{2}:\d{2})\s+no\s+valor\s+de\s+R\$\s*([\d.]+,\d{2})\.?""",
        RegexOption.IGNORE_CASE,
    )
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale("pt", "BR"))

    fun parse(title: String?, body: String?): ParsedPixReceipt? {
        if (!title.orEmpty().contains("receber um PIX", ignoreCase = true)) return null
        val match = receiptPattern.find(body.orEmpty().trim()) ?: return null
        val (rawDate, rawTime, rawAmount) = match.destructured

        return runCatching {
            ParsedPixReceipt(
                amount = rawAmount.replace(".", "").replace(",", ".").toBigDecimal(),
                occurredAt = LocalDateTime.parse("$rawDate $rawTime", dateFormatter),
            )
        }.getOrNull()
    }
}
