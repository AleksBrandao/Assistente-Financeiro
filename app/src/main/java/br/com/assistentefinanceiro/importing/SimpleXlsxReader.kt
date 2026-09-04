package br.com.assistentefinanceiro.importing

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

object SimpleXlsxReader {
    private const val TARGET_SHEET = "Receitas e Despesas"
    private const val MAX_ARCHIVE_ENTRIES = 512
    private const val MAX_ENTRY_BYTES = 32 * 1024 * 1024
    private const val MAX_TOTAL_XML_BYTES = 64 * 1024 * 1024
    private const val MAX_ROWS = 200_000
    private const val MAX_COLUMNS = 256

    fun readMobillsRows(input: InputStream): List<List<String>> = try {
        val entries = unzip(input)
        if ("[Content_Types].xml" !in entries) {
            invalid("o arquivo não contém a estrutura obrigatória de um XLSX")
        }
        val workbook = entries["xl/workbook.xml"]
            ?: invalid("a pasta de trabalho está ausente")
        val relationshipsXml = entries["xl/_rels/workbook.xml.rels"]
            ?: invalid("os relacionamentos da pasta de trabalho estão ausentes")
        val relationshipId = findSheetRelationship(workbook)
        val relationships = readRelationships(relationshipsXml)
        val sheetRelationship = relationships[relationshipId]
            ?: invalid("o relacionamento da aba '$TARGET_SHEET' está ausente")
        if (sheetRelationship.external || !sheetRelationship.type.endsWith("/worksheet")) {
            invalid("o relacionamento da aba '$TARGET_SHEET' não é uma planilha interna")
        }
        val sheetPath = resolvePartPath("xl/workbook.xml", sheetRelationship.target)
        val sheet = entries[sheetPath]
            ?: invalid("os dados da aba '$TARGET_SHEET' estão ausentes")

        val sharedStringsPath = relationships.values
            .filter { !it.external && it.type.endsWith("/sharedStrings") }
            .singleOrNull()
            ?.let { resolvePartPath("xl/workbook.xml", it.target) }
        val sharedStrings = (sharedStringsPath?.let(entries::get)
            ?: entries["xl/sharedStrings.xml"])
            ?.let(::readSharedStrings)
            .orEmpty()

        readSheet(sheet, sharedStrings)
    } catch (error: MobillsImportFormatException) {
        throw error
    } catch (error: Exception) {
        throw MobillsImportFormatException(
            "Arquivo XLSX inválido: não foi possível interpretar a planilha.",
            error,
        )
    }

    private fun unzip(input: InputStream): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var entryCount = 0
        var totalXmlBytes = 0
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entryCount++
                if (entryCount > MAX_ARCHIVE_ENTRIES) {
                    invalid("o arquivo contém entradas demais")
                }
                val name = entry.name.replace('\\', '/')
                validateEntryName(name)
                if (!entry.isDirectory && shouldRead(name)) {
                    if (name in entries) invalid("há uma entrada duplicada: $name")
                    val bytes = readEntry(zip, name)
                    totalXmlBytes += bytes.size
                    if (totalXmlBytes > MAX_TOTAL_XML_BYTES) {
                        invalid("o conteúdo da planilha excede o limite suportado")
                    }
                    entries[name] = bytes
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (entryCount == 0) invalid("o arquivo selecionado não é um pacote XLSX")
        return entries
    }

    private fun shouldRead(name: String): Boolean = when {
        name == "[Content_Types].xml" -> true
        name == "xl/workbook.xml" -> true
        name == "xl/_rels/workbook.xml.rels" -> true
        name == "xl/sharedStrings.xml" -> true
        name.startsWith("xl/worksheets/") && name.endsWith(".xml") -> true
        else -> false
    }

    private fun validateEntryName(name: String) {
        if (
            name.isBlank() || name.startsWith("/") || name.contains(':') ||
            name.split('/').any { it == ".." }
        ) {
            invalid("o arquivo contém um caminho interno inválido")
        }
    }

