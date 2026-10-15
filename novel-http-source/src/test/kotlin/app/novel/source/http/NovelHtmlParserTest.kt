package app.novel.source.http

import app.novel.source.api.Chapter
import app.novel.source.api.ContentBlock
import app.novel.source.api.Novel
import app.novel.source.api.NovelPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Parser tests against saved fixtures. Nothing here opens a socket: every input is a String
 * read from `src/test/resources/fixtures`, which is the same directory `tools/serve-fixtures.sh`
 * publishes for on-device runs.
 */
class NovelHtmlParserTest {

    private val novel = Novel(
        sourceId = SOURCE_ID,
        id = "torre-de-cristal",
        title = "La torre de cristal",
        author = "Ada Vergara",
    )

    @Test
    fun `parseNovelList reads every card inside the listing container`() {
        val page = parseNovelList(fixture("index.html"))

        assertEquals(
            listOf("La torre de cristal", "Cronicas del valle", "Quimera urbana"),
            page.novels.map { it.title },
        )
        assertEquals(listOf("Ada Vergara", "Bruno Salas", "Camila Nunez"), page.novels.map { it.author })
        assertEquals(
            listOf("/covers/torre-de-cristal.jpg", "/covers/cronicas-del-valle.jpg", "/covers/quimera-urbana.jpg"),
            page.novels.map { it.coverUrl },
        )
        assertTrue(!page.hasNextPage, "the fixture serves a single listing page")
    }

    @Test
    fun `parseNovelList derives the novel id from the card link`() {
        val page = parseNovelList(fixture("index.html"))

        assertEquals(
            listOf("torre-de-cristal", "cronicas-del-valle", "quimera-urbana"),
            page.novels.map { it.id },
        )
        assertTrue(page.novels.all { it.sourceId == SOURCE_ID })
    }

    @Test
    fun `parseNovelList ignores the ad block and the recommended sidebar`() {
        val page = parseNovelList(fixture("index.html"))

        // The ad block clones the card markup and the sidebar repeats a real novel. Scoping to
        // #novel-list is the only thing keeping either out, so assert on the exact set, not a count.
        assertEquals(3, page.novels.size, "ad card or sidebar card leaked into the listing")
        assertTrue(page.novels.none { it.title == "Curso intensivo de cripto" })
        assertTrue(page.novels.none { it.coverUrl == "/covers/ad-cripto.png" })
        assertEquals(1, page.novels.count { it.title == "Quimera urbana" }, "the sidebar duplicate leaked in")
    }

    @Test
    fun `parseNovelList reads a search results page with the same rules`() {
        val page = parseNovelList(fixture("search.html"))

        assertEquals(listOf("La torre de cristal"), page.novels.map { it.title })
        assertEquals("Ada Vergara", page.novels.single().author)
        assertTrue(page.novels.none { it.title == "Curso intensivo de cripto" })
    }

    @Test
    fun `parseNovelList reports a next page when the listing links one`() {
        val html = """
            <ul id="novel-list"><li class="novel-card"><a href="novel/a.html"></a>
            <h3 class="novel-title">A</h3></li></ul>
            <nav class="pagination"><a class="next-page" href="index.html?page=2">Siguiente</a></nav>
        """.trimIndent()

        assertTrue(parseNovelList(html).hasNextPage)
    }

    @Test
    fun `parseNovelList drops a card whose link addresses no document`() {
        val html = "<ul id='novel-list'>" +
            "<li class='novel-card'><a href='novel/'></a><h3 class='novel-title'>Sin destino</h3></li>" +
            "<li class='novel-card'><a href='novel/ok.html'></a><h3 class='novel-title'>Con destino</h3></li>" +
            "</ul>"

        assertEquals(listOf("Con destino"), parseNovelList(html).novels.map { it.title })
    }

