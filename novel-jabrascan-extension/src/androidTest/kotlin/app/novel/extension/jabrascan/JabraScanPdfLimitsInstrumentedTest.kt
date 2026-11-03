package app.novel.extension.jabrascan

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class JabraScanPdfLimitsInstrumentedTest {
    @Before
    fun initPdfBox() {
        PDFBoxResourceLoader.init(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun s32SmallPdfPreservesCompleteText() = runBlocking {
        val text = JabraScanPdf.extract(pdf(listOf(700f, 688f, 676f)))
            .joinToString(" ") { it.text }
        assertEquals(PROSE.take(3).joinToString(" "), text)
    }

    @Test
    fun s32OversizedPdfFailsWithExplicitSizeLimit() {
        val bytes = pdf(listOf(700f), paddingBytes = INPUT_LIMIT + 1024)
        assertTrue("fixture must exceed the input limit", bytes.size > INPUT_LIMIT)
        // Verify the padding is part of a readable PDF, not bytes appended after EOF.
        PDDocument.load(bytes).use { assertEquals(1, it.numberOfPages) }
        try {
            JabraScanPdf.extractPdf(bytes)
            fail("a valid PDF above the input limit must be rejected")
        } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            assertTrue("diagnostic must name the PDF size limit: $message", message.contains("PDF", true))
            assertTrue(
                "diagnostic must name 32 MiB or 33554432 bytes: $message",
                message.contains("33554432") ||
                    Regex("\\b32\\s*(MiB|MB)\\b", RegexOption.IGNORE_CASE).containsMatchIn(message),
            )
            assertTrue("diagnostic must identify a limit: $message", message.contains("limit", true))
        }
    }

    @Test
    fun s32CorruptPdfFailsWithControlledDiagnostic() {
        val bytes = "%PDF-1.7\nthis is not a valid document".toByteArray(Charsets.US_ASCII)
        try {
            JabraScanPdf.extractPdf(bytes)
            fail("corrupt PDF must not return content")
        } catch (e: IllegalStateException) {
            assertTrue("diagnostic must identify the PDF: ${e.message}", e.message.orEmpty().contains("PDF", true))
        }
    }

    @Test
    fun s32ShortValidPdfDoesNotReportUnreliableContent() {
        val reconstruction = JabraScanPdf.extractPdf(pdf(listOf(700f, 690f, 679.8f, 669.7f, 659.5f, 649.4f, 639.2f)))
        assertEquals(PROSE.joinToString(" "), reconstruction.paragraphs.joinToString(" "))
        assertFalse("short valid prose must not be flagged", reconstruction.statisticUnreliable)
        val warnings = mutableListOf<String>()
        JabraScanPdf.warnOnSuspiciousLayout(
            ChapterIdentity(work = "Invented work", chapter = "Short chapter"),
            reconstruction.paragraphs,
            reconstruction.leading,
            reconstruction.statisticUnreliable,
            { warnings.add(it) },
        )
        assertTrue(
            "short valid prose must not emit an unreliable warning: $warnings",
            warnings.none {
                it.contains("unreliable", true)
            },
        )
    }

    @Test
    fun s32PageBudgetRejectsWithoutPartialText() {
        val bytes = pdf(listOf(700f), pageCount = 2)
        val error = expectLimit { JabraScanPdf.extractPdf(bytes, JabraScanPdfLimits(pages = 1)) }
        assertTrue(error.message.orEmpty().contains("pages"))
    }

    @Test
    fun s32GlyphBudgetRejectsWithoutPartialText() {
        val bytes = pdf(listOf(700f, 688f))
        val error = expectLimit { JabraScanPdf.extractPdf(bytes, JabraScanPdfLimits(textUnits = 8)) }
        assertTrue(error.message.orEmpty().contains("text/glyphs"))
    }

    @Test
    fun s32ScratchBudgetClosesFilesOnSuccessAndFailure() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            .resolve("s32-scratch-${UUID.randomUUID()}")
        assertTrue(dir.mkdir())
        try {
            val bytes = pdf(listOf(700f), paddingBytes = 128 * 1024)
            val result = JabraScanPdf.extractPdf(
                bytes,
                JabraScanPdfLimits(scratchMainBytes = 4096, scratchTotalBytes = 2 * 1024 * 1024),
                dir,
            )
            assertEquals(PROSE.first(), result.paragraphs.joinToString(" "))
            assertTrue("scratch files remain after success", dir.listFiles()!!.isEmpty())
            val error = expectLimit {
                JabraScanPdf.extractPdf(
                    bytes,
                    JabraScanPdfLimits(scratchMainBytes = 4096, scratchTotalBytes = 32768),
                    dir,
                )
            }
            assertTrue("scratch error must preserve its I/O cause", error.cause is IOException)
            assertTrue("scratch files remain after failure", dir.listFiles()!!.isEmpty())
        } finally {
            assertTrue("owned scratch directory must be empty and removable", dir.delete())
        }
    }

    private fun expectLimit(block: () -> Unit): IllegalStateException {
        try {
            block()
            fail("PDF limit must reject without returning partial content")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("PDF"))
            assertTrue(e.message.orEmpty().contains("limit"))
            return e
        }
        throw AssertionError("unreachable")
    }

    /** A real PDF with optional uncompressed, catalog-referenced stream and a proper xref. */
    private fun pdf(baselines: List<Float>, paddingBytes: Int = 0, pageCount: Int = 1): ByteArray {
        val out = ByteArrayOutputStream(if (paddingBytes > 0) paddingBytes + 65536 else 4096)
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            repeat(pageCount - 1) { document.addPage(PDPage()) }
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 10f)
                content.newLineAtOffset(60f, baselines.first())
                baselines.forEachIndexed { index, baseline ->
                    if (index > 0) content.newLineAtOffset(0f, baseline - baselines[index - 1])
                    content.showText(PROSE[index])
                }
                content.endText()
            }
            if (paddingBytes > 0) {
                val padding = PDStream(document)
                padding.createOutputStream().use { stream ->
                    val chunk = ByteArray(8192) { 65 }
                    var remaining = paddingBytes
                    while (remaining > 0) {
                        val count = minOf(remaining, chunk.size)
                        stream.write(chunk, 0, count)
                        remaining -= count
                    }
                }
                document.documentCatalog.cosObject.setItem(COSName.getPDFName("S32Padding"), padding.cosObject)
            }
            document.save(out)
        }
        return out.toByteArray()
    }

    private companion object {
        const val INPUT_LIMIT = 33554432
        val PROSE = listOf(
            "The lantern glowed beside the gate.",
            "A quiet visitor crossed the courtyard.",
            "The keeper watched the clouds drift past.",
            "A small bell sounded in the tower.",
            "The visitor unfolded a paper map.",
            "They followed the path toward the garden.",
            "At sunrise the garden opened its doors.",
        )
    }
}
