package br.com.assistentefinanceiro.importing

import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.TransactionCategory
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

class MobillsImportFormatException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

enum class ImportDisposition {
    READY,
    PENDING,
    POSSIBLE_DUPLICATE,
    REJECTED,
}

data class MobillsImportRow(
    val sourceRow: Int,
    val date: LocalDate?,
    val description: String,
    val amount: BigDecimal?,
    val account: String,
    val situation: String,
    val originalCategory: String,
    val category: TransactionCategory,
    val direction: FinancialTransactionDirection?,
    val disposition: ImportDisposition,
    val rejectionReason: String? = null,
    val importKey: String? = null,
)

data class MobillsImportPreview(val rows: List<MobillsImportRow>) {
    val readyCount = rows.count { it.disposition == ImportDisposition.READY }
    val pendingCount = rows.count { it.disposition == ImportDisposition.PENDING }
    val possibleDuplicateCount = rows.count {
        it.disposition == ImportDisposition.POSSIBLE_DUPLICATE
    }
    val rejectedCount = rows.count { it.disposition == ImportDisposition.REJECTED }
    val rejectionReasons: Map<String, Int> = rows
        .filter { it.disposition == ImportDisposition.REJECTED }
        .groupingBy { it.rejectionReason ?: "Motivo não informado" }
        .eachCount()
}