    @Test
    fun `parseNovelDetails fills description and cover without inventing identity`() {
        val parsed = parseNovelDetails(fixture("novel/torre-de-cristal.html"), novel)

        assertEquals("torre-de-cristal", parsed.id)
        assertEquals(SOURCE_ID, parsed.sourceId)
        assertEquals("La torre de cristal", parsed.title)
        assertEquals("Ada Vergara", parsed.author)
        assertEquals("/covers/torre-de-cristal.jpg", parsed.coverUrl)
        assertEquals(
            "Unaginx ingeniera descubre que el edificio donde trabaja responde a un diseno " +
                "anterior a su construccion, y que el plano que lo documenta esta incompleto.",
            parsed.description,
        )
    }

    @Test
    fun `parseChapterList reads the chapter links of the detail page`() {
        val chapters = parseChapterList(fixture("novel/torre-de-cristal.html"), novel)

        assertEquals(
            listOf(
                "Prologo",
                "Capitulo 1: El ascensor",
                "Capitulo 2: La sala de mapas",
                "Capitulo 3: Viento",
            ),
            chapters.map { it.title },
        )
        assertEquals(
            listOf(
                "../chapter/torre-de-cristal-prologo.html",
                "../chapter/torre-de-cristal-1.html",
                "../chapter/torre-de-cristal-2.html",
                "../chapter/torre-de-cristal-3.html",
            ),
            chapters.map { it.url },
        )
        assertTrue(chapters.all { it.novelId == "torre-de-cristal" && it.sourceId == SOURCE_ID })
    }

    @Test
    fun `parseChapterList reads the chapter number out of the trailing slug segment`() {
        val chapters = parseChapterList(fixture("novel/torre-de-cristal.html"), novel)

        assertEquals(listOf(null, 1f, 2f, 3f), chapters.map { it.number })
    }

    @Test
    fun `parseChapterList leaves the number null when the slug does not end in a number`() {
        val html = "<article class='novel-detail'><ul class='chapter-list'>" +
            "<li><a href='../chapter/guia-de-torno.html'>Guia</a></li>" +
            "</ul></article>"

        val chapter = parseChapterList(html, novel).single()
        assertEquals("guia-de-torno", chapter.id)
        assertEquals(null, chapter.number)
    }

    @Test
    fun `parseChapterList ignores the ad block chapter list and the sidebar`() {
        val chapters = parseChapterList(fixture("novel/torre-de-cristal.html"), novel)

        assertEquals(4, chapters.size, "a chapter list outside .novel-detail leaked in")
        assertTrue(chapters.none { it.id == "torre-de-cristal-99" }, "the ad chapter leaked in")
        assertTrue(chapters.none { it.url!!.endsWith("quimera-urbana-1.html") }, "the sidebar chapter leaked in")
    }

    @Test
    fun `parseChapterContent maps every block type in document order`() {
        val blocks = parseChapterContent(fixture("chapter/torre-de-cristal-1.html"), chapter1()).blocks

        assertEquals(7, blocks.size)
        assertEquals(ContentBlock.Heading("El ascensor", level = 2), blocks[0])
        assertEquals(
            ContentBlock.Paragraph("El ascensor subia despacio, como si el edificio estuviera pensando."),
            blocks[1],
        )
        // A non-breaking space in the source has to come out as an ordinary space.
        assertEquals(
            ContentBlock.Paragraph(
                "Unanginecx seguro habia contado once pisos, pero el boton de la cabina marcaba trece.",
            ),
            blocks[2],
        )
        assertEquals(ContentBlock.Divider, blocks[3])
        assertEquals(ContentBlock.Paragraph("Cundo llego al ultimo piso la puerta se abrio sola."), blocks[4])
        assertEquals(ContentBlock.Image("/images/torre-ascensor.jpg", "El ascensor de la torre"), blocks[5])
        assertEquals(ContentBlock.Paragraph("Ahi adentro la esperaba el plano incompleto."), blocks[6])
    }

    @Test
    fun `parseChapterContent strips script style and ad block content`() {
        val blocks = parseChapterContent(fixture("chapter/torre-de-cristal-1.html"), chapter1()).blocks
        val rendered = render(blocks)

        assertTrue("__CHAPTER_ADS__" !in rendered, "inline script text became reader content")
        assertTrue("margin: 0 0 1em" !in rendered, "inline style text became reader content")
        assertTrue("Patrocinado por el Sponsor Ficticio" !in rendered, "ad copy became a paragraph")
        assertTrue("/ads/banner.gif" !in rendered, "the ad image survived the strip")
        assertTrue("Recomendado para ti" !in rendered, "sidebar copy became reader content")
        assertTrue("El ascensor" in rendered)
    }

