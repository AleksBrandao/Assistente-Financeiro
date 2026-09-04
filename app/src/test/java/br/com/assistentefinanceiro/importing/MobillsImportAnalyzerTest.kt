package br.com.assistentefinanceiro.importing

import br.com.assistentefinanceiro.notifications.FinancialTransactionDirection
import br.com.assistentefinanceiro.notifications.TransactionCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MobillsImportAnalyzerTest {
    private val header = listOf(
        "Data", "Descrição", "Valor", "Conta", "Situação", "Categoria", "Subcategoria", "Tags"
    )

    @Test
    fun separatesRealizedPlannedDuplicatesAndZeroValue() {
        val duplicate = listOf(
            "20/08/2026", "Mercado", "-50.25", "Santander", "Paga", "Alimentação", "", ""
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
                    "30/08/2026", "Ajuste", "0", "Santander", "Paga", "Outros", "", "",
                ),
                listOf(
                    "29/08/2026", "Salário", "1000", "Santander", "Paga", "Salário", "", "",
                ),
            ),
        )

        assertEquals(2, preview.readyCount)
        assertEquals(1, preview.pendingCount)
        assertEquals(1, preview.possibleDuplicateCount)
        assertEquals(1, preview.rejectedCount)
        assertEquals("Valor igual a zero", preview.rows[3].rejectionReason)
        assertNotEquals(preview.rows[0].importKey, preview.rows[1].importKey)
        assertEquals(FinancialTransactionDirection.INCOME, preview.rows[4].direction)
        assertEquals(TransactionCategory.SALARY, preview.rows[4].category)
    }

    @Test
    fun rejectsWorkbookWithoutRequiredHeaders() {
        val error = assertThrows(MobillsImportFormatException::class.java) {
            MobillsImportAnalyzer.analyze(
                rawRows = listOf(listOf("Data", "Valor")),
            )
        }

        assertTrue(error.message.orEmpty().contains("cabeçalhos obrigatórios ausentes"))
    }

    @Test
    fun acceptsRequiredColumnsInAnyOrder() {
        val preview = MobillsImportAnalyzer.analyze(
            rawRows = listOf(
                listOf("Categoria", "Situação", "Conta", "Valor", "Descrição", "Data", "Tags"),
                listOf("Transporte", "Paga", "Conta teste", "-25,90", "Combustível", "21/08/2026", ""),
            ),
        )

        assertEquals(1, preview.readyCount)
        assertEquals("Combustível", preview.rows.single().description)
        assertEquals("25.90", preview.rows.single().amount?.toPlainString())
        assertEquals(TransactionCategory.TRANSPORT, preview.rows.single().category)
    }

    @Test
    fun rejectsEmptyRequiredCellsWithSpecificReasons() {
        val preview = MobillsImportAnalyzer.analyze(
            rawRows = listOf(
                header,
                listOf("", "Compra", "-10", "Conta teste", "Paga", "Compras"),
                listOf("20/08/2026", "", "-10", "Conta teste", "Paga", "Compras"),
                listOf("20/08/2026", "Compra", "", "Conta teste", "Paga", "Compras"),
                listOf("20/08/2026", "Compra", "-10", "", "Paga", "Compras"),
                listOf("20/08/2026", "Compra", "-10", "Conta teste", "", "Compras"),
            ),
        )

        assertEquals(5, preview.rejectedCount)
        assertTrue(preview.rejectionReasons.keys.any { it.startsWith("Data inválida") })
        assertEquals(1, preview.rejectionReasons["Descrição vazia"])
        assertEquals(1, preview.rejectionReasons["Valor inválido"])
        assertEquals(1, preview.rejectionReasons["Conta vazia"])
        assertEquals(1, preview.rejectionReasons["Situação inválida"])
    }

    @Test
    fun parsesNegativeAndLocalizedAmountsWithoutChangingDirection() {
        val preview = MobillsImportAnalyzer.analyze(
            rawRows = listOf(
                header,
                row(description = "Decimal com ponto", amount = "-1234.56"),
                row(description = "Decimal com vírgula", amount = "-1234,56"),
                row(description = "Milhar brasileiro", amount = "-1.234,56"),
                row(description = "Valor entre parênteses", amount = "R$ (1.234,56)"),
            ),
        )

        assertEquals(4, preview.readyCount)
        preview.rows.forEach { parsed ->
            assertEquals(FinancialTransactionDirection.EXPENSE, parsed.direction)
            assertEquals("1234.56", parsed.amount?.toPlainString())
        }
    }

    @Test
    fun acceptsDocumentedDateFormatsAndRejectsAmbiguousDate() {
        val preview = MobillsImportAnalyzer.analyze(
            rawRows = listOf(
                header,
                row(date = "20/08/2026", description = "Formato brasileiro"),
                row(date = "20-08-2026", description = "Formato com hífen"),
                row(date = "2026-08-20", description = "Formato ISO"),
                row(date = "08/20/2026", description = "Formato ambíguo"),
            ),
        )

        assertEquals(3, preview.readyCount)
        assertEquals(1, preview.rejectedCount)
        assertTrue(preview.rows.last().rejectionReason.orEmpty().startsWith("Data inválida"))
    }

    @Test
    fun rejectsEmptyWorkbookExplicitly() {
        val error = assertThrows(MobillsImportFormatException::class.java) {
            MobillsImportAnalyzer.analyze(emptyList())
        }

        assertTrue(error.message.orEmpty().contains("planilha está vazia"))
    }

    private fun row(
        date: String = "20/08/2026",
        description: String,
        amount: String = "-10.00",
    ) = listOf(date, description, amount, "Conta teste", "Paga", "Compras", "", "")
}
