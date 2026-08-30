package br.com.assistentefinanceiro.importing

import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.TransactionCategory
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

enum class ImportDisposition {
    READY,
    PLANNED,
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
    val plannedCount = rows.count { it.disposition == ImportDisposition.PLANNED }
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
    private val dateFormatter = DateTimeFormatter
        .ofPattern("dd/MM/uuuu", Locale("pt", "BR"))
        .withResolverStyle(ResolverStyle.STRICT)

    fun analyze(rawRows: List<List<String>>, today: LocalDate): MobillsImportPreview {
        if (rawRows.isEmpty()) return MobillsImportPreview(emptyList())
        val header = rawRows.first().map { normalizeHeader(it) }
        val required = listOf("data", "descricao", "valor", "conta", "situacao", "categoria")
        val indexes = required.associateWith { header.indexOf(it) }
        if (indexes.values.any { it < 0 }) {
            return MobillsImportPreview(
                listOf(
                    rejectedRow(
                        sourceRow = 1,
                        reason = "Cabeçalhos obrigatórios ausentes: " +
                            required.filter { indexes[it] == -1 }.joinToString(),
                    )
                )
            )
        }

        val parsed = rawRows.drop(1).mapIndexedNotNull { index, cells ->
            if (cells.all { it.isBlank() }) return@mapIndexedNotNull null
            parseRow(index + 2, cells, indexes, today)
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
        today: LocalDate,
    ): MobillsImportRow {
        fun value(name: String): String = cells.getOrNull(indexes.getValue(name))?.trim().orEmpty()
        val dateText = value("data")
        val description = value("descricao")
        val amountText = value("valor")
        val account = value("conta")
        val situation = value("situacao")
        val originalCategory = value("categoria")
        val date = runCatching { LocalDate.parse(dateText, dateFormatter) }.getOrNull()
        val amount = amountText.replace(",", ".").toBigDecimalOrNull()

        val reason = when {
            date == null -> "Data inválida"
            description.isBlank() -> "Descrição vazia"
            amount == null -> "Valor inválido"
            amount.signum() == 0 -> "Valor igual a zero"
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
            disposition = if (date!!.isAfter(today)) {
                ImportDisposition.PLANNED
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

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