    @Test
    fun `parseChapterContent drops the whitespace-only paragraph`() {
        val blocks = parseChapterContent(fixture("chapter/torre-de-cristal-1.html"), chapter1()).blocks

        assertTrue(blocks.none { it is ContentBlock.Paragraph && it.text.isBlank() })
    }

    @Test
    fun `parseChapterContent handles a chapter made only of paragraphs`() {
        val blocks = parseChapterContent(fixture("chapter/torre-de-cristal-2.html"), chapter2()).blocks

        assertEquals(
            listOf(
                ContentBlock.Paragraph("La sala de mapas no tenia ventanas ni relojes."),
                ContentBlock.Paragraph("En la pared, la ciudad estaba dibujada a mano y le faltaba un barrio entero."),
            ),
            blocks,
        )
    }

    @Test
    fun `parseChapterContent keeps a heading that is not level two and a trailing divider`() {
        val blocks = parseChapterContent(fixture("chapter/torre-de-cristal-3.html"), chapter3()).blocks

        assertEquals(
            listOf(
                ContentBlock.Heading("Viento", level = 3),
                ContentBlock.Paragraph("Esa noche el viento soplo desde el piso trece."),
                ContentBlock.Divider,
            ),
            blocks,
        )
    }

    @Test
    fun `parseChapterContent is scoped to the content container`() {
        val blocks = parseChapterContent(fixture("chapter/torre-de-cristal-1.html"), chapter1()).blocks

        // The page title and the chapter h1 sit outside .chapter-content and must not be read.
        assertEquals("El ascensor", assertIs<ContentBlock.Heading>(blocks.first()).text)
        assertTrue(blocks.none { it is ContentBlock.Heading && it.text == "Capitulo 1: El ascensor" })
    }

    @Test
    fun `an empty document yields empty results everywhere`() {
        assertEquals(NovelPage(emptyList(), hasNextPage = false), parseNovelList(""))
        assertEquals(novel, parseNovelDetails("", novel))
        assertEquals(emptyList(), parseChapterList("", novel))
        assertEquals(emptyList<ContentBlock>(), parseChapterContent("", chapter1()).blocks)
    }

    @Test
    fun `a document without the expected selectors yields empty results`() {
        val unrelated = "<html><body><p>Nothing to see here.</p></body></html>"

        assertEquals(NovelPage(emptyList(), hasNextPage = false), parseNovelList(unrelated))
        assertEquals(novel, parseNovelDetails(unrelated, novel))
        assertEquals(emptyList(), parseChapterList(unrelated, novel))
        assertEquals(emptyList<ContentBlock>(), parseChapterContent(unrelated, chapter1()).blocks)
    }

    @Test
    fun `malformed html does not throw`() {
        val malformed = listOf(
            "<div class='novel-card'><p>unclosed",
            "<ul id=novel-list><li class=novel-card><h3 class=novel-title>A</h3>",
            "<article class=novel-detail><ul class=chapter-list><li><a href=x.html>A",
            "<div class=chapter-content><p>texto sin cerrar<hr><img src=/a.jpg",
            "<<>>&&&;;;",
            "<!-- unterminated comment",
        )

        malformed.forEach { html ->
            parseNovelList(html)
            parseNovelDetails(html, novel)
            parseChapterList(html, novel)
            parseChapterContent(html, chapter1())
        }
    }

    @Test
    fun `the listing id addresses the detail document a later parse reads`() {
        val listed = parseNovelList(fixture("index.html")).novels.first { it.id == "torre-de-cristal" }

        // The id is the last segment of the card link, so `novel/{id}.html` is the document that
        // parseNovelDetails is built for. No network: the fixture tree is the server.
        val detail = parseNovelDetails(fixture("novel/${listed.id}.html"), listed)

        assertEquals(listed.id, detail.id)
        assertEquals(listed.title, detail.title)
        assertEquals(listed.author, detail.author)
    }

