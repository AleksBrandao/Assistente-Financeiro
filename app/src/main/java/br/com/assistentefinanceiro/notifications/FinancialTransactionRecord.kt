package br.com.assistentefinanceiro.notifications

data class FinancialTransactionRecord(
    val id: Long,
    val sourceEventId: Long,
    val direction: FinancialTransactionDirection,
    val type: FinancialTransactionType,
    val amount: String,
    val occurredAt: String,
    val description: String,
    val sourcePackage: String,
    val category: TransactionCategory = TransactionCategory.UNCATEGORIZED,
)
