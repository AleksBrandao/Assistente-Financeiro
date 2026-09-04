package br.com.assistentefinanceiro.importing

import br.com.assistentefinanceiro.notifications.FinancialAccountRecord
import br.com.assistentefinanceiro.notifications.FinancialAccountType
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobillsImportAccountPlannerTest {
    @Test
    fun preservesExistingAccountTypeAndNormalizesEquivalentNames() {
        val preview = MobillsImportAnalyzer.analyze(
            listOf(
                headers,
                row("01/08/2026", "Compra 1", "-10,00", "Cartão Família"),
                row("05/08/2026", "Compra 2", "-20,00", "Cartao Familia"),
            ),
        )
        val existing = FinancialAccountRecord(
            id = 7L,
            name = "Cartao Familia",
            type = FinancialAccountType.CREDIT_CARD,
        )

        val result = MobillsImportAccountPlanner.build(preview, listOf(existing))

        assertEquals(1, result.size)
        assertEquals(7L, result.single().existingAccountId)
        assertEquals(FinancialAccountType.CREDIT_CARD, result.single().selectedType)
        assertEquals(LocalDate.of(2026, 8, 1), result.single().firstTransactionDate)
        assertTrue(result.single().isExisting)
    }

    @Test
    fun requiresExplicitTypeForNewAccount() {
        val preview = MobillsImportAnalyzer.analyze(
            listOf(
                headers,
                row("20/08/2026", "Despesa", "-15,00", "Conta Principal"),
            ),
        )

        val result = MobillsImportAccountPlanner.build(preview, emptyList())

        assertEquals(1, result.size)
        assertEquals("Conta Principal", result.single().displayName)
        assertNull(result.single().existingAccountId)
        assertNull(result.single().selectedType)
        assertFalse(result.single().isExisting)
        assertEquals(LocalDate.of(2026, 8, 20), result.single().firstTransactionDate)
    }

    private fun row(
        date: String,
        description: String,
        amount: String,
        account: String,
    ) = listOf(date, description, amount, account, "Paga", "Compras")

    private companion object {
        val headers = listOf("Data", "Descrição", "Valor", "Conta", "Situação", "Categoria")
    }
}
