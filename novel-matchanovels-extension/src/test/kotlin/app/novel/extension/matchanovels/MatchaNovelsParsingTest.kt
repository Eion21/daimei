package app.novel.extension.matchanovels

import app.novel.source.api.ContentBlock
import app.novel.source.api.Novel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The page-to-data half of the source, tested as the user experiences it: a whole page goes in and
 * the list, the metadata or the prose comes out.
 */
class MatchaNovelsParsingTest {

    @Test
    fun `the catalogue slice parses to one novel per card, in order`() {
        val novels = MatchaNovelsParsing.parseCatalogue(TestPages.CATALOGUE)

        assertEquals(
            listOf("I-Married-The-Dragon-I-Killed", "Corazon-de-Tinta", "La-Dama-del-Norte"),
            novels.map { it.id },
        )
        assertEquals("I Married The Dragon I Killed", novels[0].title)
        assertEquals("Corazón de Tinta", novels[1].title)
        assertTrue(novels.all { it.sourceId == "matchanovels" })
    }

    @Test
    fun `the cover of a card is decoded out of the next image proxy`() {
        val covers = MatchaNovelsParsing.parseCatalogue(TestPages.CATALOGUE).map { it.coverUrl }

        assertEquals(
            "${TestPages.SITE}/static/imagenes-portadas/portada-I-Married-The-Dragon-I-Killed.webp?v=1784448982",
            covers[0],
        )
        // No image at all: fall back to the deterministic portada-{slug}.jpg address.
        assertEquals("${TestPages.SITE}/static/imagenes-portadas/portada-Corazon-de-Tinta.jpg", covers[1])
        // A plain address that is not the proxy is passed through untouched.
        assertEquals("https://cdn.example.com/covers/dama.jpg", covers[2])
    }

    @Test
    fun `search folds case and accents over the parsed catalogue`() {
        val novels = MatchaNovelsParsing.parseCatalogue(TestPages.CATALOGUE)

        assertEquals(listOf("Corazon-de-Tinta"), MatchaNovelsParsing.filterByTitle(novels, "corazon").map { it.id })
        assertEquals(
            listOf("I-Married-The-Dragon-I-Killed"),
            MatchaNovelsParsing.filterByTitle(novels, "DRAGÓN").map { it.id },
        )
        assertTrue(MatchaNovelsParsing.filterByTitle(novels, "zzz").isEmpty())
    }

    @Test
    fun `the novel page yields its title and its Book metadata`() {
        val novel = MatchaNovelsParsing.parseNovelDetails(
            TestPages.NOVEL,
            Novel(sourceId = "matchanovels", id = "I-Married-The-Dragon-I-Killed", title = "stale"),
        )

        assertEquals("I Married The Dragon I Killed", novel.title)
        assertEquals("MatchaNovels", novel.author)
        assertEquals("Línea uno.\n\"Cita\".\nFin\u200b", novel.description)
        assertEquals(
            "${TestPages.SITE}/static/imagenes-portadas/portada-I-Married-The-Dragon-I-Killed.webp?v=1784448982",
            novel.coverUrl,
        )
    }

    @Test
    fun `only the Book ld-json block is read, escapes and all`() {
        val metadata = MatchaNovelsParsing.parseBookJsonLd(TestPages.BOOK_JSON)

        assertEquals("I Married The Dragon I Killed", metadata?.name)
        assertEquals("Línea uno.\n\"Cita\".\nFin\u200b", metadata?.description)
        assertEquals("MatchaNovels", metadata?.author)
        assertNull(MatchaNovelsParsing.parseBookJsonLd(TestPages.BREADCRUMB_JSON))
    }

    @Test
    fun `the chapter list keeps the real chapters and drops the start-reading CTA`() {
        val novel = Novel(sourceId = "matchanovels", id = "I-Married-The-Dragon-I-Killed", title = "t")
        val chapters = MatchaNovelsParsing.parseChapterList(TestPages.NOVEL, novel)

        assertEquals(listOf(1f, 2f, 3f), chapters.map { it.number })
        assertEquals("Capítulo 1", chapters[0].title)
        assertTrue(chapters[1].title.startsWith("Capítulo 2"))
        assertEquals("${TestPages.SITE}/novela/I-Married-The-Dragon-I-Killed/capitulo/3", chapters[2].url)
        assertTrue(chapters.all { it.novelId == novel.id })
    }

