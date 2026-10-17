package app.novel.spike.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Pins the properties of the committed synthetic fixture `synthetic_spanish_novel.pdf`, on a device.
 *
 * The fixture's guarantees split in two, and this class covers the half that cannot be read off the raw
 * bytes:
 *
 * - **Byte-checkable** (JVM, no device, no PdfBox): `/Type /ObjStm` and `/Type /XRef` are present. That
 *   is pinned by `SyntheticFixtureIntegrityTest` in `:novel-jabrascan-extension`.
 * - **PdfBox-only** (this class): the fixture's font is a `PDType0Font` with Identity-H encoding and a
 *   subset-prefixed base-font name, and the text extracts with accents intact.
 *
 * The font facts cannot be read from the raw bytes: `qpdf --object-streams=generate` moves the font
 * dictionaries *inside* the compressed object stream, so the literal `Identity-H` appears zero times in
 * the file. Only a real parser that decompresses the object stream can observe it. `PDDocument.load`
 * reading the fixture and returning its text is itself the proof that the object stream is consumable.
 */
@RunWith(AndroidJUnit4::class)
class SyntheticSpanishNovelFixtureTest {

    @Before
    fun initPdfBox() {
        // PdfBox reads its bundled glyph tables through `getApplicationContext().getAssets()`; the test apk
        // carries them (the module depends on pdfbox-android), so its context is the one to hand over.
        PDFBoxResourceLoader.init(InstrumentationRegistry.getInstrumentation().context)
    }

    @Test
    fun theFixturesFontIsAnEmbeddedIdentityHCidSubset() {
        PDDocument.load(fixtureFile()).use { document ->
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

    @Test
    fun pdfBoxExtractsTheAccentedSentenceFromTheCommittedFixture() {
        // This assertion is what covers *reading object streams*, which is the reason the
        // `/Type /ObjStm` requirement exists at all: PdfBox can only return the sentence below after it
        // has decompressed the object stream and resolved the page content and font resources inside it.
        val text = PDDocument.load(fixtureFile()).use { document ->
            PDFTextStripper().apply { sortByPosition = true }.getText(document)
        }

        val normalized = text.replace('\u00AD', ' ').replace(Regex("\\s+"), " ")
        assertTrue(
            "the extracted text must carry the accented sentence; got ${text.length} chars: " +
                normalized.take(200),
            normalized.contains(EXPECTED_SENTENCE),
        )
    }

    /** Copies the test apk's asset to the cache dir so PdfBox can open it by path. */
    private fun fixtureFile(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val out = File(instrumentation.targetContext.cacheDir, ASSET).apply { parentFile?.mkdirs() }
        instrumentation.context.assets.open(ASSET).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

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
        const val ASSET = "synthetic_spanish_novel.pdf"

        /** The `ABCDEF+Name` subset form: six uppercase letters, a plus sign, then the face name. */
        val SUBSET_PREFIX = Regex("^[A-Z]{6}\\+.+")

        // The fixture's opening sentence (señora, jardín, mañana, pájaros), compared after whitespace is
        // collapsed so line wrapping cannot break the match.
        const val EXPECTED_SENTENCE = "La señora del jardín anotaba cada mañana los nombres de los pájaros"
    }
}
