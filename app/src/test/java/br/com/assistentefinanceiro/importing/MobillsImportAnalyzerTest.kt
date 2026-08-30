package br.com.assistentefinanceiro.importing

import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.TransactionCategory
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MobillsImportAnalyzerTest {
    private val header = listOf(
        "Data", "Descrição", "Valor", "Conta", "Situação", "Categoria", "Subcategoria", "Tags"
    )

    @Test
    fun separatesRealizedPlannedDuplicatesAndZeroValue() {
        val duplicate = listOf(
            "20/08/2026", "Mercado", "-50.25", "Santander", "Efetivada", "Alimentação", "", ""
        )
        val preview = MobillsImportAnalyzer.analyze(
            rawRows = listOf(
                header,
                duplicate,
                duplicate,
                listOf(
                    "25/12/2027", "Parcela (21/36)", "-1061.01", "Santander",
                    "Pendente", "Moradia", "", "",
                ),
                listOf(
                    "30/08/2026", "Ajuste", "0", "Santander", "Efetivada", "Outros", "", "",
                ),
                listOf(
                    "29/08/2026", "Salário", "1000", "Santander", "Efetivada", "Salário", "", "",
                ),
            ),
            today = LocalDate.of(2026, 8, 30),
        )

        assertEquals(2, preview.readyCount)
        assertEquals(1, preview.plannedCount)
        assertEquals(1, preview.possibleDuplicateCount)
        assertEquals(1, preview.rejectedCount)
        assertEquals("Valor igual a zero", preview.rows[3].rejectionReason)
        assertNotEquals(preview.rows[0].importKey, preview.rows[1].importKey)
        assertEquals(FinancialTransactionDirection.INCOME, preview.rows[4].direction)
        assertEquals(TransactionCategory.SALARY, preview.rows[4].category)
    }

    @Test
    fun rejectsWorkbookWithoutRequiredHeaders() {
        val preview = MobillsImportAnalyzer.analyze(
            rawRows = listOf(listOf("Data", "Valor")),
            today = LocalDate.of(2026, 8, 30),
        )

        assertEquals(1, preview.rejectedCount)
        assertEquals(0, preview.readyCount)
    }
}
