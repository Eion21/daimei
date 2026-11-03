package app.novel.extension.jabrascan

import app.novel.source.api.Chapter
import app.novel.source.api.Novel
import app.novel.source.api.NovelNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

private const val CLAVE = "TorreDeCristal"

private val CATALOGUE_URL = JabraScanUrls.CONTENT_REPOSITORY + "/obras.xml"
private val INDEX_URL = JabraScanUrls.CONTENT_REPOSITORY + "/capitulos.json"
private val LIST_URL = JabraScanUrls.PDF_REPOSITORY + "/books/$CLAVE.json"

/** The two file names the mixed lists use, one per root. */
private val FILES = listOf("$CLAVE-0001.0v01.pdf", "$CLAVE-0002.0v01.pdf")

private val CATALOGUE = """
<obras>
<obra>
<visible>si</visible>
<clave>$CLAVE</clave>
<nombreobra>La torre de cristal</nombreobra>
<autor>Autor Uno</autor>
<sinopsis>Una torre y un secreto.</sinopsis>
<imagen>$CLAVE/$CLAVE-01.webp</imagen>
</obra>
</obras>
"""

/** One good obra and one with no `nombreobra`, so the catalogue reports exactly one discard. */
private val CATALOGUE_WITH_DISCARD = """
<obras>
<obra>
<visible>si</visible>
<clave>SinTitulo</clave>
</obra>
<obra>
<visible>si</visible>
<clave>$CLAVE</clave>
<nombreobra>La torre de cristal</nombreobra>
</obra>
</obras>
"""

private val INDEX = """{"$CLAVE":"books/$CLAVE.json"}"""

/** An index that carries a different work, so the clave under test is genuinely absent from it. */
private val INDEX_FOR_ANOTHER_WORK = """{"OtraObra":"books/OtraObra.json"}"""

/** A row that names `io-pdfs` explicitly, which shares the repository root with an absent server. */
private val IO_PDFS_LIST = """
{"$CLAVE":[
{"NombreArchivo":"${FILES[0]}","numCapitulo":"1","nombreCapitulo":"Uno","server":"io-pdfs"}
]}
"""

/** Both rows on the repository: no server at all. */
private val LIST_REPOSITORY = """
{"$CLAVE":[
{"NombreArchivo":"${FILES[0]}","numCapitulo":"1","nombreCapitulo":"Uno"},
{"NombreArchivo":"${FILES[1]}","numCapitulo":"2","nombreCapitulo":"Dos"}
]}
"""

/** The same two files, both on the site: the identity must be identical to [LIST_REPOSITORY]. */
private val LIST_ALL_LOCAL = """
{"$CLAVE":[
{"NombreArchivo":"${FILES[0]}","numCapitulo":"1","nombreCapitulo":"Uno","server":"local"},
{"NombreArchivo":"${FILES[1]}","numCapitulo":"2","nombreCapitulo":"Dos","server":"local"}
]}
"""

/** One repository row and one site row in the same work. */
private val MIXED_LIST = """
{"$CLAVE":[
{"NombreArchivo":"${FILES[0]}","numCapitulo":"1","nombreCapitulo":"Uno"},
{"NombreArchivo":"${FILES[1]}","numCapitulo":"2","nombreCapitulo":"Dos","server":"local"}
]}
"""

/** A row whose `server` names no known root: kept, but it cannot be downloaded. */
private val UNKNOWN_SERVER_LIST = """
{"$CLAVE":[
{"NombreArchivo":"weird.pdf","numCapitulo":"1","nombreCapitulo":"Uno","server":"weird"}
]}
"""

/** Four hand-written names, spaces and a comma included, to pin the id format. */
private val LIST_WITH_ODD_NAMES = """
{"$CLAVE":[
{"NombreArchivo":"a uno.pdf","numCapitulo":"1"},
{"NombreArchivo":"b,dos.pdf","numCapitulo":"2"},
{"NombreArchivo":"c tres.pdf","numCapitulo":"3"},
{"NombreArchivo":"d cuatro.pdf","numCapitulo":"4"}
]}
"""