    private fun readEntry(zip: ZipInputStream, name: String): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_ENTRY_BYTES) {
                invalid("a entrada '$name' excede o limite suportado")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun findSheetRelationship(xml: ByteArray): String {
        var relationshipId: String? = null
        parseXml(xml, "xl/workbook.xml", object : StrictXmlHandler() {
            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String?,
                attributes: Attributes,
            ) {
                if (tag(localName, qName) != "sheet") return
                if (attributes.value("name") != TARGET_SHEET) return
                if (relationshipId != null) {
                    invalid("há mais de uma aba chamada '$TARGET_SHEET'")
                }
                relationshipId = attributes.value("id")
                    ?: invalid("a aba '$TARGET_SHEET' não possui identificador")
            }
        })
        return relationshipId ?: invalid("a aba '$TARGET_SHEET' não foi encontrada")
    }

    private fun readRelationships(xml: ByteArray): Map<String, Relationship> {
        val relationships = linkedMapOf<String, Relationship>()
        parseXml(xml, "xl/_rels/workbook.xml.rels", object : StrictXmlHandler() {
            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String?,
                attributes: Attributes,
            ) {
                if (tag(localName, qName) != "Relationship") return
                val id = attributes.value("Id")
                    ?: invalid("há um relacionamento sem identificador")
                if (id in relationships) invalid("há relacionamentos duplicados")
                relationships[id] = Relationship(
                    type = attributes.value("Type").orEmpty(),
                    target = attributes.value("Target")
                        ?: invalid("o relacionamento '$id' não possui destino"),
                    external = attributes.value("TargetMode").equals("External", true),
                )
            }
        })
        return relationships
    }

    private fun resolvePartPath(basePart: String, target: String): String {
        val cleanTarget = target.replace('\\', '/')
        if (cleanTarget.isBlank() || cleanTarget.contains(':') || cleanTarget.contains('#')) {
            invalid("há um destino de relacionamento inválido")
        }
        val candidate = when {
            cleanTarget.startsWith('/') -> cleanTarget.removePrefix("/")
            cleanTarget.startsWith("xl/") -> cleanTarget
            else -> "${basePart.substringBeforeLast('/')}/$cleanTarget"
        }
        val segments = mutableListOf<String>()
        candidate.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) {
                    invalid("há um destino fora da pasta de trabalho")
                } else {
                    segments.removeAt(segments.lastIndex)
                }
                else -> segments += segment
            }
        }
        return segments.joinToString("/").takeIf { it.startsWith("xl/") }
            ?: invalid("há um destino fora da pasta de trabalho")
    }

    private fun readSharedStrings(xml: ByteArray): List<String> {
        val result = mutableListOf<String>()
        var current: StringBuilder? = null
        var textDepth = 0
        var phoneticDepth = 0
        parseXml(xml, "xl/sharedStrings.xml", object : StrictXmlHandler() {
            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String?,
                attributes: Attributes,
            ) {
                when (tag(localName, qName)) {
                    "si" -> {
                        if (current != null) invalid("a tabela de textos compartilhados está inválida")
                        current = StringBuilder()
                    }
                    "rPh" -> phoneticDepth++
                    "t" -> if (current != null && phoneticDepth == 0) textDepth++
                }
            }

            override fun characters(chars: CharArray, start: Int, length: Int) {
                if (textDepth > 0) current?.append(chars, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (tag(localName, qName)) {
                    "t" -> if (textDepth > 0) textDepth--
                    "rPh" -> if (phoneticDepth > 0) phoneticDepth--
                    "si" -> {
                        result += current?.toString()
                            ?: invalid("a tabela de textos compartilhados está inválida")
                        current = null
                    }
                }
            }
        })
        return result
    }

    private fun readSheet(xml: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var currentRowNumber: Int? = null
        var previousRowNumber = 0
        var currentRow = linkedMapOf<Int, String>()
        var currentCell: CellState? = null

        parseXml(xml, "aba '$TARGET_SHEET'", object : StrictXmlHandler() {
            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String?,
                attributes: Attributes,
            ) {
                when (tag(localName, qName)) {
                    "mergeCell" -> invalid(
                        "a aba '$TARGET_SHEET' contém células mescladas, que não são suportadas",
                    )
                    "row" -> {
                        if (currentRowNumber != null) invalid("há linhas sobrepostas na planilha")
                        val rowNumber = attributes.value("r")?.toIntOrNull()
                            ?: (previousRowNumber + 1)
                        if (rowNumber <= previousRowNumber || rowNumber > MAX_ROWS) {
                            invalid("a numeração das linhas está inválida")
                        }
                        while (rows.size < rowNumber - 1) rows.add(emptyList())
                        currentRowNumber = rowNumber
                        currentRow = linkedMapOf()
                    }
                    "c" -> {
                        val rowNumber = currentRowNumber
                            ?: invalid("há uma célula fora de uma linha")
                        if (currentCell != null) invalid("há células sobrepostas na planilha")
                        val reference = attributes.value("r")
                            ?: invalid("há uma célula sem referência")
                        val match = CELL_REFERENCE.matchEntire(reference)
                            ?: invalid("a referência de célula '$reference' é inválida")
                        val referenceRow = match.groupValues[2].toIntOrNull()
                        if (referenceRow != rowNumber) {
                            invalid("a referência de célula '$reference' não corresponde à linha")
                        }
                        val column = columnIndex(match.groupValues[1])
                        if (column !in 0 until MAX_COLUMNS) {
                            invalid("a planilha excede o limite de colunas suportado")
                        }
                        if (column in currentRow) invalid("há células duplicadas na linha $rowNumber")
                        currentCell = CellState(
                            reference = reference,
                            column = column,
                            type = attributes.value("t"),
                        )
                    }
                    "v" -> currentCell?.readingValue = true
                    "f" -> currentCell?.hasFormula = true
                    "t" -> currentCell
                        ?.takeIf { it.type == "inlineStr" }
                        ?.let { it.inlineTextDepth++ }
                }
            }

            override fun characters(chars: CharArray, start: Int, length: Int) {
                currentCell?.let { cell ->
                    if (cell.readingValue) cell.value.append(chars, start, length)
                    if (cell.inlineTextDepth > 0) cell.inlineText.append(chars, start, length)
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (tag(localName, qName)) {
                    "v" -> currentCell?.readingValue = false
                    "t" -> currentCell?.let { if (it.inlineTextDepth > 0) it.inlineTextDepth-- }
                    "c" -> {
                        val cell = currentCell ?: invalid("há uma célula incompleta")
                        currentRow[cell.column] = decodeCell(cell, sharedStrings)
                        currentCell = null
                    }
                    "row" -> {
                        val rowNumber = currentRowNumber ?: invalid("há uma linha incompleta")
                        val lastColumn = currentRow.keys.maxOrNull() ?: -1
                        rows += (0..lastColumn).map { currentRow[it].orEmpty() }
                        previousRowNumber = rowNumber
                        currentRowNumber = null
                    }
                }
            }
        })
        if (currentCell != null || currentRowNumber != null) invalid("a planilha terminou incompleta")
        return rows
    }

    private fun decodeCell(cell: CellState, sharedStrings: List<String>): String {
        val raw = cell.value.toString()
        if (cell.hasFormula && raw.isBlank()) {
            invalid("a célula ${cell.reference} contém fórmula sem resultado armazenado")
        }
        return when (cell.type) {
            null, "n", "str", "d" -> raw
            "inlineStr" -> cell.inlineText.toString()
            "s" -> {
                val index = raw.toIntOrNull()
                    ?: invalid("a célula ${cell.reference} possui texto compartilhado inválido")
                sharedStrings.getOrNull(index)
                    ?: invalid("a célula ${cell.reference} referencia um texto inexistente")
            }
            "b" -> when (raw) {
                "0" -> "FALSO"
                "1" -> "VERDADEIRO"
                else -> invalid("a célula ${cell.reference} possui valor booleano inválido")
            }
            "e" -> invalid("a célula ${cell.reference} contém um erro do Excel")
            else -> invalid(
                "a célula ${cell.reference} usa um tipo não suportado (${cell.type})",
            )
        }
    }

    private fun parseXml(xml: ByteArray, partName: String, handler: DefaultHandler) {
        if (xml.toString(Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) {
            invalid("o XML de $partName contém uma declaração não permitida")
        }
        try {
            SAXParserFactory.newInstance().apply { isNamespaceAware = true }
                .newSAXParser()
                .parse(ByteArrayInputStream(xml), handler)
        } catch (error: MobillsImportFormatException) {
            throw error
        } catch (error: Exception) {
            throw MobillsImportFormatException(
                "Arquivo XLSX inválido: o XML de $partName está corrompido.",
                error,
            )
        }
    }

    private fun columnIndex(letters: String): Int = letters.fold(0) { total, char ->
        total * 26 + (char.uppercaseChar() - 'A' + 1)
    } - 1

    private fun invalid(detail: String): Nothing = throw MobillsImportFormatException(
        "Arquivo XLSX inválido: $detail.",
    )

    private fun tag(localName: String?, qName: String?): String = localName
        ?.takeIf { it.isNotBlank() }
        ?: qName.orEmpty().substringAfter(':')

    private fun Attributes.value(name: String): String? = (0 until length)
        .firstOrNull {
            getLocalName(it).equals(name, ignoreCase = false) ||
                getQName(it).substringAfter(':').equals(name, ignoreCase = false)
        }
        ?.let(::getValue)

    private open class StrictXmlHandler : DefaultHandler() {
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
            InputSource(StringReader(""))
    }

    private data class Relationship(
        val type: String,
        val target: String,
        val external: Boolean,
    )

    private data class CellState(
        val reference: String,
        val column: Int,
        val type: String?,
        val value: StringBuilder = StringBuilder(),
        val inlineText: StringBuilder = StringBuilder(),
        var readingValue: Boolean = false,
        var inlineTextDepth: Int = 0,
        var hasFormula: Boolean = false,
    )

    private val CELL_REFERENCE = Regex("^([A-Za-z]+)([1-9][0-9]*)$")
}
