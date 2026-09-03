package br.com.assistentefinanceiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class FinancialTransactionRecordTest {
    @Test
    fun displaysCategoryAndSubcategory() {
        val transaction = record(
            category = TransactionCategory.HOUSING,
            subcategory = "Energia",
        )

        assertEquals("Moradia › Energia", transaction.categoryDisplayName)
    }

    @Test
    fun customCategoryReplacesFallbackName() {
        val transaction = record(
            category = TransactionCategory.OTHER_EXPENSE,
            customCategory = "Animais",
            subcategory = "Veterinário",
        )

        assertEquals("Animais › Veterinário", transaction.categoryDisplayName)
    }

    private fun record(
        category: TransactionCategory,
        customCategory: String? = null,
        subcategory: String? = null,
    ) = FinancialTransactionRecord(
        id = 1,
        sourceEventId = null,
        direction = FinancialTransactionDirection.EXPENSE,
        type = FinancialTransactionType.MANUAL_EXPENSE,
        amount = "1",
        occurredAt = "2026-09-02T00:00:00",
        description = "Teste",
        sourcePackage = "manual",
        category = category,
        customCategory = customCategory,
        subcategory = subcategory,
    )
}
