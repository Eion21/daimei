package app.novel.extension.jabrascan

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The generator behind `synthetic_spanish_novel.pdf`, the repo's own stand-in for the third-party
 * jabrascan.net samples.
 *
 * It is **skipped by default**: the fixture it produces is already committed, so the test does nothing
 * unless it is explicitly asked to run. Pass the `generateFixture` instrumentation argument set to
 * `true` to actually write a file; the guard is the [assumeTrue] below, which turns everything else into
 * an assumption failure.
 *
 * A note for whoever reads the report: AGP's connected-test XML in this version does **not** show an
 * assumption failure as *skipped*. It writes a `<failure>` element for it — and a control experiment
 * with a plain `@Ignore`d test confirmed it writes one for that too, with `skipped="0"` either way. The
 * label is therefore misleading, not the behaviour: the instrumentation run exits 0, the build stays
 * green, and without the argument this test never writes a single byte.
 *
 * When it does run it writes the **raw** generator output to the device's external cache dir — never
 * the committed fixture, which a device test cannot reach and must not be wired to overwrite. That raw
 * file still has to be post-processed with qpdf before it becomes the fixture; the full out-of-band
 * recipe, with the exact command, the qpdf version and the committed fixture's size and SHA-256, lives
 * in `novel-jabrascan-extension/src/androidTest/README.md`.
 *
 * The text is authored here — nothing is copied from the samples — and the layout is placed by hand so
 * the file carries exactly the shapes [JabraScanPdfLayout] has to survive: body leading with a larger
 * paragraph gap, a running header on most pages, a bare page number, a word split by a hard hyphen at a
 * line end, a genuine line-final dash before a capital, and a paragraph that runs across a page
 * boundary.
 *
 * It is a device test only because the TrueType font it embeds is a file that lives on the emulator
 * (`/system/fonts`); writing the PDF itself needs no Android.
 */
@RunWith(AndroidJUnit4::class)
class SyntheticPdfGeneratorTest {

    @Test
    fun generateSyntheticSpanishNovel() {
        // Skipped unless asked for: the fixture is committed, so regenerating it is a deliberate,
        // out-of-band act, never a side effect of running the instrumentation suite.
        assumeTrue(
            "skipped: pass -e $ARG_GENERATE true to write the raw fixture",
            InstrumentationRegistry.getArguments().getString(ARG_GENERATE) == "true",
        )

        PDFBoxResourceLoader.init(InstrumentationRegistry.getInstrumentation().context)

        val fontFile = File(FONT_PATH)
        assertTrue("the emulator must expose $FONT_PATH", fontFile.isFile)

        // The external cache dir, so the raw output is both a scratch file and directly `adb pull`-able.
        // It is named `.raw.` because it is *not* the committed fixture: qpdf has not run over it yet.
        val outFile = File(
            InstrumentationRegistry.getInstrumentation().targetContext.externalCacheDir,
            RAW_OUTPUT_NAME,
        )

        PDDocument().use { document ->
            // embedSubset = true is what makes the font a *subset* and gives it Identity-H encoding.
            val font = PDType0Font.load(document, fontFile.inputStream(), true)
            // The samples read as version 1.6 in the file header with a catalog /Version of 1.7
            // (`pdfinfo` reports 1.7). Mirror both so the version dimension matches the samples.
            document.document.version = 1.6f
            document.version = 1.7f
            for (page in PAGES) writePage(document, font, page)
            document.save(outFile)
        }

        assertTrue("the PDF was not written", outFile.isFile && outFile.length() > 0)
        Log.i(TAG, "WROTE ${outFile.absolutePath} (${outFile.length()} bytes)")
    }

    private fun writePage(document: PDDocument, font: PDType0Font, page: Page) {
        val pdPage = PDPage(PDRectangle.LETTER)
        document.addPage(pdPage)
        PDPageContentStream(document, pdPage).use { content ->
            page.header?.let { drawLine(content, font, it, HEADER_SIZE, HEADER_TOP, centered = true) }
            var y = FIRST_BODY_TOP
            var firstOnPage = true
            for (line in page.lines) {
                // The extra space goes above a paragraph's first line, but never above the page's top
                // line: a paragraph that continues from the previous page keeps the plain leading.
                if (line.paragraphStart && !firstOnPage) y += PARAGRAPH_EXTRA
                drawLine(content, font, line.text, BODY_SIZE, y, centered = false)
                y += LEADING
                firstOnPage = false
            }
            page.number?.let { drawLine(content, font, it, BODY_SIZE, PAGE_NUMBER_TOP, centered = true) }
        }
    }

    /** [yFromTop] is the distance from the page's top edge to the line's baseline. */
    private fun drawLine(
        content: PDPageContentStream,
        font: PDType0Font,
        text: String,
        size: Float,
        yFromTop: Float,
        centered: Boolean,
    ) {
        val x = if (centered) {
            (PAGE_WIDTH - font.getStringWidth(text) / 1000f * size) / 2f
        } else {
            LEFT_MARGIN
        }
        content.beginText()
        content.setFont(font, size)
        content.newLineAtOffset(x, PAGE_HEIGHT - yFromTop)
        content.showText(text)
        content.endText()
    }

