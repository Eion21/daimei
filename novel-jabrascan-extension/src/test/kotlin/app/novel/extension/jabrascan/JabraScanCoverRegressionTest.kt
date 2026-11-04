package app.novel.extension.jabrascan

import app.novel.source.api.Novel
import app.novel.source.api.NovelNetwork
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Drives the public source with synthetic catalogue data; no image or catalogue HTTP is performed. */
class JabraScanCoverRegressionTest {
    private val catalogueUrl = JabraScanUrls.CONTENT_REPOSITORY + "/obras.xml"
    private val normal = "IBecameMarriedMan/IBecameMarriedMan01.webp"
    private val normalUrl = "https://img.jabrascan.net/IBecameMarriedMan/IBecameMarriedMan01-225w.webp"

    @Test
    fun `popular returns responsive cover without changing novel metadata`() = runBlocking {
        val page = source(normal).popular(1)
        assertFalse(page.hasNextPage)
        assertEquals(expected(normalUrl), page.novels.single())
    }

    @Test
    fun `search returns responsive cover without changing novel metadata`() = runBlocking {
        val page = source(normal).search("torre", 1)
        assertFalse(page.hasNextPage)
        assertEquals(expected(normalUrl), page.novels.single())
    }

    @Test
    fun `details returns responsive cover without changing identity or other metadata`() = runBlocking {
        val input = Novel("jabrascan", "Torre", "Provisional")
        assertEquals(expected(normalUrl), source(normal).getNovelDetails(input))
        assertEquals(Novel("jabrascan", "Torre", "Provisional"), input)
    }

    @Test
    fun `nested measured path encodes spaces per segment`() = runBlocking {
        val path = "Mis3EsposasSonHermosasVampiros/063-Helena Elderblood Gremory/V11 text.webp"
        val url = "https://img.jabrascan.net/Mis3EsposasSonHermosasVampiros/" +
            "063-Helena%20Elderblood%20Gremory/V11%20text-225w.webp"
        assertEquals(expected(url), source(path).popular(1).novels.single())
    }

    @Test
    fun `synthetic accented path encodes utf8 per segment`() = runBlocking {
        val url = "https://img.jabrascan.net/Se%C3%B1ora%20azul/cap%C3%ADtulo-225w.webp"
        assertEquals(expected(url), source("Señora azul/capítulo.jpg").popular(1).novels.single())
    }

    @Test
    fun `absolute https cover passes through unchanged`() = runBlocking {
        val url = "https://example.test/cover.webp"
        assertEquals(expected(url), source(url).popular(1).novels.single())
        assertEquals(expected(url), source(url).getNovelDetails(Novel("jabrascan", "Torre", "Old")))
    }

    @Test
    fun `blank cover stays null and details retain existing cover`() = runBlocking {
        val source = source("   ")
        assertNull(source.popular(1).novels.single().coverUrl)
        val old = Novel("jabrascan", "Torre", "Old", coverUrl = "https://example.test/old.webp")
        assertEquals(expected(old.coverUrl), source.getNovelDetails(old))
    }

    @Test
    fun `missing cover retains existing cover in details`() = runBlocking {
        val old = Novel("jabrascan", "Torre", "Old", coverUrl = "https://example.test/old.webp")
        assertNull(source(null).popular(1).novels.single().coverUrl)
        assertEquals(expected(old.coverUrl), source(null).getNovelDetails(old))
    }

    private fun expected(cover: String?) = Novel(
        "jabrascan",
        "Torre",
        "La torre de cristal",
        "Autor Uno",
        "Una torre y un secreto.",
        cover,
    )

    private fun source(cover: String?): JabraScanSource {
        val image = cover?.let { "<imagen>$it</imagen>" }.orEmpty()
        val xml = """
            <obras><obra><visible>si</visible><clave>Torre</clave>
            <nombreobra>La torre de cristal</nombreobra><autor>Autor Uno</autor>
            <sinopsis>Una torre y un secreto.</sinopsis>$image</obra></obras>
        """.trimIndent()
        return JabraScanSource().apply { bindNetwork(CoverNetwork(mapOf(catalogueUrl to xml))) }
    }
}

private class CoverNetwork(private val pages: Map<String, String>) : NovelNetwork {
    override suspend fun fetch(url: String, headers: Map<String, String>): String =
        pages[url] ?: error("No synthetic document for $url")

    override suspend fun fetchBytes(url: String, headers: Map<String, String>): ByteArray =
        error("Unexpected binary request: $url")
}
