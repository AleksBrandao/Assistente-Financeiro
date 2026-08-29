package br.com.assistentefinanceiro.notifications

enum class TransactionCategory(
    val displayName: String,
    private val supportedDirection: FinancialTransactionDirection?,
) {
    UNCATEGORIZED("Sem categoria", null),

    FOOD("Alimentação", FinancialTransactionDirection.EXPENSE),
    TRANSPORT("Transporte", FinancialTransactionDirection.EXPENSE),
    HOUSING("Moradia", FinancialTransactionDirection.EXPENSE),
    HEALTH("Saúde", FinancialTransactionDirection.EXPENSE),
    SHOPPING("Compras", FinancialTransactionDirection.EXPENSE),
    EDUCATION("Educação", FinancialTransactionDirection.EXPENSE),
    LEISURE("Lazer", FinancialTransactionDirection.EXPENSE),
    SERVICES("Serviços", FinancialTransactionDirection.EXPENSE),
    OTHER_EXPENSE("Outras despesas", FinancialTransactionDirection.EXPENSE),

    SALARY("Salário", FinancialTransactionDirection.INCOME),
    TRANSFER_IN("Transferência recebida", FinancialTransactionDirection.INCOME),
    REFUND("Reembolso", FinancialTransactionDirection.INCOME),
    INVESTMENT_INCOME("Rendimentos", FinancialTransactionDirection.INCOME),
    OTHER_INCOME("Outras entradas", FinancialTransactionDirection.INCOME);

    fun supports(direction: FinancialTransactionDirection): Boolean =
        supportedDirection == null || supportedDirection == direction

    companion object {
        fun fromStored(value: String?): TransactionCategory =
            entries.firstOrNull { it.name == value } ?: UNCATEGORIZED

        fun availableFor(direction: FinancialTransactionDirection): List<TransactionCategory> =
            entries.filter { it.supports(direction) }
    }
}
