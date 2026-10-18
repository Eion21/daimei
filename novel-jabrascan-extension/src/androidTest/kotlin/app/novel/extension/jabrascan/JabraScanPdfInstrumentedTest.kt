package app.novel.extension.jabrascan

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The end-to-end proof, on a device: an accented chapter PDF that is already **in memory** — read
 * straight from the test apk's assets into a `ByteArray`, never written to a file — becomes the
 * paragraphs the reader shows, accents and all.
 *
 * This is the half a JVM test cannot reach. [JabraScanPdfLayout] is pure and is covered by
 * [JabraScanPdfLayoutTest]; what needs a device is PdfBox: it reads the glyph tables the AAR ships under
 * `assets/` through a `Context`, and it opens the in-memory document with `PDDocument.load(byte[])`
 * (the overload that matters — production gets bytes from `fetchBytes`, never a `File`).
 *
 * The PDF is the repo's own synthetic `synthetic_spanish_novel.pdf` (self-authored and committed). It is
 * this module's own copy under `androidTest/assets`, referenced through that assets source dir rather
 * than copied in from anywhere else.
 */
@RunWith(AndroidJUnit4::class)
class JabraScanPdfInstrumentedTest {

    @Before
    fun initPdfBox() {
        // PdfBox refuses to extract anything until it has been initialised with a real application
        // context: it reads its bundled AFM/glyphlist resources through `getApplicationContext()`. The
        // extension apk is the target and carries those assets (PdfBox is an `implementation`
        // dependency), so its own context is the one to hand over.
        PDFBoxResourceLoader.init(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun aSyntheticPdfInMemoryBecomesAccentedParagraphs() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = instrumentation.context.assets.open(PDF_ASSET).use { it.readBytes() }
        assertTrue("the synthetic PDF must not be empty", bytes.isNotEmpty())

        // The production entry point end to end: in-memory bytes -> PdfBox (`PDDocument.load(byte[])`)
        // -> the pure reconstruction -> the reader's paragraphs, off the calling thread by the
        // extension's own `withContext(Dispatchers.IO)`.
        val paragraphs = JabraScanPdf.extract(bytes)
        val text = paragraphs.joinToString(" ") { it.text }
            .replace(Regex("\\s+"), " ")

        // An accented sentence (señora, jardín, mañana, pájaros) that also runs across a page boundary:
        // it only appears whole if the page change did not split the paragraph.
        assertTrue(
            "the extracted paragraphs must carry the accented sentence; got ${text.length} chars: " +
                text.take(200),
            text.contains(EXPECTED_SENTENCE),
        )

        // A word split by a hard hyphen at a line end is sewn back together without the hyphen.
        assertTrue(
            "the split word must be rejoined as «palabra»; got: ${text.take(400)}",
            text.contains("«palabra»"),
        )

        // A genuine line-final dash before a capital stays a dash instead of being sewn away.
        assertTrue(
            "the line-final dash before a capital must survive; got: ${text.take(400)}",
            text.contains("lejos- Nadie"),
        )

        // The running header and the bare page numbers are furniture, not prose.
        assertTrue(
            "the running header must be dropped; got: ${text.take(200)}",
            !text.contains(RUNNING_HEADER),
        )
    }

    /**
     * Characterisation, not a wish: `synthetic_spanish_novel.pdf` is authored as
     * **seven** prose paragraphs, but the extractor returns **four** blocks, and four is the correct
     * behaviour *today* — it was four before the paragraph-threshold change landed, and it is four after.
     *
     * Neither the threshold nor a bug explains the gap. The fixture separates its paragraphs with either
     * an ordinary ~14.9 pt leading or a real ~44.7 pt paragraph gap, both comfortably on their own side of
     * the old and the new threshold. The cause is the rule [JabraScanPdfLayout] applies on purpose: **a
     * page change is not a paragraph break**, so a paragraph that runs off the bottom of a page continues
     * on the next one. A new paragraph whose first line sits at the top of a page therefore loses the
     * extra paragraph space the generator draws above a paragraph's first line and looks exactly like a
     * continuation, so it merges into the block before it.
     *
     * The generator's paragraphs that begin at a page top are what the assertions below pin down — not
     * only the total, but which starts merged: `Un día el viento …` merges with `¡Qué extraño …` across
     * the page 2 -> 3 boundary, and `La señora cerró la libreta …`, `Hay historias …` and `Así termina …`
     * come out as one block across the page 3 -> 4 and 4 -> 5 boundaries. Seven paragraphs in, four
     * blocks out.
     */
    @Test
    fun syntheticFixtureYieldsFourBlocksPageTopStartsMerge() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = instrumentation.context.assets.open(PDF_ASSET).use { it.readBytes() }

        // The same end-to-end extraction the class's other test drives, so this characterises the
        // production path and not a re-implementation of it.
        val blocks = JabraScanPdf.extract(bytes).map { it.text.replace(Regex("\\s+"), " ") }

        assertEquals(
            "the fixture's page-top paragraph starts merge, so the extractor returns fewer blocks " +
                "than the generator's paragraphs; got ${blocks.size}: ${blocks.joinToString(" | ")}",
            4,
            blocks.size,
        )

        // Page 2 -> 3: the generator opens a paragraph at the top of page 3, so it merges with the
        // paragraph that ended page 2.
        val pageTwoToThree = blockContaining(blocks, "Un día el viento arrancó la última hoja")
        assertTrue(
            "«Un día el viento …» (end of page 2) must share a block with «¡Qué extraño …» " +
                "(top of page 3); got: $pageTwoToThree",
            pageTwoToThree.contains("¡Qué extraño resultaba todo!"),
        )

        // Pages 3 -> 4 -> 5: three generator paragraphs that each open at a page top collapse into one
        // block.
        val pagesThreeToFive = blockContaining(blocks, "La señora cerró la libreta")
        assertTrue(
            "the page-3 paragraph must also carry the page-4 paragraph; got: $pagesThreeToFive",
            pagesThreeToFive.contains("Hay historias que solo se entienden al final"),
        )
        assertTrue(
            "the same block must also carry the page-5 paragraph; got: $pagesThreeToFive",
            pagesThreeToFive.contains("Así termina este cuaderno de pruebas"),
        )
    }

