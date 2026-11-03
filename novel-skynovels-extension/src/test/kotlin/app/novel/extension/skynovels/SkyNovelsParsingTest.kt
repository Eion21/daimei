package app.novel.extension.skynovels

import app.novel.source.api.ContentBlock
import app.novel.source.api.Novel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The response-to-data half of the source, tested as the reader experiences it: an API response goes
 * in and the list, the metadata or the prose comes out.
 */
class SkyNovelsParsingTest {

    @Test
    fun `the catalogue lists one novel per entry with its cover and synopsis`() {
        val page = SkyNovelsParsing.parseCatalogue(TestFixtures.CATALOGUE)

        assertEquals(listOf("866", "42"), page.novels.map { it.id })
        assertEquals(listOf("El Dominio del Emperador", "Mago Supremo"), page.novels.map { it.title })
        assertEquals("Yan Bi Xiao Sheng", page.novels[0].author)
        // The synopsis carries HTML; it is reduced to readable text, accents intact.
        assertEquals("Sinopsis con HTML y acentos: corazón.", page.novels[0].description)
        assertEquals("${TestFixtures.API}/get-image/abc123.webp/novels/true", page.novels[0].coverUrl)
        assertTrue(page.novels.all { it.sourceId == "skynovels" })
    }

    @Test
    fun `the catalogue reports a next page until the last one`() {
        assertTrue(SkyNovelsParsing.parseCatalogue(TestFixtures.CATALOGUE).hasNextPage)
        assertFalse(SkyNovelsParsing.parseCatalogue(TestFixtures.LAST_CATALOGUE).hasNextPage)
    }

    @Test
    fun `a response without a catalogue parses to an empty page`() {
        val page = SkyNovelsParsing.parseCatalogue(TestFixtures.EMPTY)

        assertTrue(page.novels.isEmpty())
        assertFalse(page.hasNextPage)
    }

    @Test
    fun `search goes through q and never through search`() {
        assertEquals("${TestFixtures.API}/novels?q=dragon&page=1", SkyNovelsParsing.searchUrl("dragon", 1))
        assertFalse(SkyNovelsParsing.searchUrl("dragon", 1).contains("search="))
        // A space is percent-encoded for the query string.
        assertEquals("${TestFixtures.API}/novels?q=nuevo+mundo&page=2", SkyNovelsParsing.searchUrl("nuevo mundo", 2))
    }

    @Test
    fun `the novel detail merges the base response into the novel`() {
        val incoming = Novel(sourceId = "skynovels", id = "866", title = "stale")

        val details = SkyNovelsParsing.parseNovelDetails(TestFixtures.NOVEL_BASE, incoming)

        assertEquals("El Dominio del Emperador", details.title)
        assertEquals("Yan Bi Xiao Sheng", details.author)
        assertEquals("Sinopsis completa en detalle.", details.description)
        assertEquals("${TestFixtures.API}/get-image/abc123.webp/novels/true", details.coverUrl)
    }

    @Test
    fun `a base response without the fields keeps the incoming novel`() {
        val incoming = Novel(sourceId = "skynovels", id = "866", title = "Ya cargada", author = "Autor")

        assertEquals(incoming, SkyNovelsParsing.parseNovelDetails(TestFixtures.EMPTY, incoming))
    }

    @Test
    fun `the genres response yields the genre names`() {
        assertEquals(listOf("Acción", "Fantasía"), SkyNovelsParsing.parseGenres(TestFixtures.GENRES))
    }

    @Test
    fun `the chapter list keeps every chapter with its number and title`() {
        val chapters = SkyNovelsParsing.parseChapterList(TestFixtures.CHAPTER_LIST, novel())

        assertEquals(3, chapters.size)
        assertEquals(listOf("355958", "355959", "355960"), chapters.map { it.id })
        assertEquals(listOf(355958f, 355959f, 355960f), chapters.map { it.number })
        assertEquals(listOf("Capítulo 355958", "Capítulo 355959", "Capítulo 355960"), chapters.map { it.title })
        assertTrue(chapters.all { it.novelId == "866" })
    }