/**
 * The source as the host drives it: the network is a stub that serves hand-written documents, so every
 * method is exercised end to end (URL in, contract types out) without a socket and without a device.
 *
 * Two properties are the point of T6 and are pinned here:
 *
 * - **a chapter's `url` and `id` are the bare file name and `{obra}/{file}`**, the stable pair the
 *   reader stores, and neither moves when the row's `server` or the chosen root does. The root is a
 *   request-time decision, not part of the identity;
 * - **the row's `server` picks the root per row**, read from a concurrent map that is filled while the
 *   chapter list is read and refetched once — and only once — when a fresh process asks for content
 *   before the list was ever read. A row whose `server` names no known root is kept (dropping it would
 *   make the host delete the stored chapter and its progress) and its download fails naming the value.
 *
 * The documents are synthetic: the real ones are a publishable library listing, and what matters here is
 * the shapes, not the text. Warnings go through the injected sink, never through `android.util.Log`,
 * which a plain-JVM test cannot call.
 */
class JabraScanSourceTest {

    @Test
    fun `catalogue and details preserve synopsis comparisons through the source API`() = runBlocking {
        val page = CATALOGUE.replace(
            "Una torre y un secreto.",
            "<![CDATA[Si a<b y c>d. <a title='x > y' href='url'>Fin</a>.]]>",
        )
        val source = Harness(mapOf(CATALOGUE_URL to page)).source
        val novel = source.popular(1).novels.single()
        assertEquals("Si a<b y c>d. Fin.", novel.description)
        assertEquals("Si a<b y c>d. Fin.", source.getNovelDetails(novel).description)
    }

    @Test
    fun `popular lists the catalogue and a catalogue that never pages has no next page`() = runBlocking {
        val harness = Harness(mapOf(CATALOGUE_URL to CATALOGUE))

        val page = harness.source.popular(1)
        assertEquals(listOf(CLAVE), page.novels.map { it.id })
        assertEquals("La torre de cristal", page.novels.single().title)
        assertFalse(page.hasNextPage)

        assertTrue(harness.source.popular(2).novels.isEmpty())
    }

    @Test
    fun `search filters the fetched catalogue by title`() = runBlocking {
        val harness = Harness(mapOf(CATALOGUE_URL to CATALOGUE))

        assertEquals(listOf(CLAVE), harness.source.search("torre", 1).novels.map { it.id })
        assertTrue(harness.source.search("inexistente", 1).novels.isEmpty())
    }

    @Test
    fun `getNovelDetails merges the obra into the novel the host holds`() = runBlocking {
        val harness = Harness(mapOf(CATALOGUE_URL to CATALOGUE))

        val details = harness.source.getNovelDetails(Novel("jabrascan", CLAVE, "sinopsis provisional"))

        assertEquals("La torre de cristal", details.title)
        assertEquals("Autor Uno", details.author)
        assertEquals("Una torre y un secreto.", details.description)
    }

    @Test
    fun `a discarded obra is reported through the injected sink and never through Log`() = runBlocking {
        val harness = Harness(mapOf(CATALOGUE_URL to CATALOGUE_WITH_DISCARD))

        harness.source.popular(1)

        assertEquals(1, harness.warnings.size, "expected the one discard, got: ${harness.warnings}")
        val line = harness.warnings.single()
        assertTrue(line.contains("SinTitulo"), "the dropped obra must be named: $line")
    }

    @Test
    fun `a chapter's id and url do not depend on the row's server or on the chosen root`() = runBlocking {
        val repositoryRows = Harness(mapOf(INDEX_URL to INDEX, LIST_URL to LIST_REPOSITORY))
        val localRows = Harness(mapOf(INDEX_URL to INDEX, LIST_URL to LIST_ALL_LOCAL))

        val onRepository = repositoryRows.source.getChapters(novel())
        val onSite = localRows.source.getChapters(novel())

        // The same files under different servers keep the same identity: the server only chooses a root.
        assertEquals(onRepository.map { it.id }, onSite.map { it.id })
        assertEquals(onRepository.map { it.url }, onSite.map { it.url })

        // The identity is the bare, unencoded file name and the work plus that name.
        assertEquals(FILES, onRepository.map { it.url })
        assertEquals(FILES.map { "$CLAVE/$it" }, onRepository.map { it.id })

        // The roots differ and the address each builds differs with them, while the chapter does not move.
        val repositoryRoot = JabraScanUrls.chapterPdfUrl(JabraScanUrls.PDF_REPOSITORY, CLAVE, FILES[0], 1f)
        val siteRoot = JabraScanUrls.chapterPdfUrl(JabraScanUrls.SITE, CLAVE, FILES[0], 1f)
        assertNotEquals(repositoryRoot, siteRoot)
        assertEquals(FILES[0], onRepository[0].url)
    }