    private fun novel(slug: String) = Novel(sourceId = "matchanovels", id = slug, title = slug)

    @Test
    fun `a large escaped flight payload yields every chapter, not the truncated anchors`() {
        val slug = "Shadow-Slave"
        val rows = (1..3216).map { TestPages.chapterRow(it.toString()) }
        val html = TestPages.payloadNovel(slug, rows, escaped = true)

        val chapters = MatchaNovelsParsing.parseChapterList(html, novel(slug))

        assertEquals(3216, chapters.size)
        assertEquals("1", chapters.first().id)
        assertEquals(1f, chapters.first().number)
        assertEquals("Capítulo 1", chapters.first().title)
        assertEquals("3216", chapters.last().id)
        assertEquals(3216f, chapters.last().number)
        assertEquals("${TestPages.SITE}/novela/$slug/capitulo/3216", chapters.last().url)
    }

    @Test
    fun `an unescaped capitulos payload is read too`() {
        val slug = "Some-Novela"
        val rows = listOf(TestPages.chapterRow("1"), TestPages.chapterRow("2"))
        val html = TestPages.payloadNovel(slug, rows, escaped = false)

        val chapters = MatchaNovelsParsing.parseChapterList(html, novel(slug))

        assertEquals(listOf(1f, 2f), chapters.map { it.number })
        assertEquals("${TestPages.SITE}/novela/$slug/capitulo/2", chapters[1].url)
    }

    @Test
    fun `a page without a payload still yields its rendered chapter links`() {
        val chapters = MatchaNovelsParsing.parseChapterList(
            TestPages.NOVEL,
            novel("I-Married-The-Dragon-I-Killed"),
        )

        assertEquals(listOf(1f, 2f, 3f), chapters.map { it.number })
    }

    @Test
    fun `a payload row whose number is not numeric is skipped`() {
        val slug = "Some-Novela"
        val rows = listOf(
            TestPages.chapterRow("1"),
            """["abc","22 jul 26",false,null,null]""",
            TestPages.chapterRow("3"),
        )
        val html = TestPages.payloadNovel(slug, rows, escaped = true)

        val chapters = MatchaNovelsParsing.parseChapterList(html, novel(slug))

        assertEquals(listOf(1f, 3f), chapters.map { it.number })
    }

    @Test
    fun `the payload wins over the anchors and both paths agree on a chapter`() {
        val slug = "I-Married-The-Dragon-I-Killed"
        val rows = (1..500).map { TestPages.chapterRow(it.toString()) }
        val html = TestPages.payloadNovel(slug, rows, escaped = true, links = 3)

        val payload = MatchaNovelsParsing.parseChapterList(html, novel(slug))
        val links = MatchaNovelsParsing.parseChapterLinks(html, novel(slug))

        // 500 payload rows beat the 3 rendered anchors, and the first chapter is the same object.
        assertEquals(500, payload.size)
        assertEquals(3, links.size)
        assertEquals(links.first(), payload.first())
    }

    @Test
    fun `chapter links are recognised from their href alone`() {
        assertEquals(
            "I-Married-The-Dragon-I-Killed",
            MatchaNovelsParsing.novelSlugFromHref("/novela/I-Married-The-Dragon-I-Killed"),
        )
        assertEquals(4f, MatchaNovelsParsing.chapterNumberFromHref("/novela/Some-Novela/capitulo/4"))
        assertNull(MatchaNovelsParsing.novelSlugFromHref("/novela/Some-Novela/capitulo/4"))
        assertNull(MatchaNovelsParsing.chapterNumberFromHref("/biblioteca"))
    }

    @Test
    fun `the chapter page yields prose and none of the chrome`() {
        val content = MatchaNovelsParsing.parseChapterContent(TestPages.CHAPTER)

        assertEquals(
            listOf(
                "Había una vez un hombre singular que se convirtió en un gran mago únicamente a través del odio.",
                "Su nombre era Ferda Rosnova.",
                "—Tu compromiso con la Reina de Valdrova ha sido arreglado.",
            ),
            content.blocks.map { (it as ContentBlock.Paragraph).text },
        )
    }

    @Test
    fun `a chapter page with no prose parses to no blocks`() {
        assertTrue(MatchaNovelsParsing.parseChapterContent(TestPages.CHAPTER_WITHOUT_PROSE).blocks.isEmpty())
    }
}
