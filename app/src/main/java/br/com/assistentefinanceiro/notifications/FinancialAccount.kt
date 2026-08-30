package br.com.assistentefinanceiro.notifications

import java.text.Normalizer
import java.util.Locale

enum class FinancialAccountType(val displayName: String) {
    BANK_ACCOUNT("Conta bancária"),
    CREDIT_CARD("Cartão de crédito");

    companion object {
        fun fromStored(value: String?): FinancialAccountType =
            entries.firstOrNull { it.name == value } ?: BANK_ACCOUNT
    }
}

data class FinancialAccountRecord(
    val id: Long,
    val name: String,
    val type: FinancialAccountType,
    val closingDay: Int? = null,
    val dueDay: Int? = null,
    val isDefault: Boolean = false,
    val cardIdentifiers: String? = null,
)

fun FinancialAccountRecord.matchesCardLastFour(lastFour: String?): Boolean {
    val target = lastFour?.filter(Char::isDigit)?.takeLast(4) ?: return false
    return cardIdentifiers
        ?.split(",", "/", ";", " ")
        ?.map { it.filter(Char::isDigit).takeLast(4) }
        ?.any { it == target } == true
}

object FinancialAccountIdentity {
    fun normalize(name: String): String = Normalizer
        .normalize(name.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^A-Za-z0-9]"), "")
        .uppercase(Locale.ROOT)

    fun inferredType(name: String): FinancialAccountType = when (normalize(name)) {
        "CINZA", "VERMELHO", "PRETO", "CARREFOUR", "SEMPARAR", "3409EUR" ->
            FinancialAccountType.CREDIT_CARD
        else -> FinancialAccountType.BANK_ACCOUNT
    }

    fun normalizedIdentifiers(value: String?): String? = value
        ?.split(",", "/", ";", " ")
        ?.map { it.filter(Char::isDigit).takeLast(4) }
        ?.filter { it.length == 4 }
        ?.distinct()
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(",")
}