    @Test
    fun `a vip chapter is labelled and never dropped`() {
        val chapters = SkyNovelsParsing.parseChapterList(TestFixtures.CHAPTER_LIST_WITH_VIP, novel())

        assertEquals(2, chapters.size)
        assertEquals(listOf("Capítulo 1", "Capítulo 2 · VIP"), chapters.map { it.title })
    }

    @Test
    fun `a list whose count matches its declared total is accepted`() {
        val chapters = SkyNovelsParsing.parseChapterList(
            TestFixtures.chapterList(declared = 3, ids = listOf(1, 2, 3)),
            novel(),
        )

        assertEquals(3, chapters.size)
    }

    @Test
    fun `a list shorter than its declared total fails instead of reporting a truncation`() {
        val truncated = TestFixtures.chapterList(declared = 5, ids = listOf(1, 2, 3))

        assertThrows(IllegalStateException::class.java) { SkyNovelsParsing.parseChapterList(truncated, novel()) }
    }

    @Test
    fun `a list without a declared total is accepted`() {
        val chapters = SkyNovelsParsing.parseChapterList(
            TestFixtures.chapterList(declared = null, ids = listOf(1, 2, 3)),
            novel(),
        )

        assertEquals(3, chapters.size)
    }

    @Test
    fun `a large declared total keeps every chapter`() {
        val chapters = SkyNovelsParsing.parseChapterList(
            TestFixtures.chapterList(declared = 7190, ids = (1..7190).toList()),
            novel(),
        )

        assertEquals(7190, chapters.size)
        assertEquals("7190", chapters.last().id)
    }

    @Test
    fun `the markdown chapter becomes a heading, paragraphs and a divider`() {
        val blocks = SkyNovelsParsing.parseChapterContent(TestFixtures.CHAPTER_MARKDOWN).blocks

        assertEquals(
            listOf(
                ContentBlock.Heading("Capítulo 1: El comienzo", level = 2),
                ContentBlock.Paragraph("Había una vez un hombre singular. Su nombre era Ferda."),
                ContentBlock.Divider,
                ContentBlock.Paragraph("Fin."),
            ),
            blocks,
        )
    }

    @Test
    fun `markdown soft wraps join prose while blank lines and structural blocks separate it`() {
        val markdown = """
            Primera línea
            continúa aquí.

            Otro párrafo.
            ## Título
            Después del título
            sigue el texto.
            ![Portada](https://img.example/cover.png)
            Antes de la regla.
            ---
            Texto final
            continúa.
        """.trimIndent()

        assertEquals(
            listOf(
                ContentBlock.Paragraph("Primera línea continúa aquí."),
                ContentBlock.Paragraph("Otro párrafo."),
                ContentBlock.Heading("Título", 2),
                ContentBlock.Paragraph("Después del título sigue el texto."),
                ContentBlock.Image("https://img.example/cover.png", "Portada"),
                ContentBlock.Paragraph("Antes de la regla."),
                ContentBlock.Divider,
                ContentBlock.Paragraph("Texto final continúa."),
            ),
            SkyNovelsParsing.parseChapterText(markdown),
        )
    }

    @Test
    fun `the html chapter loses its tags and decodes its entities`() {
        val blocks = SkyNovelsParsing.parseChapterContent(TestFixtures.CHAPTER_HTML).blocks

        assertEquals(
            listOf(
                ContentBlock.Paragraph("Capítulo 1186 - La batalla"),
                ContentBlock.Paragraph("El cielo & la tierra temblaron."),
                ContentBlock.Paragraph("—No ¡huyas!"),
            ),
            blocks,
        )
    }

    @Test
    fun `a chapter mixing markdown and html is read as both`() {
        val blocks = SkyNovelsParsing.parseChapterContent(TestFixtures.CHAPTER_MIXED).blocks

        assertEquals(
            listOf(
                ContentBlock.Heading("Capítulo 7", level = 2),
                ContentBlock.Paragraph("Un giro inesperado."),
            ),
            blocks,
        )
    }

