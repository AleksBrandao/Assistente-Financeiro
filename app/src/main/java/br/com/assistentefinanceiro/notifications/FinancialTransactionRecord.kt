package br.com.assistentefinanceiro.notifications

data class FinancialTransactionRecord(
    val id: Long,
    val sourceEventId: Long?,
    val direction: FinancialTransactionDirection,
    val type: FinancialTransactionType,
    val amount: String,
    val occurredAt: String,
    val description: String,
    val sourcePackage: String,
    val category: TransactionCategory = TransactionCategory.UNCATEGORIZED,
    val categorySource: TransactionCategorySource = TransactionCategorySource.DEFAULT,
    val ruleKey: String? = null,
    val origin: TransactionOrigin = TransactionOrigin.NOTIFICATION,
    val status: TransactionStatus = TransactionStatus.REALIZED,
    val account: String? = null,
    val originalCategory: String? = null,
    val originalStatus: String? = null,
    val accountId: Long? = null,
)

enum class TransactionOrigin {
    NOTIFICATION,
    MOBILLS;

    companion object {
        fun fromStored(value: String?): TransactionOrigin =
            entries.firstOrNull { it.name == value } ?: NOTIFICATION
    }
}

enum class TransactionStatus {
    REALIZED,
    PENDING;

    companion object {
        fun fromStored(value: String?): TransactionStatus =
            entries.firstOrNull { it.name == value } ?: REALIZED
    }
}
