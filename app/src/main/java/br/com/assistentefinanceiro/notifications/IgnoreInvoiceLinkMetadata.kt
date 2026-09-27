package br.com.assistentefinanceiro.notifications

data class IgnoreInvoiceLinkMetadata(
    val accountId: Long?,
    val transactionType: FinancialTransactionType,
    val invoiceId: Long?,
    val accountType: FinancialAccountType?,
) {
    fun canIgnoreInvoiceLink(): Boolean =
        invoiceId == null && (
            transactionType == FinancialTransactionType.CARD_PURCHASE ||
                accountType == FinancialAccountType.CREDIT_CARD
            )
}