    private data class BodyLine(val text: String, val paragraphStart: Boolean = false)

    private data class Page(val header: String?, val lines: List<BodyLine>, val number: String?)

    private companion object {
        const val TAG = "SyntheticPdf"

        /** The instrumentation argument that opts the generator in. Off unless it is exactly `true`. */
        const val ARG_GENERATE = "generateFixture"

        /** A TrueType face with full Latin coverage, shipped read-only on the emulator. */
        const val FONT_PATH = "/system/fonts/NotoSerif-Regular.ttf"

        /** The raw generator output, before qpdf. Never the committed fixture's bytes. */
        const val RAW_OUTPUT_NAME = "synthetic_spanish_novel.raw.pdf"

        const val PAGE_WIDTH = 612f
        const val PAGE_HEIGHT = 792f
        const val LEFT_MARGIN = 85.104f
        const val HEADER_SIZE = 14f
        const val BODY_SIZE = 12f

        /** Body leading, close to the 14.9 pt the real samples use. */
        const val LEADING = 14.9f

        /** Extra space above a paragraph's first line, so the paragraph gap is ~3x the leading. */
        const val PARAGRAPH_EXTRA = 29.8f

        const val HEADER_TOP = 110f
        const val FIRST_BODY_TOP = 155f
        const val PAGE_NUMBER_TOP = 740f

        const val RUNNING_HEADER = "Cuaderno de Pruebas"

        private val PAGES = listOf(
            Page(
                header = RUNNING_HEADER,
                lines = listOf(
                    // This paragraph runs off the bottom of page 0 and continues on page 1.
                    BodyLine(
                        "La señora del jardín anotaba cada mañana los nombres de los pájaros que",
                        paragraphStart = true,
                    ),
                    BodyLine("cruzaban el cielo de la aldea. Guardaba una libreta azul, pequeña y"),
                    BodyLine("gastada, y en ella escribía también la hora exacta en que el sol"),
                    BodyLine("tocaba la copa del árbol más alto del huerto. No era una costumbre"),
                    BodyLine("común en aquel lugar, donde casi nadie se detenía a observar el vuelo"),
                    BodyLine("de un gorrión, pero ella lo hacía con la misma paciencia con que"),
                    BodyLine("regaba sus macetas cada amanecer. Ninguna página debía cortar una"),
                ),
                number = "1",
            ),
            Page(
                header = RUNNING_HEADER,
                lines = listOf(
                    BodyLine("palabra a la mitad, y cada párrafo debía respetar el espacio que"),
                    BodyLine("el tiempo le concedía."),
                    BodyLine("El cuaderno seguía una regla sencilla: si una palabra larga no", paragraphStart = true),
                    // "«pala-" + "bra»" is the hard hyphen a word is split at.
                    BodyLine("cabía en el renglón, se partía con un guion, como en «pala-"),
                    BodyLine("bra» o en «extraordi-» con su mitad, para que nada se perdiera."),
                    BodyLine("Un día el viento arrancó la última hoja y se la llevó lejos-", paragraphStart = true),
                    // Genuine line-final dash before a capital: it must stay a dash, not be sewn away.
                    BodyLine("Nadie supo explicarlo, pero el cuaderno siguió creciendo solo."),
                ),
                number = "2",
            ),
            Page(
                header = RUNNING_HEADER,
                lines = listOf(
                    BodyLine(
                        "¡Qué extraño resultaba todo! En el pueblo de la montaña, el único",
                        paragraphStart = true,
                    ),
                    BodyLine("cartel de la plaza anunciaba una feria, y bajo el cartel un niño"),
                    BodyLine("vendía globos de colores. Pasó un pingüino de trapo —un juguete—"),
                    BodyLine("y el niño sonrió. ¿Quién podía imaginar que aquel juguete llegaría"),
                    BodyLine("tan lejos?"),
                    BodyLine("La señora cerró la libreta y apagó la lámpara. Afuera, la niebla", paragraphStart = true),
                    BodyLine("borraba los contornos del jardín. Mañana volvería a empezar con"),
                    BodyLine("la misma calma de siempre."),
                ),
                number = "3",
            ),
            Page(
                header = RUNNING_HEADER,
                lines = listOf(
                    BodyLine("Hay historias que solo se entienden al final, cuando el silencio", paragraphStart = true),
                    BodyLine("ocupa el lugar de las voces y el papel se queda quieto bajo la"),
                    BodyLine("luz de la tarde. Entonces una palabra pequeña, casi invisible,"),
                    BodyLine("decide quedarse para siempre."),
                ),
                number = "4",
            ),
            Page(
                // No header on the last page, so the header is a *running* one on most pages only.
                header = null,
                lines = listOf(
                    BodyLine("Así termina este cuaderno de pruebas: una lista de pájaros, un", paragraphStart = true),
                    BodyLine("sueño de invierno y una regla que nadie rompió."),
                ),
                number = "5",
            ),
        )
    }
}
