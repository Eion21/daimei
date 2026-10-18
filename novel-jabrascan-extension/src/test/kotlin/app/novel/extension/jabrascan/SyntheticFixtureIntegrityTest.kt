package app.novel.extension.jabrascan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

/**
 * Pins the shared synthetic fixture at the byte level: its object-stream structure, and the fact that its
 * two committed copies are byte-identical.
 *
 * These are pure JVM checks — the file is only read, never parsed with PdfBox — so no device is involved.
 * The paths are relative to this module's directory (Gradle runs unit tests with the module as the working
 * directory): this module's own copy sits under `src/androidTest/assets`, and the other one is reached
 * with `../`.
 */
class SyntheticFixtureIntegrityTest {

    @Test
    fun `the fixture carries an object stream and a cross-reference stream`() {
        for (path in FIXTURE_PATHS) {
            val text = File(path).readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(
                OBJ_STM.containsMatchIn(text),
                "$path must carry a /Type /ObjStm object stream (qpdf --object-streams=generate)",
            )
            assertTrue(
                XREF_STREAM.containsMatchIn(text),
                "$path must carry a /Type /XRef cross-reference stream",
            )
        }

        // Deliberately NOT asserting Identity-H here. It is legitimately absent from the raw bytes: qpdf
        // --object-streams=generate puts the font dictionaries inside the compressed object stream, so the
        // literal `Identity-H` appears zero times in the file. Identifying the Identity-H/CID and
        // subset-font properties requires decompressing that stream, so they are asserted through PdfBox,
        // which does exactly that — see JabraScanPdfInstrumentedTest in this module. Do NOT add a
        // raw-byte assertion for Identity-H; it would fail on a correct fixture and would be "fixing" a
        // non-bug.
    }

    @Test
    fun `the two fixture copies are byte-identical`() {
        val hashes = FIXTURE_PATHS.associateWith { sha256(File(it)) }
        val listing = hashes.entries.joinToString("\n") { "  ${it.value}  ${it.key}" }
        assertEquals(
            1,
            hashes.values.toSet().size,
            "the two synthetic fixture copies diverged; regenerate them as one file:\n$listing",
        )
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private companion object {
        val OBJ_STM = Regex("/Type\\s*/ObjStm")
        val XREF_STREAM = Regex("/Type\\s*/XRef")

        val FIXTURE_PATHS = listOf(
            "src/androidTest/assets/synthetic_spanish_novel.pdf",
            "../app/src/androidTest/assets/synthetic_spanish_novel.pdf",
        )
    }
}
