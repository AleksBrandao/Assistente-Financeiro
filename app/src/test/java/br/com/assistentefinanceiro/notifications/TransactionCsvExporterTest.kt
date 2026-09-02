package br.com.assistentefinanceiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionCsvExporterTest {
    @Test
    fun exportsExcelCompatibleCsvAndEscapesUserText() {
        val csv = TransactionCsvExporter.export(
            listOf(
                FinancialTransactionRecord(
                    id = 7,
                    sourceEventId = null,
                    direction = FinancialTransactionDirection.EXPENSE,
                    type = FinancialTransactionType.MANUAL_EXPENSE,
                    amount = "1250.00",
                    occurredAt = "2026-09-05T00:00",
                    description = "Moradia \"Nena\"\nparcela",
                    sourcePackage = "MANUAL",
                    status = TransactionStatus.PENDING,
                    dueDate = "2026-09-05",
                    plannedPaymentDate = "2026-10-05",
                    seriesId = "series-1",
                    seriesIndex = 7,
                    seriesTotal = 12,
                )
            )
        )

        assertTrue(csv.startsWith("\uFEFF\"ID\";\"Descrição\""))
        assertTrue(csv.contains("\"Moradia \"\"Nena\"\" parcela\""))
        assertTrue(csv.contains("\"2026-09-05\";\"2026-10-05\";\"\""))
        assertTrue(csv.endsWith("\"series-1\";\"7/12\""))
        assertEquals(2, csv.split("\r\n").size)
    }
}
