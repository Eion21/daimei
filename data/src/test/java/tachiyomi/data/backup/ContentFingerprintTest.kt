package tachiyomi.data.backup

import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ContentFingerprintTest {
    @Test
    fun `empty block list has a fixed v1 digest`() {
        val content = ChapterContent(emptyList())
        assertArrayEquals("[]".toByteArray(Charsets.UTF_8), ContentFingerprint.canonicalBytes(content))
        assertEquals("v1:sha256:4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", ContentFingerprint.of(content))
    }

    @Test
    fun `encoding fixes subtype fields defaults escaping unicode and order`() {
        val content = ChapterContent(listOf(
            ContentBlock.Paragraph("é \"x\"\n"),
            ContentBlock.Heading("heading"),
            ContentBlock.Heading("other", 3),
            ContentBlock.Image("url"),
            ContentBlock.Image("u", ""),
            ContentBlock.Divider,
        ))
        val expected = """[{"type":"app.novel.source.api.ContentBlock.Paragraph","text":"é \"x\"\n"},{"type":"app.novel.source.api.ContentBlock.Heading","text":"heading"},{"type":"app.novel.source.api.ContentBlock.Heading","text":"other","level":3},{"type":"app.novel.source.api.ContentBlock.Image","url":"url"},{"type":"app.novel.source.api.ContentBlock.Image","url":"u","alt":""},{"type":"app.novel.source.api.ContentBlock.Divider"}]"""
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), ContentFingerprint.canonicalBytes(content))
        assertEquals(410, expected.toByteArray(Charsets.UTF_8).size)
        assertEquals("v1:sha256:2305bc64bbca71a6fdddf8185e22b128bd693fc486abc425484210cbc9982949", ContentFingerprint.of(content))
    }
}
