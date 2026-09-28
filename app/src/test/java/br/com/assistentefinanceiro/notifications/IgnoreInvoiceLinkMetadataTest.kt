package br.com.assistentefinanceiro.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnoreInvoiceLinkMetadataTest {
    @Test
    fun `allows imported expense assigned to a credit card`() {
        val metadata = IgnoreInvoiceLinkMetadata(
            accountId = 10L,
            transactionType = FinancialTransactionType.IMPORTED_EXPENSE,
            invoiceId = null,
            accountType = FinancialAccountType.CREDIT_CARD,
        )

        assertTrue(metadata.canIgnoreInvoiceLink())
    }

    @Test
    fun `does not allow a transaction linked to a consolidated invoice`() {
        val metadata = IgnoreInvoiceLinkMetadata(
            accountId = 10L,
            transactionType = FinancialTransactionType.CARD_PURCHASE,
            invoiceId = 20L,
            accountType = FinancialAccountType.CREDIT_CARD,
            linkedInvoiceHasConsolidatedValue = true,
        )

        assertFalse(metadata.canIgnoreInvoiceLink())
    }

    @Test
    fun `allows a card transaction linked to an unconsolidated invoice`() {
        val metadata = IgnoreInvoiceLinkMetadata(
            accountId = 10L,
            transactionType = FinancialTransactionType.CARD_PURCHASE,
            invoiceId = 20L,
            accountType = FinancialAccountType.CREDIT_CARD,
            linkedInvoiceHasConsolidatedValue = false,
        )

        assertTrue(metadata.canIgnoreInvoiceLink())
    }

    @Test
    fun `does not allow ordinary bank expense`() {
        val metadata = IgnoreInvoiceLinkMetadata(
            accountId = 10L,
            transactionType = FinancialTransactionType.IMPORTED_EXPENSE,
            invoiceId = null,
            accountType = FinancialAccountType.BANK_ACCOUNT,
        )

        assertFalse(metadata.canIgnoreInvoiceLink())
    }
}
