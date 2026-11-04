package app.novel.extension.jabrascan

import app.novel.source.api.Chapter
import app.novel.source.api.Novel
import app.novel.source.api.NovelNetwork
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Synthetic documents and an intercepting network: these tests never fetch or extract a PDF. */
class JabraScanRoutingRegressionTest {
    private val work = "TorreDeCristal"
    private val files = listOf("uno.pdf", "Señora, por favor.pdf", "local.pdf", "rara.pdf")
    private val indexUrl = "https://raw.githubusercontent.com/JabraScan/JabraScan.github.io/main/capitulos.json"
    private val listUrl = "https://raw.githubusercontent.com/JabraScan/JabraScan.github.io/main/books/$work.json"
    private val oldListUrl =
        "https://raw.githubusercontent.com/JabraScan/JabraScan.github.io-pdfs/main/books/$work.json"
    private val index = """{"$work":"books/$work.json"}"""
    private val list = """
{"$work":[
{"NombreArchivo":"${files[0]}","numCapitulo":"1","nombreCapitulo":"Uno"},
{"NombreArchivo":"${files[1]}","numCapitulo":"1031","nombreCapitulo":"Dos","server":"io-pdfs"},
{"NombreArchivo":"${files[2]}","numCapitulo":"2","nombreCapitulo":"Local","server":"local"},
{"NombreArchivo":"${files[3]}","numCapitulo":"3","nombreCapitulo":"Raro","server":"unknown"}
]}
"""

    @Test
    fun `chapter list uses content repository and preserves every identity and input`() = runBlocking {
        val network = RoutingNetwork(mapOf(indexUrl to index, listUrl to list))
        val source = source(network)
        val novel = Novel("jabrascan", work, "Título original")
        val original = Novel("jabrascan", work, "Título original")

        val chapters = source.getChapters(novel)

        assertEquals(listOf(indexUrl, listUrl), network.textRequests)
        assertEquals(files.map { "$work/$it" }, chapters.map { it.id })
        assertEquals(files, chapters.map { it.url })
        assertEquals(listOf(1f, 1031f, 2f, 3f), chapters.map { it.number })
        assertEquals(listOf("Uno", "Dos", "Local", "Raro"), chapters.map { it.title })
        assertTrue(chapters.all { it.novelId == work && it.sourceId == "jabrascan" })
        assertEquals(original, novel)
    }

    @Test
    fun `fresh download refetches metadata from content repository`() {
        // Both roots are stubbed so the byte intercept is reached even before the metadata fix.
        val network = networkForDownload()
        val chapter = chapter(0, 1f)
        assertThrows(PdfIntercept::class.java) { runBlocking { source(network).getChapterContent(chapter) } }
        assertEquals(listOf(listUrl), network.textRequests)
        assertEquals(chapter(0, 1f), chapter)
    }

    @Test
    fun `absent server requests pdf from verified host without books prefix`() {
        val network = networkForDownload()
        assertThrows(PdfIntercept::class.java) { runBlocking { source(network).getChapterContent(chapter(0, 1f)) } }
        assertEquals(listOf("https://books.jabrascan.net/$work/uno.pdf"), network.byteRequests)
    }

    @Test
    fun `io-pdfs requests folded and UTF-8 encoded pdf from verified host`() {
        val network = networkForDownload()
        assertThrows(PdfIntercept::class.java) { runBlocking { source(network).getChapterContent(chapter(1, 1031f)) } }
        assertEquals(
            listOf("https://books.jabrascan.net/${work}1/Se%C3%B1ora%2C%20por%20favor.pdf"),
            network.byteRequests,
        )
    }

    @Test
    fun `local server keeps site books route`() {
        val network = networkForDownload()
        assertThrows(PdfIntercept::class.java) { runBlocking { source(network).getChapterContent(chapter(2, 2f)) } }
        assertEquals(listOf("https://jabrascan.net/books/$work/local.pdf"), network.byteRequests)
    }

    @Test
    fun `unknown server remains listed and fails closed before pdf request`() {
        val network = networkForDownload()
        val source = source(network)
        val chapters = runBlocking { source.getChapters(Novel("jabrascan", work, "Título")) }
        assertEquals(files.map { "$work/$it" }, chapters.map { it.id })
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { source.getChapterContent(chapters.last()) }
        }
        assertTrue(error.message!!.contains("unknown"))
        assertTrue(error.message!!.contains(work))
        assertTrue(network.byteRequests.isEmpty())
    }

    private fun networkForDownload() = RoutingNetwork(mapOf(indexUrl to index, listUrl to list, oldListUrl to list))

    private fun source(network: NovelNetwork) = JabraScanSource().apply { bindNetwork(network) }

    private fun chapter(index: Int, number: Float) = Chapter(
        sourceId = "jabrascan",
        novelId = work,
        id = "$work/${files[index]}",
        title = "Capítulo",
        number = number,
        url = files[index],
    )
}

private class PdfIntercept : RuntimeException()

private class RoutingNetwork(private val pages: Map<String, String>) : NovelNetwork {
    val textRequests = mutableListOf<String>()
    val byteRequests = mutableListOf<String>()

    override suspend fun fetch(url: String, headers: Map<String, String>): String {
        textRequests += url
        return pages[url] ?: throw IllegalStateException("No synthetic document for $url")
    }

    override suspend fun fetchBytes(url: String, headers: Map<String, String>): ByteArray {
        byteRequests += url
        throw PdfIntercept()
    }
}
