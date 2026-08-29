package br.com.assistentefinanceiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionCategoryRuleTest {
    @Test
    fun normalizeMerchant_ignoresCaseAccentsAndPunctuation() {
        assertEquals(
            "CAFE SAO JOAO",
            TransactionCategoryRule.normalizeMerchant("  Café São-João!!!  "),
        )
    }

    @Test
    fun normalizeMerchant_collapsesWhitespace() {
        assertEquals(
            "SHOPEE QUALYCE",
            TransactionCategoryRule.normalizeMerchant("Shopee   .qualyce"),
        )
    }

    @Test
    fun normalizeMerchant_returnsNullForBlankInput() {
        assertNull(TransactionCategoryRule.normalizeMerchant("   "))
        assertNull(TransactionCategoryRule.normalizeMerchant(null))
    }

    @Test
    fun categorySource_unknownStoredValueFallsBackToDefault() {
        assertEquals(
            TransactionCategorySource.DEFAULT,
            TransactionCategorySource.fromStored("UNKNOWN"),
        )
    }

    @Test
    fun futureRule_requiresCategorizedCardPurchaseAndMerchantKey() {
        assertTrue(
            TransactionCategoryRule.canApplyToFuture(
                FinancialTransactionType.CARD_PURCHASE,
                TransactionCategory.FOOD,
                "CAFE",
            )
        )
        assertFalse(
            TransactionCategoryRule.canApplyToFuture(
                FinancialTransactionType.CARD_PURCHASE,
                TransactionCategory.UNCATEGORIZED,
                "CAFE",
            )
        )
        assertFalse(
            TransactionCategoryRule.canApplyToFuture(
                FinancialTransactionType.PIX_RECEIVED,
                TransactionCategory.TRANSFER_IN,
                null,
            )
        )
    }
}