    @Test
    fun `one work with a local row and a repository row downloads each from its own root`() = runBlocking {
        val harness = Harness(mapOf(INDEX_URL to INDEX, LIST_URL to MIXED_LIST))

        val chapters = harness.source.getChapters(novel())
        assertEquals(2, chapters.size)

        harness.source.getChapterContent(chapters[0])
        harness.source.getChapterContent(chapters[1])

        assertEquals(
            listOf(
                "${JabraScanUrls.PDF_REPOSITORY}/books/$CLAVE/${FILES[0]}",
                "${JabraScanUrls.SITE}/books/$CLAVE/${FILES[1]}",
            ),
            harness.network.byteFetches.toList(),
        )
    }

    @Test
    fun `an explicit io-pdfs server downloads from the repository like an absent one`() = runBlocking {
        val harness = Harness(mapOf(INDEX_URL to INDEX, LIST_URL to IO_PDFS_LIST))

        val chapters = harness.source.getChapters(novel())
        harness.source.getChapterContent(chapters[0])

        assertEquals(
            listOf("${JabraScanUrls.PDF_REPOSITORY}/books/$CLAVE/${FILES[0]}"),
            harness.network.byteFetches.toList(),
        )
    }

    @Test
    fun `a work the index does not carry is a work with no chapters and costs no list request`() = runBlocking {
        val harness = Harness(mapOf(INDEX_URL to INDEX_FOR_ANOTHER_WORK))

        assertTrue(harness.source.getChapters(novel()).isEmpty())

        assertEquals(listOf(INDEX_URL), harness.network.fetched.toList())
    }

    @Test
    fun `two chapters of one work read the list once, and a fresh source refetches it exactly once`() =
        runBlocking {
            val seeded = Harness(mapOf(INDEX_URL to INDEX, LIST_URL to MIXED_LIST))
            val chapters = seeded.source.getChapters(novel())
            seeded.source.getChapterContent(chapters[0])
            seeded.source.getChapterContent(chapters[1])
            assertEquals(1, seeded.chapterListFetches(), "the content path reuses the list getChapters read")

            val fresh = Harness(mapOf(LIST_URL to MIXED_LIST))
            val row = chapterOf(0)
            fresh.source.getChapterContent(row)
            fresh.source.getChapterContent(row)
            assertEquals(1, fresh.chapterListFetches(), "an unseeded source refetches its list exactly once")
        }

    @Test
    fun `an unknown server keeps the row and fails the download naming the value and the work`() {
        val harness = Harness(mapOf(INDEX_URL to INDEX, LIST_URL to UNKNOWN_SERVER_LIST))

        val chapters = runBlocking { harness.source.getChapters(novel()) }
        assertEquals(
            listOf("$CLAVE/weird.pdf"),
            chapters.map { it.id },
            "the row must survive, or the host would delete the stored chapter and its progress",
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { harness.source.getChapterContent(chapters.single()) }
        }
        assertTrue(failure.message!!.contains("weird"), failure.message)
        assertTrue(failure.message!!.contains(CLAVE), failure.message)
        assertTrue(harness.network.byteFetches.isEmpty(), "a row with no known root is not downloaded")
    }

