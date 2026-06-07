package com.example.exporter

import android.content.Context
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Zero-dependency Markdown -> OOXML (.docx) converter that runs fully offline.
 *
 * This is the practical replacement for bundling Pandoc (Pandoc is a desktop
 * Haskell binary that cannot run inside an Android app). It produces a valid
 * Microsoft Word package and supports headings, inline formatting
 * (bold / italic / inline-code / strikethrough / links), ordered & unordered
 * lists, blockquotes, fenced code blocks, GFM pipe tables and horizontal rules.
 */
object DocxExporter {

    fun exportToDocx(context: Context, markdownText: String, outputStream: OutputStream): Boolean =
        exportToDocx(markdownText, outputStream)

    /** Core converter — no Android dependency, so it is unit-testable on the host JVM. */
    fun exportToDocx(markdownText: String, outputStream: OutputStream): Boolean {
        var zip: ZipOutputStream? = null
        return try {
            zip = ZipOutputStream(outputStream)
            writeZipEntry(zip, "[Content_Types].xml", CONTENT_TYPES)
            writeZipEntry(zip, "_rels/.rels", PACKAGE_RELS)
            writeZipEntry(zip, "word/_rels/document.xml.rels", DOCUMENT_RELS)
            writeZipEntry(zip, "word/styles.xml", STYLES)
            writeZipEntry(zip, "word/document.xml", buildDocumentXml(markdownText))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { zip?.close() } catch (ignored: Exception) {}
        }
    }

    // ---------------------------------------------------------------------
    // Document body construction
    // ---------------------------------------------------------------------

    private fun buildDocumentXml(markdown: String): String {
        val body = StringBuilder()
        val lines = markdown.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        var i = 0
        var orderedIndex = 1

        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()
            val trimmed = line.trim()

            // Fenced code block
            if (trimmed.startsWith("```")) {
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append("\n")
                    i++
                }
                i++ // skip closing fence
                appendCodeBlock(body, code.toString())
                continue
            }

            // GFM pipe table: header row + separator row of ---/:--: cells
            if (trimmed.startsWith("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
                val tableLines = ArrayList<String>()
                tableLines.add(line)
                i++ // separator
                val align = parseAlignments(lines[i])
                i++
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    tableLines.add(lines[i].trimEnd())
                    i++
                }
                appendTable(body, tableLines, align)
                continue
            }

            when {
                trimmed.isEmpty() -> { /* skip; spacing handled by paragraph after */ }

                trimmed == "---" || trimmed == "***" || trimmed == "___" ->
                    body.append(HORIZONTAL_RULE)

                trimmed.startsWith("###### ") -> appendHeading(body, trimmed.substring(7), 6)
                trimmed.startsWith("##### ") -> appendHeading(body, trimmed.substring(6), 5)
                trimmed.startsWith("#### ") -> appendHeading(body, trimmed.substring(5), 4)
                trimmed.startsWith("### ") -> appendHeading(body, trimmed.substring(4), 3)
                trimmed.startsWith("## ") -> appendHeading(body, trimmed.substring(3), 2)
                trimmed.startsWith("# ") -> appendHeading(body, trimmed.substring(2), 1)

                trimmed.startsWith("> ") || trimmed == ">" ->
                    appendQuote(body, if (trimmed.length > 1) trimmed.substring(2) else "")

                isUnorderedItem(trimmed) ->
                    appendListItem(body, trimmed.substring(2), bullet = "•")

                isOrderedItem(trimmed) -> {
                    val dot = trimmed.indexOf('.')
                    appendListItem(body, trimmed.substring(dot + 1).trim(), bullet = "$orderedIndex.")
                    orderedIndex++
                    i++
                    continue
                }

                else -> appendParagraph(body, line)
            }

            // Reset ordered list counter when the list ends
            if (!isOrderedItem(trimmed)) orderedIndex = 1
            i++
        }

        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>