    @Test
    fun `the chapter url addresses the chapter document a later parse reads`() {
        val detail = parseNovelDetails(fixture("novel/torre-de-cristal.html"), novel)
        val chapters = parseChapterList(fixture("novel/torre-de-cristal.html"), detail)
        val chapter = chapters.first { it.id == "torre-de-cristal-1" }

        // `../chapter/x.html` from /novel/torre-de-cristal.html lands on /chapter/x.html, so the
        // file name is the chapter id.
        val blocks = parseChapterContent(fixture("chapter/${chapter.id}.html"), chapter).blocks

        assertEquals(ContentBlock.Heading("El ascensor", level = 2), blocks.first())
        assertTrue(blocks.any { it == ContentBlock.Divider })
        assertTrue(blocks.any { it == ContentBlock.Image("/images/torre-ascensor.jpg", "El ascensor de la torre") })
    }

    @Test
    fun `html entities are decoded rather than shown raw`() {
        val html = "<div class='chapter-content'><p>Tom&aacute; &amp; Jerry: &laquo;claro&raquo;</p></div>"

        val blocks = parseChapterContent(html, chapter1()).blocks
        assertEquals(listOf(ContentBlock.Paragraph("Tomá & Jerry: «claro»")), blocks)
    }

    @Test
    fun `noise nested deeper inside the content is still stripped`() {
        val html = "<div class='chapter-content'>" +
            "<p>Se queda.</p>" +
            "<div class='ad-slot'><div><div><p>Anuncio anidado</p><img src='/ads/x.gif'></div></div></div>" +
            "<script>if (a < b) { document.write('<p>inyectado</p>') }</script>" +
            "</div>"

        assertEquals(listOf(ContentBlock.Paragraph("Se queda.")), parseChapterContent(html, chapter1()).blocks)
    }

    @Test
    fun `an image with no source and a blank heading are dropped`() {
        val html = "<div class='chapter-content'>" +
            "<h3>&nbsp;</h3>" +
            "<img src='' alt='sin fuente'>" +
            "<img alt='sin fuente'>" +
            "<p>Solo esto.</p>" +
            "</div>"

        assertEquals(listOf(ContentBlock.Paragraph("Solo esto.")), parseChapterContent(html, chapter1()).blocks)
    }

    @Test
    fun `a card with no readable title is dropped`() {
        val html = "<ul id='novel-list'>" +
            "<li class='novel-card'><a href='novel/sin-titulo.html'></a></li>" +
            "<li class='novel-card'><a href='novel/con-titulo.html'></a>" +
            "<h3 class='novel-title'>Con titulo</h3></li>" +
            "</ul>"

        assertEquals(listOf("con-titulo"), parseNovelList(html).novels.map { it.id })
    }

    @Test
    fun `a chapter link with no text is dropped`() {
        val html = "<article class='novel-detail'><ul class='chapter-list'>" +
            "<li><a href='../chapter/vacio.html'>   </a></li>" +
            "<li><a href='../chapter/real.html'>Real</a></li>" +
            "</ul></article>"

        assertEquals(listOf("real"), parseChapterList(html, novel).map { it.id })
    }

    private fun render(blocks: List<ContentBlock>) = blocks.joinToString("\n") { block ->
        when (block) {
            is ContentBlock.Paragraph -> block.text
            is ContentBlock.Heading -> block.text
            is ContentBlock.Image -> block.url + (block.alt ?: "")
            ContentBlock.Divider -> "---"
        }
    }

    private fun chapter1() = Chapter(SOURCE_ID, "torre-de-cristal", "torre-de-cristal-1", "Capitulo 1: El ascensor")

    private fun chapter2() = Chapter(
        SOURCE_ID,
        "torre-de-cristal",
        "torre-de-cristal-2",
        "Capitulo 2: La sala de mapas",
    )

    private fun chapter3() = Chapter(SOURCE_ID, "torre-de-cristal", "torre-de-cristal-3", "Capitulo 3: Viento")

    private companion object {
        fun fixture(name: String): String =
            checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/$name")) {
                "missing fixture: fixtures/$name"
            }.bufferedReader().use { it.readText() }
    }
}
