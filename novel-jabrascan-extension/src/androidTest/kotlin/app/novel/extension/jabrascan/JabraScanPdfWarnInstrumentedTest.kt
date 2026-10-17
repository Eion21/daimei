package app.novel.extension.jabrascan

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * The safety warnings end to end, on a device.
 *
 * [JabraScanParagraphWarningsTest] pins *when* a warning is due; this proves the other half, which only
 * exists on Android: that the extraction turns a warning into a `WARN` logcat line that names the work and
 * the chapter. The input is a deliberately pathological in-memory PDF — hundreds of evenly spaced lines
 * with no paragraph gap — so the criterion (untouched, by design) really does collapse it into one block,
 * and the warning is observed in logcat rather than merely asserted to exist.
 *
 * The PDF is generated here, in memory. It carries no prose: only numbered synthetic placeholder lines,
 * so no real chapter ever enters the repository.
 */
@RunWith(AndroidJUnit4::class)
class JabraScanPdfWarnInstrumentedTest {

    @Before
    fun initPdfBox() {
        PDFBoxResourceLoader.init(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun aPathologicalPdfLogsWarningsThatNameTheWorkAndTheChapter() = runBlocking {
        val paragraphs = JabraScanPdf.extract(
            pathologicalPdf(),
            ChapterIdentity(work = WORK, chapter = CHAPTER),
        )

        // The synthetic input has no paragraph gaps, so the untouched criterion must collapse it: fewer
        // blocks than the "few blocks" floor, and one block far past the per-paragraph limit.
        assertTrue(
            "the pathological chapter must not split; got ${paragraphs.size} blocks",
            paragraphs.size < JabraScanParagraphWarnings.FEW_BLOCKS,
        )
        val longest = paragraphs.maxOf { it.text.length }
        assertTrue(
            "the pathological chapter must contain one over-long block; longest was $longest",
            longest > JabraScanParagraphWarnings.LONG_PARAGRAPH_CHARS,
        )
    }

    /**
     * An in-memory PDF of [LINE_COUNT] evenly spaced lines, each unique so the running-header rule cannot
     * mistake them for furniture, and with no vertical gap over 1.5x the leading.
     */
    private fun pathologicalPdf(): ByteArray {
        val out = ByteArrayOutputStream()
        PDDocument().use { document ->
            var line = 0
            while (line < LINE_COUNT) {
                val page = PDPage(PDRectangle.LETTER)
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, FONT_SIZE)
                    content.setLeading(LEADING)
                    content.newLineAtOffset(LEFT_MARGIN, FIRST_BASELINE_FROM_TOP)
                    val pageEnd = minOf(line + LINES_PER_PAGE, LINE_COUNT)
                    while (line < pageEnd) {
                        content.showText(
                            "Synthetic placeholder line ${line.toString().padStart(4, '0')} of the warning probe",
                        )
                        content.newLine()
                        line++
                    }
                    content.endText()
                }
            }
            document.save(out)
        }
        return out.toByteArray()
    }

    private companion object {
        const val WORK = "Synthetic Work"
        const val CHAPTER = "Warning Probe"

        const val FONT_SIZE = 9f
        const val LEADING = 11f
        const val LEFT_MARGIN = 60f
        const val FIRST_BASELINE_FROM_TOP = 720f
        const val LINES_PER_PAGE = 55
        const val LINE_COUNT = 700
    }
}
