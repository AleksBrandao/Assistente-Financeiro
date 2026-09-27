package br.com.assistentefinanceiro.notifications

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ParsedPixSent(
    val amount: BigDecimal,
    val occurredAt: LocalDateTime,
)

object SantanderPixSentParser {
    private val pattern = Regex(
        """PIX\s+enviado\s+em\s+(\d{2}/\d{2}/\d{4})\s+(?:a|à|às|as)\s+(\d{2}:\d{2}).*?R\$\s*([\d.]+,\d{2})""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val formatter = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm", Locale("pt", "BR"))

    fun parse(title: String?, body: String?): ParsedPixSent? {
        val fullText = "${title.orEmpty()}\n${body.orEmpty()}"
        val match = pattern.find(fullText) ?: return null
        return runCatching {
            ParsedPixSent(
                amount = match.groupValues[3].replace(".", "").replace(',', '.').toBigDecimal(),
                occurredAt = LocalDateTime.parse(
                    "${match.groupValues[1]} ${match.groupValues[2]}",
                    formatter,
                ),
            )
        }.getOrNull()
    }
}