    /**
     * Pins the fixture's font shape, the half of its guarantees that no raw-byte check can reach:
     * `synthetic_spanish_novel.pdf` carries exactly one text font, and that font is an embedded
     * `PDType0Font` with Identity-H encoding and a subset-prefixed base-font name.
     *
     * This cannot be read off the raw bytes. `qpdf --object-streams=generate` moves the font
     * dictionaries *inside* the compressed object stream, so the literal `Identity-H` appears zero times
     * in the file: only a parser that decompresses the object stream can observe it, and it must be read
     * from the parsed dictionary. That is why the assertion lives here, on a device with PdfBox, and not
     * in the JVM `SyntheticFixtureIntegrityTest`, which deliberately does not assert Identity-H against
     * the raw bytes. `PDDocument.load` reading the fixture at all is itself the proof that the object
     * stream is consumable.
     */
    @Test
    fun theFixturesFontIsAnEmbeddedIdentityHCidSubset() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = instrumentation.context.assets.open(PDF_ASSET).use { it.readBytes() }
        PDDocument.load(bytes).use { document ->
            val fonts = fontsOf(document)
            assertEquals("the fixture must use exactly one text font", 1, fonts.size)
            val font = fonts.values.single()
            assertTrue(
                "the font must be a Type0 (CID) font, not ${font.javaClass.name}",
                font is PDType0Font,
            )

            val type0 = font as PDType0Font
            assertEquals(
                "the font must carry the Identity-H encoding in its parsed dictionary",
                "Identity-H",
                type0.cosObject.getNameAsString(COSName.ENCODING),
            )
            assertTrue("the font must be embedded", type0.isEmbedded)

            val baseFont = type0.baseFont
            assertTrue(
                "the base font '$baseFont' must carry the six-uppercase-letter subset prefix (ABCDEF+Name form)",
                SUBSET_PREFIX.matches(baseFont),
            )
        }
    }

    /** The block whose text contains [phrase], or a failure naming what was actually extracted. */
    private fun blockContaining(blocks: List<String>, phrase: String): String =
        blocks.firstOrNull { it.contains(phrase) }
            ?: error("no block contains «$phrase»; got: ${blocks.joinToString(" | ")}")

    /** Every font reachable from every page's resources, keyed by resource name. */
    private fun fontsOf(document: PDDocument): Map<String, PDFont> {
        val fonts = LinkedHashMap<String, PDFont>()
        for (page in document.pages) {
            val resources = page.resources ?: continue
            for (name in resources.fontNames) {
                val font = resources.getFont(name) ?: continue
                fonts[name.name] = font
            }
        }
        return fonts
    }

    private companion object {
        /** This extension's own committed copy, under `androidTest/assets`. */
        const val PDF_ASSET = "synthetic_spanish_novel.pdf"

        /** The `ABCDEF+Name` subset form: six uppercase letters, a plus sign, then the face name. */
        val SUBSET_PREFIX = Regex("^[A-Z]{6}\\+.+")

        // A sentence of the synthetic text that spans the page-0/page-1 boundary. Compared after
        // whitespace is collapsed so line wrapping cannot break the match.
        const val EXPECTED_SENTENCE =
            "La señora del jardín anotaba cada mañana los nombres de los pájaros"

        /** The synthetic running header, which [JabraScanPdfLayout] must classify as furniture. */
        const val RUNNING_HEADER = "Cuaderno de Pruebas"
    }
}
