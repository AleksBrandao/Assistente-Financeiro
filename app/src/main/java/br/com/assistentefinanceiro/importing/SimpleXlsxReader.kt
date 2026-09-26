package br.com.assistentefinanceiro.importing

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

object SimpleXlsxReader {
    private const val TARGET_SHEET = "Receitas e Despesas"

    fun readMobillsRows(input: InputStream): List<List<String>> {
        val entries = unzip(input)
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let(::readSharedStrings).orEmpty()
        val relationshipId = findSheetRelationship(
            entries["xl/workbook.xml"] ?: error("Pasta de trabalho inválida"),
        )
        val sheetPath = findSheetPath(
            entries["xl/_rels/workbook.xml.rels"] ?: error("Relacionamentos ausentes"),
            relationshipId,
        )
        val normalizedPath = if (sheetPath.startsWith("xl/")) sheetPath else "xl/$sheetPath"
        val sheet = entries[normalizedPath] ?: error("Aba '$TARGET_SHEET' não encontrada")
        return readSheet(sheet, sharedStrings)
    }

    private fun unzip(input: InputStream): Map<String, ByteArray> = buildMap {
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && (
                        entry.name in setOf(
                            "xl/sharedStrings.xml",
                            "xl/workbook.xml",
                            "xl/_rels/workbook.xml.rels",
                        ) || (
                            entry.name.startsWith("xl/worksheets/") &&
                                entry.name.endsWith(".xml")
                            )
                        )
                ) {
                    put(entry.name, zip.readBytes())
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun readSharedStrings(xml: ByteArray): List<String> {
        val parser = parser(xml)
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var insideItem = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.name == "si") {
                    insideItem = true
                    current = StringBuilder()
                }
                XmlPullParser.TEXT -> if (insideItem) current.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.name == "si") {
                    result += current.toString()
                    insideItem = false
                }
            }
            parser.next()
        }
        return result
    }

    private fun findSheetRelationship(xml: ByteArray): String {
        val parser = parser(xml)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
                if (parser.getAttributeValue(null, "name") == TARGET_SHEET) {
                    return parser.getAttributeValue(
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                        "id",
                    ) ?: parser.getAttributeValue(null, "r:id")
                    ?: error("Identificador da aba ausente")
                }
            }
            parser.next()
        }
        error("Aba '$TARGET_SHEET' não encontrada")
    }

    private fun findSheetPath(xml: ByteArray, relationshipId: String): String {
        val parser = parser(xml)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
                if (parser.getAttributeValue(null, "Id") == relationshipId) {
                    return parser.getAttributeValue(null, "Target")
                        ?.removePrefix("/")
                        ?.removePrefix("xl/")
                        ?: error("Destino da aba ausente")
                }
            }
            parser.next()
        }
        error("Relacionamento da aba não encontrado")
    }

    private fun readSheet(xml: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val parser = parser(xml)
        val rows = mutableListOf<List<String>>()
        var row = mutableMapOf<Int, String>()
        var cellIndex = -1
        var cellType: String? = null
        var value: String? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> row = mutableMapOf()
                    "c" -> {
                        cellIndex = columnIndex(parser.getAttributeValue(null, "r").orEmpty())
                        cellType = parser.getAttributeValue(null, "t")
                        value = null
                    }
                }
                XmlPullParser.TEXT -> if (parser.name == null) value = parser.text
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> Unit
                    "c" -> {
                        val raw = value.orEmpty()
                        row[cellIndex] = if (cellType == "s") {
                            sharedStrings.getOrNull(raw.toIntOrNull() ?: -1).orEmpty()
                        } else raw
                    }
                    "row" -> {
                        val last = row.keys.maxOrNull() ?: -1
                        rows += (0..last).map { row[it].orEmpty() }
                    }
                }
            }
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "v") {
                value = parser.nextText()
            }
            parser.next()
        }
        return rows
    }

    private fun parser(xml: ByteArray): XmlPullParser = XmlPullParserFactory.newInstance()
        .newPullParser()
        .apply { setInput(ByteArrayInputStream(xml), "UTF-8") }

    private fun columnIndex(reference: String): Int {
        val letters = reference.takeWhile { it.isLetter() }
        return letters.fold(0) { total, char -> total * 26 + (char.uppercaseChar() - 'A' + 1) } - 1
    }
}