    @Test
    fun `a chapter of only the watermark parses to no blocks`() {
        assertTrue(SkyNovelsParsing.parseChapterContent(TestFixtures.CHAPTER_INVISIBLE_ONLY).blocks.isEmpty())
    }

    @Test
    fun `a chapter response without content parses to no blocks`() {
        assertTrue(SkyNovelsParsing.parseChapterContent(TestFixtures.EMPTY).blocks.isEmpty())
    }

    @Test
    fun `the zero-width watermark is gone from the text`() {
        val blocks = SkyNovelsParsing.parseChapterText(TestFixtures.ZERO_WIDTH_TEXT)

        assertEquals(listOf(ContentBlock.Paragraph("Hello, mundo.")), blocks)
    }

    @Test
    fun `a markdown image and a horizontal rule become their blocks`() {
        val blocks = SkyNovelsParsing.parseChapterContent(TestFixtures.CHAPTER_IMAGE_AND_RULE).blocks

        assertEquals(
            listOf(
                ContentBlock.Image("https://img.example/cover.png", "Portada"),
                ContentBlock.Divider,
                ContentBlock.Paragraph("Texto final."),
            ),
            blocks,
        )
    }

    @Test
    fun `the strip covers the whole format category, not just the two printed codepoints`() {
        // Word joiner, BOM and soft hyphen are all Cf and all must go; \n survives.
        assertEquals("ab\nc", SkyNovelsParsing.stripInvisible("\u2060a\uFEFFb\u00AD\nc"))
    }

    @Test
    fun `the json reader decodes escapes, unicode and numbers`() {
        val root = SkyNovelsJson.asObject(
            SkyNovelsJson.parse("""{"name":"l\u00ednea","count":7190,"rating":4.5,"ok":true,"none":null}"""),
        )

        assertEquals("línea", root?.get("name"))
        val count = SkyNovelsJson.asInt(root?.get("count")) ?: -1
        val rating = SkyNovelsJson.asFloat(root?.get("rating")) ?: 0f
        assertEquals(7190, count)
        assertEquals(4.5f, rating)
        assertEquals(true, root?.get("ok"))
        assertNull(root?.get("none"))
    }

    @Test
    fun `a malformed json response fails instead of returning garbage`() {
        assertThrows(IllegalStateException::class.java) { SkyNovelsJson.parse("""{"novels":[""") }
    }

    @Test
    fun `HTML preserves comparisons and quoted greater-than attributes`() {
        assertEquals(
            listOf(ContentBlock.Paragraph("Si a < b y c > d, sigue. Fin.")),
            SkyNovelsParsing.parseChapterText("<p title=\"x > y\">Si a < b y c > d, sigue. <em>Fin.</em></p>"),
        )
    }

    @Test
    fun `HTML headings images breaks and lists keep structure with quoted attributes`() {
        assertEquals(
            listOf(
                ContentBlock.Heading("Título", 2),
                ContentBlock.Paragraph("Uno"),
                ContentBlock.Paragraph("Dos"),
                ContentBlock.Image("https://img.example/a.png", "A > B"),
                ContentBlock.Divider,
                ContentBlock.Paragraph("Fin"),
            ),
            SkyNovelsParsing.parseChapterText(
                "<h2 title='a > b'>Título</h2><ul><li>Uno<br>Dos</li></ul>" +
                    "<img src='https://img.example/a.png' alt='A > B'><hr><p>Fin</p>",
            ),
        )
    }

    @Test
    fun `plain markdown keeps literal entities and angle comparisons`() {
        assertEquals(
            listOf(ContentBlock.Paragraph("a<b y c>d. &lt;literal&gt;")),
            SkyNovelsParsing.parseChapterText("a<b y c>d. &lt;literal&gt;"),
        )
    }

    private fun novel() = Novel(sourceId = "skynovels", id = "866", title = "El Dominio del Emperador")
}
