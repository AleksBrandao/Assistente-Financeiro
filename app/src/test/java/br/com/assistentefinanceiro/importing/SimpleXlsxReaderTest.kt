package br.com.assistentefinanceiro.importing

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleXlsxReaderTest {
    @Test
    fun readsTargetSheetAmongExtraSheetsAndSupportsCommonStringTypes() {
        val rows = SimpleXlsxReader.readMobillsRows(ByteArrayInputStream(workbook()))

        assertEquals(
            listOf("Data", "Descrição", "Valor", "Conta", "Situação", "Categoria"),
            rows.first(),
        )
        assertEquals(
            listOf("20/08/2026", "Mercado", "-25.90", "Conta teste", "Paga", "Alimentação"),
            rows[1],
        )
    }

    @Test
    fun preservesEmptyCellsUsingTheirExcelReferences() {
        val sheet = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1">
                  <c r="A1" t="inlineStr"><is><t>Data</t></is></c>
                  <c r="C1" t="inlineStr"><is><t>Valor</t></is></c>
                </row>
              </sheetData>
            </worksheet>
        """.trimIndent()

        val rows = SimpleXlsxReader.readMobillsRows(
            ByteArrayInputStream(workbook(sheetXml = sheet)),
        )

        assertEquals(listOf("Data", "", "Valor"), rows.single())
    }

    @Test
    fun rejectsMergedCellsWithClearMessage() {
        val mergedSheet = validSheetXml().replace(
            "</worksheet>",
            "<mergeCells count=\"1\"><mergeCell ref=\"A1:B1\"/></mergeCells></worksheet>",
        )

        val error = assertThrows(MobillsImportFormatException::class.java) {
            SimpleXlsxReader.readMobillsRows(
                ByteArrayInputStream(workbook(sheetXml = mergedSheet)),
            )
        }

        assertTrue(error.message.orEmpty().contains("células mescladas"))
    }

    @Test
    fun rejectsInvalidSharedStringReferenceWithClearMessage() {
        val invalidSheet = validSheetXml().replace("<v>0</v>", "<v>999</v>")

        val error = assertThrows(MobillsImportFormatException::class.java) {
            SimpleXlsxReader.readMobillsRows(
                ByteArrayInputStream(workbook(sheetXml = invalidSheet)),
            )
        }

        assertTrue(error.message.orEmpty().contains("texto inexistente"))
    }

    @Test
    fun rejectsWorkbookWithoutExpectedSheetWithClearMessage() {
        val workbookXml = """
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <sheets><sheet name="Resumo" sheetId="1" r:id="rId1"/></sheets>
            </workbook>
        """.trimIndent()

        val error = assertThrows(MobillsImportFormatException::class.java) {
            SimpleXlsxReader.readMobillsRows(
                ByteArrayInputStream(workbook(workbookXml = workbookXml)),
            )
        }

        assertTrue(error.message.orEmpty().contains("não foi encontrada"))
    }

    private fun workbook(
        workbookXml: String = validWorkbookXml(),
        sheetXml: String = validSheetXml(),
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.writeEntry("[Content_Types].xml", "<Types/>")
            zip.writeEntry("xl/workbook.xml", workbookXml)
            zip.writeEntry("xl/_rels/workbook.xml.rels", relationshipsXml())
            zip.writeEntry(
                "xl/worksheets/sheet1.xml",
                "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData/></worksheet>",
            )
            zip.writeEntry("xl/worksheets/sheet2.xml", sheetXml)
            zip.writeEntry("xl/sharedStrings.xml", sharedStringsXml())
        }
        return output.toByteArray()
    }

    private fun ZipOutputStream.writeEntry(name: String, contents: String) {
        putNextEntry(ZipEntry(name))
        write(contents.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun validWorkbookXml() = """
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
          <sheets>
            <sheet name="Resumo" sheetId="1" r:id="rId1"/>
            <sheet name="Receitas e Despesas" sheetId="2" r:id="rId2"/>
          </sheets>
        </workbook>
    """.trimIndent()

    private fun relationshipsXml() = """
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1"
              Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
              Target="worksheets/sheet1.xml"/>
          <Relationship Id="rId2"
              Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
              Target="/xl/worksheets/sheet2.xml"/>
          <Relationship Id="rId3"
              Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings"
              Target="sharedStrings.xml"/>
        </Relationships>
    """.trimIndent()

    private fun sharedStringsXml() = """
        <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="6" uniqueCount="6">
          <si><t>Data</t></si>
          <si><t>Descrição</t></si>
          <si><t>Valor</t></si>
          <si><t>Conta</t></si>
          <si><t>Situação</t></si>
          <si><t>Categoria</t></si>
        </sst>
    """.trimIndent()

    private fun validSheetXml() = """
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <sheetData>
            <row r="1">
              <c r="A1" t="s"><v>0</v></c>
              <c r="B1" t="s"><v>1</v></c>
              <c r="C1" t="s"><v>2</v></c>
              <c r="D1" t="s"><v>3</v></c>
              <c r="E1" t="s"><v>4</v></c>
              <c r="F1" t="s"><v>5</v></c>
            </row>
            <row r="2">
              <c r="A2" t="inlineStr"><is><t>20/08/2026</t></is></c>
              <c r="B2" t="inlineStr"><is><r><t>Mer</t></r><r><t>cado</t></r></is></c>
              <c r="C2"><v>-25.90</v></c>
              <c r="D2" t="inlineStr"><is><t>Conta teste</t></is></c>
              <c r="E2" t="inlineStr"><is><t>Paga</t></is></c>
              <c r="F2" t="inlineStr"><is><t>Alimentação</t></is></c>
            </row>
          </sheetData>
        </worksheet>
    """.trimIndent()
}
