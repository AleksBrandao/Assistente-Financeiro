package br.com.assistentefinanceiro.notifications

data class IgnoreInvoiceLinkMetadata(
    val accountId: Long?,
    val transactionType: FinancialTransactionType?,
    val invoiceId: Long?,
    val accountType: FinancialAccountType?,
    val linkedInvoiceHasConsolidatedValue: Boolean = false,
) {
    fun canIgnoreInvoiceLink(): Boolean =
        !linkedInvoiceHasConsolidatedValue && (
            transactionType == FinancialTransactionType.CARD_PURCHASE ||
                accountType == FinancialAccountType.CREDIT_CARD
            )
}
