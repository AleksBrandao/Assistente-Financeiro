package br.com.assistentefinanceiro.notifications

import java.text.Normalizer
import java.util.Locale

enum class TransactionCategorySource {
    DEFAULT,
    RULE,
    MANUAL,
    EXTERNAL;

    companion object {
        fun fromStored(value: String?): TransactionCategorySource =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

object TransactionCategoryRule {
    fun normalizeMerchant(value: String?): String? {
        val merchant = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return Normalizer.normalize(merchant, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .uppercase(Locale.ROOT)
            .replace(NON_ALPHANUMERIC, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    fun canApplyToFuture(
        type: FinancialTransactionType,
        category: TransactionCategory,
        ruleKey: String?,
    ): Boolean =
        type == FinancialTransactionType.CARD_PURCHASE &&
            category != TransactionCategory.UNCATEGORIZED &&
            !ruleKey.isNullOrBlank()

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val NON_ALPHANUMERIC = Regex("[^A-Z0-9]+")
    private val WHITESPACE = Regex("\\s+")
}
