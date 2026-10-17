package app.novel.extension.jabrascan

import app.novel.source.api.Novel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The three documents as the source will read them: `obras.xml` becomes a catalogue or a detail,
 * `capitulos.json` becomes clave-to-path, and `books/{clave}.json` becomes the chapter list.
 *
 * The reader never sees a crash for a document that is merely empty or merely foreign, and always sees
 * one for a document that stops in the middle: the two are different failures, and only the second
 * means the host should try again.
 */
class JabraScanParsingTest {

    @Test
    fun `the catalogue lists one novel per visible obra`() {
        val page = JabraScanParsing.parseCatalogue(TestFixtures.CATALOGUE)

        assertEquals(listOf("TorreDeCristal", "ObraMinima"), page.novels.map { it.id })
        assertTrue(page.novels.all { it.sourceId == "jabrascan" })
        assertFalse(page.hasNextPage)
    }

    @Test
    fun `a sinopsis in CDATA becomes one line with its markup gone`() {
        val novel = JabraScanParsing.parseCatalogue(TestFixtures.CATALOGUE).novels.first()

        assertEquals("La torre de cristal", novel.title)
        assertEquals("Autor Uno", novel.author)
        assertEquals("Una torre, un secreto. Blog Y una segunda línea.", novel.description)
        assertEquals("TorreDeCristal/TorreDeCristal01.webp", novel.coverUrl)
    }

    @Test
    fun `an obra without a sinopsis carries what it has and nothing more`() {
        val minimal = JabraScanParsing.parseCatalogue(TestFixtures.CATALOGUE).novels.last()

        assertEquals("Obra mínima", minimal.title)
        assertEquals("Autor Tres", minimal.author)
        assertNull(minimal.description)
        assertNull(minimal.coverUrl)
    }

    @Test
    fun `only an explicit no hides an obra`() {
        val page = JabraScanParsing.parseCatalogue(TestFixtures.CATALOGUE_DAMAGED)

        // No clave is nothing to address and no title is nothing to render; a missing `visible` is neither.
        assertEquals(listOf("SinVisible"), page.novels.map { it.id })
    }

    @Test
    fun `the visible-no skip is deliberate so the catalogue hides the obra while the detail still serves it`() {
        val page = JabraScanParsing.parseCatalogue(TestFixtures.CATALOGUE)
        // An obra the site marks hidden stays out of the catalogue...
        assertTrue(page.novels.none { it.id == "ObraOculta" })

        // ...but a reader who already has it keeps seeing it, because the detail ignores `visible`.
        val hidden = Novel(sourceId = "jabrascan", id = "ObraOculta", title = "Título provisional")
        val details = JabraScanParsing.parseNovelDetails(TestFixtures.CATALOGUE, hidden)

        assertEquals("Obra oculta", details.title)
        assertEquals("Autor Dos", details.author)
    }

    @Test
    fun `a document that is not a catalogue reads as an empty page`() {
        assertTrue(JabraScanParsing.parseCatalogue(TestFixtures.NOT_A_CATALOGUE).novels.isEmpty())
        assertTrue(JabraScanParsing.parseCatalogue(TestFixtures.CATALOGUE_EMPTY).novels.isEmpty())
    }

