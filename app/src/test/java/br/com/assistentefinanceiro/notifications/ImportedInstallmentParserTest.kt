package br.com.assistentefinanceiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportedInstallmentParserTest {
    @Test
    fun `parses Mobills installment suffix`() {
        assertEquals(
            ImportedInstallment("Moradia Nena", 7, 12),
            ImportedInstallmentParser.parse("Moradia Nena (7/12)"),
        )
    }

    @Test
    fun `rejects invalid installment index`() {
        assertNull(ImportedInstallmentParser.parse("Moradia Nena (13/12)"))
        assertNull(ImportedInstallmentParser.parse("Compra (1/1)"))
    }
}
