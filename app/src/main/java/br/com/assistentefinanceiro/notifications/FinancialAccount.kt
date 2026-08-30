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
)

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
}
