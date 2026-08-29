package br.com.assistentefinanceiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionCategoryTest {
    @Test
    fun expenseCategoriesExcludeIncomeCategories() {
        val categories = TransactionCategory.availableFor(
            FinancialTransactionDirection.EXPENSE
        )

        assertTrue(TransactionCategory.UNCATEGORIZED in categories)
        assertTrue(TransactionCategory.FOOD in categories)
        assertTrue(TransactionCategory.TRANSPORT in categories)
        assertFalse(TransactionCategory.SALARY in categories)
        assertFalse(TransactionCategory.TRANSFER_IN in categories)
    }

    @Test
    fun incomeCategoriesExcludeExpenseCategories() {
        val categories = TransactionCategory.availableFor(
            FinancialTransactionDirection.INCOME
        )

        assertTrue(TransactionCategory.UNCATEGORIZED in categories)
        assertTrue(TransactionCategory.SALARY in categories)
        assertTrue(TransactionCategory.REFUND in categories)
        assertFalse(TransactionCategory.FOOD in categories)
        assertFalse(TransactionCategory.HOUSING in categories)
    }

    @Test
    fun unknownStoredCategoryFallsBackToUncategorized() {
        assertEquals(
            TransactionCategory.UNCATEGORIZED,
            TransactionCategory.fromStored("CATEGORIA_REMOVIDA"),
        )
        assertEquals(
            TransactionCategory.UNCATEGORIZED,
            TransactionCategory.fromStored(null),
        )
    }

    @Test
    fun categorySupportMatchesTransactionDirection() {
        assertTrue(TransactionCategory.FOOD.supports(FinancialTransactionDirection.EXPENSE))
        assertFalse(TransactionCategory.FOOD.supports(FinancialTransactionDirection.INCOME))
        assertTrue(TransactionCategory.UNCATEGORIZED.supports(FinancialTransactionDirection.INCOME))
        assertTrue(TransactionCategory.UNCATEGORIZED.supports(FinancialTransactionDirection.EXPENSE))
    }
}