object MobillsImportAnalyzer {
    private val requiredHeaders = listOf(
        "data", "descricao", "valor", "conta", "situacao", "categoria",
    )
    private val dateFormatters = listOf(
        DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale("pt", "BR")),
        DateTimeFormatter.ofPattern("dd-MM-uuuu", Locale("pt", "BR")),
        DateTimeFormatter.ISO_LOCAL_DATE,
    ).map { it.withResolverStyle(ResolverStyle.STRICT) }

    fun analyze(rawRows: List<List<String>>): MobillsImportPreview {
        if (rawRows.isEmpty() || rawRows.all { row -> row.all(String::isBlank) }) {
            throw MobillsImportFormatException(
                "Arquivo Mobills inválido: a planilha está vazia.",
            )
        }
        val header = rawRows.first().map { normalizeHeader(it) }
        val missingHeaders = requiredHeaders.filterNot(header::contains)
        if (missingHeaders.isNotEmpty()) {
            throw MobillsImportFormatException(
                "Arquivo Mobills inválido: cabeçalhos obrigatórios ausentes: " +
                    missingHeaders.joinToString(),
            )
        }
        val duplicateHeaders = requiredHeaders.filter { required -> header.count { it == required } > 1 }
        if (duplicateHeaders.isNotEmpty()) {
            throw MobillsImportFormatException(
                "Arquivo Mobills inválido: cabeçalhos obrigatórios duplicados: " +
                    duplicateHeaders.joinToString(),
            )
        }
        val indexes = requiredHeaders.associateWith(header::indexOf)

        val parsed = rawRows.drop(1).mapIndexedNotNull { index, cells ->
            if (cells.all { it.isBlank() }) return@mapIndexedNotNull null
            parseRow(index + 2, cells, indexes)
        }
        val repeatedKeys = parsed
            .filter { it.disposition != ImportDisposition.REJECTED && it.importKey != null }
            .groupingBy { it.importKey!! }
            .eachCount()
            .filterValues { it > 1 }
            .keys

        val occurrences = mutableMapOf<String, Int>()
        return MobillsImportPreview(
            parsed.map { row ->
                val baseKey = row.importKey ?: return@map row
                val occurrence = occurrences.getOrDefault(baseKey, 0) + 1
                occurrences[baseKey] = occurrence
                row.copy(
                    disposition = if (baseKey in repeatedKeys && occurrence > 1) {
                        ImportDisposition.POSSIBLE_DUPLICATE
                    } else {
                        row.disposition
                    },
                    importKey = sha256("$baseKey|$occurrence"),
                )
            }
        )
    }

    private fun parseRow(
        sourceRow: Int,
        cells: List<String>,
        indexes: Map<String, Int>,
    ): MobillsImportRow {
        fun value(name: String): String = cells.getOrNull(indexes.getValue(name))?.trim().orEmpty()
        val dateText = value("data")
        val description = value("descricao")
        val amountText = value("valor")
        val account = value("conta")
        val situation = value("situacao")
        val originalCategory = value("categoria")
        val date = parseDate(dateText)
        val amount = parseAmount(amountText)

        val normalizedSituation = normalizeHeader(situation)
        val reason = when {
            date == null -> "Data inválida (use DD/MM/AAAA, DD-MM-AAAA ou AAAA-MM-DD)"
            description.isBlank() -> "Descrição vazia"
            amount == null -> "Valor inválido"
            amount.signum() == 0 -> "Valor igual a zero"
            account.isBlank() -> "Conta vazia"
            normalizedSituation !in setOf("paga", "pendente") -> "Situação inválida"
            else -> null
        }
        if (reason != null) {
            return rejectedRow(
                sourceRow = sourceRow,
                reason = reason,
                date = date,
                description = description,
                amount = amount,
                account = account,
                situation = situation,
                originalCategory = originalCategory,
            )
        }

        val direction = if (amount!!.signum() > 0) {
            FinancialTransactionDirection.INCOME
        } else {
            FinancialTransactionDirection.EXPENSE
        }
        val normalizedAmount = amount.abs()
        val keySource = listOf(
            date.toString(), description.trim().lowercase(Locale.ROOT),
            normalizedAmount.stripTrailingZeros().toPlainString(),
            account.trim().lowercase(Locale.ROOT), situation.trim().lowercase(Locale.ROOT),
            originalCategory.trim().lowercase(Locale.ROOT),
        ).joinToString("|")
        return MobillsImportRow(
            sourceRow = sourceRow,
            date = date,
            description = description,
            amount = normalizedAmount,
            account = account,
            situation = situation,
            originalCategory = originalCategory,
            category = mapCategory(originalCategory, direction),
            direction = direction,
            disposition = if (normalizedSituation == "pendente") {
                ImportDisposition.PENDING
            } else {
                ImportDisposition.READY
            },
            importKey = sha256(keySource),
        )
    }

    private fun rejectedRow(
        sourceRow: Int,
        reason: String,
        date: LocalDate? = null,
        description: String = "",
        amount: BigDecimal? = null,
        account: String = "",
        situation: String = "",
        originalCategory: String = "",
    ) = MobillsImportRow(
        sourceRow = sourceRow,
        date = date,
        description = description,
        amount = amount,
        account = account,
        situation = situation,
        originalCategory = originalCategory,
        category = TransactionCategory.UNCATEGORIZED,
        direction = null,
        disposition = ImportDisposition.REJECTED,
        rejectionReason = reason,
    )

    private fun mapCategory(
        value: String,
        direction: FinancialTransactionDirection,
    ): TransactionCategory {
        val normalized = normalizeHeader(value)
        val mapped = when (normalized) {
            "alimentacao" -> TransactionCategory.FOOD
            "transporte" -> TransactionCategory.TRANSPORT
            "moradia", "casa" -> TransactionCategory.HOUSING
            "saude" -> TransactionCategory.HEALTH
            "compras" -> TransactionCategory.SHOPPING
            "educacao" -> TransactionCategory.EDUCATION
            "lazer" -> TransactionCategory.LEISURE
            "servicos" -> TransactionCategory.SERVICES
            "salario" -> TransactionCategory.SALARY
            "reembolso" -> TransactionCategory.REFUND
            "rendimentos", "investimentos" -> TransactionCategory.INVESTMENT_INCOME
            else -> TransactionCategory.UNCATEGORIZED
        }
        return mapped.takeIf { it.supports(direction) } ?: TransactionCategory.UNCATEGORIZED
    }

    private fun normalizeHeader(value: String): String = java.text.Normalizer
        .normalize(value.trim(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)

    private fun parseDate(value: String): LocalDate? = dateFormatters.firstNotNullOfOrNull {
        formatter -> runCatching { LocalDate.parse(value, formatter) }.getOrNull()
    }

    private fun parseAmount(value: String): BigDecimal? {
        var normalized = value
            .trim()
            .replace("\u00A0", "")
            .replace(" ", "")
            .removePrefix("R$")
        val negativeInParentheses = normalized.startsWith('(') && normalized.endsWith(')')
        if (negativeInParentheses) normalized = normalized.substring(1, normalized.lastIndex)
        if (normalized.isBlank() || (negativeInParentheses && normalized.startsWith('-'))) return null

        normalized = when {
            PT_BR_GROUPED_AMOUNT.matches(normalized) -> normalized.replace(".", "").replace(',', '.')
            EN_US_GROUPED_AMOUNT.matches(normalized) -> normalized.replace(",", "")
            PLAIN_AMOUNT.matches(normalized) -> normalized.replace(',', '.')
            else -> return null
        }
        val amount = normalized.toBigDecimalOrNull() ?: return null
        return if (negativeInParentheses) amount.negate() else amount
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private val PT_BR_GROUPED_AMOUNT = Regex("^[+-]?\\d{1,3}(?:\\.\\d{3})+,\\d+$")
    private val EN_US_GROUPED_AMOUNT = Regex("^[+-]?\\d{1,3}(?:,\\d{3})+\\.\\d+$")
    private val PLAIN_AMOUNT = Regex("^[+-]?\\d+(?:[.,]\\d+)?$")
}