    @Test
    fun `two simultaneous downloads of one work do not corrupt the resolved servers`() = runBlocking {
        val harness = Harness(mapOf(LIST_URL to MIXED_LIST))
        harness.network.fetchDelayMillis = 25

        val first = async(Dispatchers.Default) { harness.source.getChapterContent(chapterOf(0)) }
        val second = async(Dispatchers.Default) { harness.source.getChapterContent(chapterOf(1)) }
        first.await()
        second.await()

        assertEquals(
            setOf(
                "${JabraScanUrls.PDF_REPOSITORY}/books/$CLAVE/${FILES[0]}",
                "${JabraScanUrls.SITE}/books/$CLAVE/${FILES[1]}",
            ),
            harness.network.byteFetches.toSet(),
        )
        assertEquals(2, harness.network.byteFetches.size, "each row is downloaded exactly once")
        assertEquals(1, harness.chapterListFetches(), "the concurrent refetch collapses into one")
    }

    @Test
    fun `the chapter id is the work and the bare file name, pinned to concrete values`() = runBlocking {
        val harness = Harness(mapOf(INDEX_URL to INDEX, LIST_URL to LIST_WITH_ODD_NAMES))

        val chapters = harness.source.getChapters(novel())

        assertEquals(
            listOf(
                "$CLAVE/a uno.pdf",
                "$CLAVE/b,dos.pdf",
                "$CLAVE/c tres.pdf",
                "$CLAVE/d cuatro.pdf",
            ),
            chapters.map { it.id },
        )
        assertEquals(
            listOf("a uno.pdf", "b,dos.pdf", "c tres.pdf", "d cuatro.pdf"),
            chapters.map { it.url },
        )
    }

    @Test
    fun `source accepts BOM documents and refuses duplicate chapter identities`() {
        val valid = Harness(mapOf(INDEX_URL to "\uFEFF$INDEX", LIST_URL to "\uFEFF$MIXED_LIST"))
        assertEquals(FILES, runBlocking { valid.source.getChapters(novel()) }.map { it.url })

        val invalidList = """{"$CLAVE":[{"NombreArchivo":"${FILES[0]}"},""" +
            """{"NombreArchivo":"${FILES[0]}","server":"local"}]}"""
        val invalid = Harness(mapOf(INDEX_URL to INDEX, LIST_URL to invalidList))
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { invalid.source.getChapters(novel()) }
        }
        assertTrue(failure.message!!.contains(FILES[0]), failure.message)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { invalid.source.getChapterContent(chapterOf(0)) }
        }
        assertTrue(invalid.network.byteFetches.isEmpty())
    }

    private fun novel(): Novel = Novel("jabrascan", CLAVE, "La torre de cristal")

    /** A chapter of [CLAVE] as the source hands it out: id is work+file, url is the bare file name. */
    private fun chapterOf(index: Int): Chapter = Chapter(
        sourceId = "jabrascan",
        novelId = CLAVE,
        id = "$CLAVE/${FILES[index]}",
        title = "Capítulo ${index + 1}",
        number = (index + 1).toFloat(),
        url = FILES[index],
    )
}

/** The source and the stub behind it, so a test can read back what was actually requested. */
private class Harness(pages: Map<String, String>) {

    val network = StubNetwork(pages)
    val warnings = CopyOnWriteArrayList<String>()

    val source: JabraScanSource = JabraScanSource().apply {
        bindNetwork(network)
        logSink = { warnings += it }
    }

    /** How many times the work's chapter list was requested. */
    fun chapterListFetches(): Int = network.fetched.count { it == LIST_URL }
}

/** A [NovelNetwork] that serves the hand-written documents and records what was asked for. */
private class StubNetwork(private val pages: Map<String, String>) : NovelNetwork {

    val fetched = CopyOnWriteArrayList<String>()
    val byteFetches = CopyOnWriteArrayList<String>()

    /** A pause in [fetch], so two concurrent callers really overlap instead of running one at a time. */
    var fetchDelayMillis: Long = 0

    override suspend fun fetch(url: String, headers: Map<String, String>): String {
        fetched += url
        if (fetchDelayMillis > 0) delay(fetchDelayMillis)
        return pages[url] ?: throw IllegalStateException("No stubbed document for $url")
    }

    // The chapter body is a PDF; returning no bytes keeps PdfBox out of these tests, and the URL that
    // was requested is what the root-per-row assertions read.
    override suspend fun fetchBytes(url: String, headers: Map<String, String>): ByteArray {
        byteFetches += url
        return ByteArray(0)
    }
}
