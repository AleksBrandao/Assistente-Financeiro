package br.com.assistentefinanceiro.notifications

data class ImportedInstallment(
    val baseDescription: String,
    val index: Int,
    val total: Int,
)

object ImportedInstallmentParser {
    private val suffix = Regex("""\s*\((\d{1,3})/(\d{1,3})\)\s*$""")

    fun parse(description: String): ImportedInstallment? {
        val match = suffix.find(description) ?: return null
        val index = match.groupValues[1].toIntOrNull() ?: return null
        val total = match.groupValues[2].toIntOrNull() ?: return null
        if (index !in 1..total || total !in 2..120) return null
        val base = description.removeRange(match.range).trim()
        if (base.isBlank()) return null
        return ImportedInstallment(base, index, total)
    }
}