    @Test
    fun `a truncated catalogue fails naming the document`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            JabraScanParsing.parseCatalogue(TestFixtures.CATALOGUE_TRUNCATED)
        }

        assertTrue(failure.message!!.contains("obras.xml"), failure.message)
    }

    @Test
    fun `the catalogue reports each discarded obra and the reason`() {
        val report = JabraScanParsing.parseCatalogueReport(TestFixtures.CATALOGUE_DAMAGED)

        assertEquals(listOf("SinVisible"), report.page.novels.map { it.id })
        assertEquals(
            listOf(
                JabraScanParsing.DiscardReason.MISSING_CLAVE,
                JabraScanParsing.DiscardReason.MISSING_TITLE,
            ),
            report.discarded.map { it.reason },
        )
        // The clave of the one that had it is kept, so a log line can name the obra.
        assertEquals(listOf(null, "SinTitulo"), report.discarded.map { it.clave })
        assertTrue(report.discarded.all { it.describe().isNotBlank() })
    }

    @Test
    fun `a catalogue whose every obra was discarded fails loudly instead of reading as empty`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            JabraScanParsing.parseCatalogue(TestFixtures.CATALOGUE_ALL_DISCARDED)
        }

        assertTrue(failure.message!!.contains("obras.xml"), failure.message)
        assertTrue(failure.message!!.contains("2"), failure.message)
    }

    @Test
    fun `a catalogue with no obras at all stays a normal empty page`() {
        val report = JabraScanParsing.parseCatalogueReport(TestFixtures.CATALOGUE_EMPTY)

        assertTrue(report.page.novels.isEmpty())
        assertTrue(report.discarded.isEmpty())
    }

    @Test
    fun `the detail merges the obra into the incoming novel, a hidden one included`() {
        val incoming = Novel(sourceId = "jabrascan", id = "ObraOculta", title = "sinopsis provisional")

        val details = JabraScanParsing.parseNovelDetails(TestFixtures.CATALOGUE, incoming)

        assertEquals("Obra oculta", details.title)
        assertEquals("Autor Dos", details.author)
        assertEquals("Sinopsis de la obra oculta.", details.description)
        assertEquals("ObraOculta/ObraOculta01.webp", details.coverUrl)
    }

    @Test
    fun `a detail the catalogue does not carry leaves the novel as it arrived`() {
        val incoming = Novel(sourceId = "jabrascan", id = "Desconocida", title = "Sin ficha")

        assertEquals(incoming, JabraScanParsing.parseNovelDetails(TestFixtures.CATALOGUE, incoming))
    }

    @Test
    fun `the title search ignores case and accents`() {
        val novels = JabraScanParsing.parseCatalogue(TestFixtures.CATALOGUE).novels

        assertEquals(listOf("ObraMinima"), JabraScanParsing.filterByTitle(novels, "MINIMA").map { it.id })
        assertEquals(listOf("TorreDeCristal"), JabraScanParsing.filterByTitle(novels, "torre").map { it.id })
        assertTrue(JabraScanParsing.filterByTitle(novels, "zzz").isEmpty())
        // An empty query is not a search: it asks for the whole catalogue back.
        assertEquals(novels, JabraScanParsing.filterByTitle(novels, "   "))
    }

    @Test
    fun `the chapter index maps each clave to its path`() {
        val index = JabraScanParsing.parseChapterIndex(TestFixtures.INDEX)

        assertEquals(
            mapOf(
                "TorreDeCristal" to "books/TorreDeCristal.json",
                "ObraOculta" to "books/ObraOculta.json",
            ),
            index,
        )
    }

    @Test
    fun `an index entry that is not a path is skipped and the rest survives`() {
        val index = JabraScanParsing.parseChapterIndex(TestFixtures.INDEX_WITH_UNKNOWN_ENTRY)

        assertEquals(mapOf("TorreDeCristal" to "books/TorreDeCristal.json"), index)
    }

    @Test
    fun `an index of the wrong shape reads as no index at all`() {
        assertTrue(JabraScanParsing.parseChapterIndex(TestFixtures.INDEX_EMPTY).isEmpty())
        assertTrue(JabraScanParsing.parseChapterIndex(TestFixtures.INDEX_NOT_AN_OBJECT).isEmpty())
    }

    @Test
    fun `a truncated index fails naming the document`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            JabraScanParsing.parseChapterIndex(TestFixtures.INDEX_TRUNCATED)
        }

        assertTrue(failure.message!!.contains("capitulos.json"), failure.message)
    }

    @Test
    fun `the chapter list keeps the file order and takes a stable id and the bare file name`() {
        val chapters = JabraScanParsing.parseChapterList(TestFixtures.CHAPTERS, novel("TorreDeCristal"))

        assertEquals(3, chapters.size)
        assertEquals(
            listOf(
                "TorreDeCristal/TorreDeCristal-0000.0v01_TorreDeCristal.pdf",
                "TorreDeCristal/TorreDeCristal-0001.1v01_TorreDeCristal.pdf",
                "TorreDeCristal/TorreDeCristal-0002.0v02_TorreDeCristal.pdf",
            ),
            chapters.map { it.id },
        )
        // The id is the stable work+file key; the url is the bare file name, encoded only at request time.
        assertEquals(
            listOf(
                "TorreDeCristal-0000.0v01_TorreDeCristal.pdf",
                "TorreDeCristal-0001.1v01_TorreDeCristal.pdf",
                "TorreDeCristal-0002.0v02_TorreDeCristal.pdf",
            ),
            chapters.map { it.url },
        )
        assertEquals(listOf("Prólogo", "Capítulo \"El pacto\"", "Capítulo 2"), chapters.map { it.title })
        assertEquals(listOf(0f, 1.1f, 2f), chapters.map { it.number })
        assertTrue(chapters.all { it.sourceId == "jabrascan" && it.novelId == "TorreDeCristal" })
    }

    @Test
    fun `a chapter without a number is kept, and a chapter without a title keeps the file's number`() {
        val chapters =
            JabraScanParsing.parseChapterList(TestFixtures.CHAPTERS_WITHOUT_NUMBERS, novel("TorreDeCristal"))

        assertEquals(2, chapters.size)
        assertEquals("Sin número", chapters[0].title)
        assertNull(chapters[0].number)
        assertEquals("Capítulo 78", chapters[1].title)
        assertEquals(78f, chapters[1].number)
    }

    @Test
    fun `a chapter with no file name is dropped and the next one survives`() {
        val chapters =
            JabraScanParsing.parseChapterList(TestFixtures.CHAPTERS_WITH_DAMAGED_ROW, novel("TorreDeCristal"))

        assertEquals(listOf("TorreDeCristal/buena.pdf"), chapters.map { it.id })
    }

    @Test
    fun `a work with no chapters reads as an empty list`() {
        assertTrue(JabraScanParsing.parseChapterList(TestFixtures.CHAPTERS_EMPTY, novel("TorreDeCristal")).isEmpty())
        assertTrue(
            JabraScanParsing.parseChapterList(TestFixtures.CHAPTERS_SHAPE_MISMATCH, novel("TorreDeCristal")).isEmpty(),
        )
    }

    @Test
    fun `a chapter list under another key is refused rather than filed under this work`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            JabraScanParsing.parseChapterList(TestFixtures.CHAPTERS_UNDER_ANOTHER_KEY, novel("TorreDeCristal"))
        }

        // Both keys are named, so the mismatch is visible in the failure.
        assertTrue(failure.message!!.contains("TorreDeCristal"), failure.message)
        assertTrue(failure.message!!.contains("OtroNombre"), failure.message)
    }

    @Test
    fun `a malformed chapter list fails naming the work's own document`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            JabraScanParsing.parseChapterList(TestFixtures.CHAPTERS_MALFORMED, novel("TorreDeCristal"))
        }

        assertTrue(failure.message!!.contains("books/TorreDeCristal.json"), failure.message)
    }

    @Test
    fun `whitespace folds to single spaces and accents survive`() {
        assertEquals("Uno dos", JabraScanParsing.normalize("  Uno\t\n dos  "))
        assertEquals("Obra mínima", JabraScanParsing.normalize("Obra   mínima"))
    }

    private fun novel(id: String): Novel = Novel(sourceId = "jabrascan", id = id, title = "La torre de cristal")
}
