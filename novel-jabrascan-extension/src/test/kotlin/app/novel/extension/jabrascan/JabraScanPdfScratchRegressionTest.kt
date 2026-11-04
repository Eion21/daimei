package app.novel.extension.jabrascan

import android.content.Context
import app.novel.source.api.Chapter
import app.novel.source.api.ContentBlock
import app.novel.source.api.NovelNetwork
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Path

/** Exercises real PDF loading, not PDFTextStripper or Android glyph assets. */
class JabraScanPdfScratchRegressionTest {
    @TempDir
    lateinit var temp: Path

    // One byte of memory forces this >4 KiB document into PDFBox's disk scratch.
    private val limits = JabraScanPdfLimits(scratchMainBytes = 1, scratchTotalBytes = 1024 * 1024)
    private val pdf = blankPdf()

    @Test
    fun `missing scratch directory preserves exact PDFBox cause and valid explicit directory still loads`() {
        val missing = temp.resolve("missing").toFile()
        val error = assertThrows(IllegalStateException::class.java) {
            JabraScanPdf.withLoadedPdf(pdf, limits, missing) { it.numberOfPages }
        }
        assertEquals("PDF could not be read (corrupt document or scratch limit/I/O failure)", error.message)
        assertEquals(IOException::class.java, error.cause?.javaClass)
        assertEquals("Scratch file directory does not exist: $missing", error.cause?.message)
        assertFalse(missing.exists())
        assertEquals(1, JabraScanPdf.withLoadedPdf(pdf, limits, temp.toFile()) { it.numberOfPages })
        assertEquals(1, JabraScanPdf.withLoadedPdf(pdf, limits, null) { it.numberOfPages })
    }

    @Test
    fun `bound package context must not select its missing cache for PDF scratch`() = runBlocking {
        val missing = temp.resolve("extension-cache").toFile()
        val context = mockk<Context>()
        every { context.cacheDir } returns missing
        mockkStatic(PDFBoxResourceLoader::class)
        mockkObject(JabraScanPdf)
        try {
            every { PDFBoxResourceLoader.init(any()) } just runs
            // Only text reading is substituted: the real loader, scratch, page guard and close still run.
            coEvery { JabraScanPdf.extract(any(), any(), any(), any()) } coAnswers {
                val bytes = firstArg<ByteArray>()
                val scratch = arg<File?>(3)
                val pages = JabraScanPdf.withLoadedPdf(bytes, limits, scratch) { it.numberOfPages }
                listOf(ContentBlock.Paragraph("pages=$pages"))
            }
            val network = object : NovelNetwork {
                override suspend fun fetch(url: String, headers: Map<String, String>): String =
                    """{"Work":[{"NombreArchivo":"chapter.pdf","numCapitulo":"1"}]}"""

                override suspend fun fetchBytes(url: String, headers: Map<String, String>): ByteArray = pdf
            }
            val source = JabraScanSource().apply {
                bindNetwork(network)
                bindContext(context)
            }
            val chapter = Chapter("jabrascan", "Work", "Work/chapter.pdf", "One", 1f, "chapter.pdf")

            val content = source.getChapterContent(chapter)

            assertEquals(listOf(ContentBlock.Paragraph("pages=1")), content.blocks)
            assertFalse(missing.exists())
        } finally {
            unmockkObject(JabraScanPdf)
            unmockkStatic(PDFBoxResourceLoader::class)
        }
    }

    /** Self-authored blank page with byte-accurate xref; padding makes scratch spill deterministic. */
    private fun blankPdf(): ByteArray {
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        fun obj(body: String) {
            offsets += out.length
            out.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] >>")
        out.append('%').append("x".repeat(10000)).append('\n')
        val xref = out.length
        out.append("xref\n0 4\n0000000000 65535 f \n")
        offsets.forEach { out.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        out.append("trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().toByteArray(Charsets.US_ASCII)
    }
}
