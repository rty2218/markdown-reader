package com.example.exporter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Validates that the Markdown -> DOCX converter emits a structurally valid
 * OpenXML package whose every part is well-formed XML. The original exporter
 * shipped a malformed `<w:it"/>` tag that corrupted any document containing a
 * blockquote; this test guards against that whole class of bug.
 */
class DocxExporterTest {

    private val sample = """
        # Heading One

        Some **bold**, *italic*, `inline code`, ~~strike~~ and a [link](https://example.com/path).

        ## Heading Two

        - bullet one
        - bullet two

        1. first
        2. second

        > a block quote that previously corrupted the file

        ```kotlin
        fun main() { println("hello") }
        ```

        | Left | Right |
        | :--- | ----: |
        | a | b |

        ---
    """.trimIndent()

    private fun generate(md: String): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        val ok = DocxExporter.exportToDocx(md, out)
        assertTrue("exporter returned false", ok)
        val bytes = out.toByteArray()
        assertTrue("docx unexpectedly small: ${bytes.size}", bytes.size > 500)

        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                entries[e.name] = zis.readBytes()
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        return entries
    }

    @Test
    fun producesWellFormedOoxmlPackage() {
        val entries = generate(sample)

        // Required OOXML parts must exist.
        for (part in listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml")) {
            assertTrue("missing required part: $part", entries.containsKey(part))
        }

        // Every XML/rels part must parse — this is what catches malformed tags.
        val dbf = DocumentBuilderFactory.newInstance()
        for ((name, data) in entries) {
            if (name.endsWith(".xml") || name.endsWith(".rels")) {
                dbf.newDocumentBuilder().parse(ByteArrayInputStream(data))
            }
        }

        val doc = String(entries["word/document.xml"]!!, Charsets.UTF_8)
        assertTrue("expected a real table", doc.contains("<w:tbl"))
        assertTrue("expected bold run", doc.contains("<w:b/>"))
        assertTrue("expected italic run", doc.contains("<w:i/>"))
        assertTrue("expected strikethrough run", doc.contains("<w:strike/>"))
        assertTrue("expected monospace code font", doc.contains("Consolas"))
        // Regression guard for the original corruption bug.
        assertFalse("malformed italic tag present", doc.contains("<w:it\""))
    }

    @Test
    fun handlesEmptyAndPlainInput() {
        // Should not throw and should still produce a valid package.
        val entries = generate("")
        assertTrue(entries.containsKey("word/document.xml"))
        val dbf = DocumentBuilderFactory.newInstance()
        dbf.newDocumentBuilder().parse(ByteArrayInputStream(entries["word/document.xml"]!!))
    }

    @Test
    fun escapesXmlSpecialCharacters() {
        val entries = generate("Text with <angle> & \"quote\" and 'apos' characters.")
        val doc = String(entries["word/document.xml"]!!, Charsets.UTF_8)
        // Raw special chars must be escaped, not break the XML.
        assertFalse(doc.contains("<angle>"))
        assertTrue(doc.contains("&lt;angle&gt;") || doc.contains("&amp;"))
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(entries["word/document.xml"]!!))
    }
}