$body
<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>
</w:body>
</w:document>"""
    }

    // ---------------------------------------------------------------------
    // Block builders
    // ---------------------------------------------------------------------

    private fun appendHeading(sb: StringBuilder, text: String, level: Int) {
        val size = when (level) { 1 -> 36; 2 -> 30; 3 -> 26; 4 -> 23; 5 -> 21; else -> 20 }
        sb.append("<w:p><w:pPr><w:spacing w:before=\"240\" w:after=\"120\"/>")
            .append("<w:keepNext/></w:pPr>")
            .append(runsFor(text, forceBold = true, sz = size, color = "1A237E"))
            .append("</w:p>")
    }

    private fun appendParagraph(sb: StringBuilder, text: String) {
        sb.append("<w:p><w:pPr><w:spacing w:after=\"160\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr>")
            .append(runsFor(text))
            .append("</w:p>")
    }

    private fun appendQuote(sb: StringBuilder, text: String) {
        sb.append("<w:p><w:pPr><w:spacing w:before=\"120\" w:after=\"120\"/>")
            .append("<w:ind w:left=\"480\"/>")
            .append("<w:pBdr><w:left w:val=\"single\" w:sz=\"18\" w:space=\"8\" w:color=\"B0BEC5\"/></w:pBdr>")
            .append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F5F7F9\"/></w:pPr>")
            .append(runsFor(text, italic = true, color = "546E7A"))
            .append("</w:p>")
    }

    private fun appendListItem(sb: StringBuilder, text: String, bullet: String) {
        sb.append("<w:p><w:pPr><w:spacing w:after=\"80\"/><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr>")
            .append(run("$bullet ", sz = 22))
            .append(runsFor(text, wrapParagraph = false))
            .append("</w:p>")
    }

    private fun appendCodeBlock(sb: StringBuilder, code: String) {
        val codeLines = code.trimEnd('\n').split("\n")
        for ((idx, line) in codeLines.withIndex()) {
            val before = if (idx == 0) 120 else 0
            val after = if (idx == codeLines.size - 1) 120 else 0
            sb.append("<w:p><w:pPr><w:spacing w:before=\"$before\" w:after=\"$after\" w:line=\"240\" w:lineRule=\"auto\"/>")
                .append("<w:ind w:left=\"360\" w:right=\"360\"/>")
                .append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F2F3F5\"/></w:pPr>")
                .append("<w:r><w:rPr><w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/>")
                .append("<w:sz w:val=\"19\"/><w:color w:val=\"263238\"/></w:rPr>")
                .append("<w:t xml:space=\"preserve\">").append(escapeXml(line)).append("</w:t></w:r></w:p>")
        }
    }

    private fun appendTable(sb: StringBuilder, rows: List<String>, align: List<String>) {
        sb.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"0\" w:type=\"auto\"/>")
            .append("<w:tblBorders>")
            .append("<w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"CFD8DC\"/>")
            .append("<w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"CFD8DC\"/>")
            .append("<w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"CFD8DC\"/>")
            .append("<w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"CFD8DC\"/>")
            .append("<w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"CFD8DC\"/>")
            .append("<w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"CFD8DC\"/>")
            .append("</w:tblBorders></w:tblPr>")

        for ((rowIdx, rowLine) in rows.withIndex()) {
            val cells = splitTableRow(rowLine)
            val isHeader = rowIdx == 0
            sb.append("<w:tr>")
            for ((colIdx, cell) in cells.withIndex()) {
                val a = align.getOrElse(colIdx) { "left" }
                val jc = when (a) { "center" -> "center"; "right" -> "right"; else -> "left" }
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/>")
                if (isHeader) sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"ECEFF1\"/>")
                sb.append("</w:tcPr>")
                sb.append("<w:p><w:pPr><w:jc w:val=\"$jc\"/></w:pPr>")
                    .append(runsFor(cell.trim(), forceBold = isHeader, wrapParagraph = false))
                    .append("</w:p></w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        // Spacer paragraph after table so following content isn't glued to it
        sb.append("<w:p><w:pPr><w:spacing w:after=\"120\"/></w:pPr></w:p>")
    }

    // ---------------------------------------------------------------------
    // Inline formatting -> runs
    // ---------------------------------------------------------------------

    private data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val strike: Boolean = false,
        val link: Boolean = false
    )

    /** Wrap inline runs; [wrapParagraph] is kept for symmetry but runs never include <w:p>. */
    private fun runsFor(
        text: String,
        forceBold: Boolean = false,
        italic: Boolean = false,
        color: String? = null,
        sz: Int = 22,
        wrapParagraph: Boolean = true
    ): String {
        val spans = parseInline(text)
        val out = StringBuilder()
        for (s in spans) {
            out.append(
                run(
                    s.text,
                    bold = s.bold || forceBold,
                    italic = s.italic || italic,
                    code = s.code,
                    strike = s.strike,
                    link = s.link,
                    sz = sz,
                    color = if (s.code) "C7254E" else if (s.link) "1565C0" else color
                )
            )
        }
        return out.toString()
    }

    private fun run(
        text: String,
        bold: Boolean = false,
        italic: Boolean = false,
        code: Boolean = false,
        strike: Boolean = false,
        link: Boolean = false,
        sz: Int = 22,
        color: String? = null
    ): String {
        val rpr = StringBuilder("<w:rPr>")
        if (code) rpr.append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/>")
        if (bold) rpr.append("<w:b/>")
        if (italic) rpr.append("<w:i/>")
        if (strike) rpr.append("<w:strike/>")
        if (link) rpr.append("<w:u w:val=\"single\"/>")
        rpr.append("<w:sz w:val=\"$sz\"/>")
        if (color != null) rpr.append("<w:color w:val=\"$color\"/>")
        rpr.append("</w:rPr>")
        return "<w:r>$rpr<w:t xml:space=\"preserve\">${escapeXml(text)}</w:t></w:r>"
    }

    // Single-pass inline parser for bold, italic, inline-code, strikethrough
    // and links. Code spans take precedence and are kept literal.
    private fun parseInline(input: String): List<Span> {
        val spans = ArrayList<Span>()
        var i = 0
        val plain = StringBuilder()

        fun flush() {
            if (plain.isNotEmpty()) { spans.add(Span(plain.toString())); plain.setLength(0) }
        }

        while (i < input.length) {
            val c = input[i]
            when {
                c == '`' -> {
                    val end = input.indexOf('`', i + 1)
                    if (end > i) { flush(); spans.add(Span(input.substring(i + 1, end), code = true)); i = end + 1 }
                    else { plain.append(c); i++ }
                }
                c == '*' && i + 1 < input.length && input[i + 1] == '*' -> {
                    val end = input.indexOf("**", i + 2)
                    if (end > i) { flush(); spans.add(Span(input.substring(i + 2, end), bold = true)); i = end + 2 }
                    else { plain.append(c); i++ }
                }
                c == '~' && i + 1 < input.length && input[i + 1] == '~' -> {
                    val end = input.indexOf("~~", i + 2)
                    if (end > i) { flush(); spans.add(Span(input.substring(i + 2, end), strike = true)); i = end + 2 }
                    else { plain.append(c); i++ }
                }
                (c == '*' || c == '_') -> {
                    val end = input.indexOf(c, i + 1)
                    if (end > i && end > i + 1) { flush(); spans.add(Span(input.substring(i + 1, end), italic = true)); i = end + 1 }
                    else { plain.append(c); i++ }
                }
                c == '[' -> {
                    val close = input.indexOf(']', i + 1)
                    if (close > i && close + 1 < input.length && input[close + 1] == '(') {
                        val paren = input.indexOf(')', close + 2)
                        if (paren > close) {
                            flush()
                            spans.add(Span(input.substring(i + 1, close), link = true))
                            i = paren + 1
                        } else { plain.append(c); i++ }
                    } else { plain.append(c); i++ }
                }
                else -> { plain.append(c); i++ }
            }
        }
        flush()
        if (spans.isEmpty()) spans.add(Span(""))
        return spans
    }

    // ---------------------------------------------------------------------
    // Table helpers
    // ---------------------------------------------------------------------

    private fun isTableSeparator(line: String): Boolean {
        val t = line.trim()
        if (!t.contains("-")) return false
        if (!t.startsWith("|") && !t.contains("|")) return false
        return t.replace("|", "").trim().matches(Regex("^[:\\- ]+$")) &&
                t.contains("-")
    }

    private fun parseAlignments(sep: String): List<String> =
        splitTableRow(sep).map {
            val c = it.trim()
            when {
                c.startsWith(":") && c.endsWith(":") -> "center"
                c.endsWith(":") -> "right"
                else -> "left"
            }
        }

    private fun splitTableRow(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|")) t = t.substring(0, t.length - 1)
        return t.split("|")
    }

    // ---------------------------------------------------------------------
    // Detectors
    // ---------------------------------------------------------------------

    private fun isUnorderedItem(t: String): Boolean =
        (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ "))

    private fun isOrderedItem(t: String): Boolean =
        Regex("^\\d+\\.\\s+.*").matches(t)

    // ---------------------------------------------------------------------
    // Zip / XML utilities
    // ---------------------------------------------------------------------

    private fun escapeXml(input: String): String =
        input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun writeZipEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private val HORIZONTAL_RULE =
        "<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"B0BEC5\"/></w:pBdr></w:pPr></w:p>"

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>"""

    private const val PACKAGE_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private const val DOCUMENT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    /** Minimal styles part: default font + a TableGrid style so tables render with borders. */
    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/><w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/><w:tblPr><w:tblBorders><w:top w:val="single" w:sz="4" w:space="0" w:color="CFD8DC"/><w:left w:val="single" w:sz="4" w:space="0" w:color="CFD8DC"/><w:bottom w:val="single" w:sz="4" w:space="0" w:color="CFD8DC"/><w:right w:val="single" w:sz="4" w:space="0" w:color="CFD8DC"/><w:insideH w:val="single" w:sz="4" w:space="0" w:color="CFD8DC"/><w:insideV w:val="single" w:sz="4" w:space="0" w:color="CFD8DC"/></w:tblBorders></w:tblPr></w:style>
</w:styles>"""
}
