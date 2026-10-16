package eu.kanade.tachiyomi.data.download

import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * That the plain text the downloader writes is the plain text the reader can read back.
 *
 * The format has exactly one owner — [toPlainText] and [toChapterContent], declared side by side in
 * `Downloader.kt` — so these tests are about that pair agreeing, not about either half in isolation. A parser that
 * drifted from the writer would leave every downloaded chapter on a device unreadable with the network off, which is
 * the failure this whole phase exists to remove, and it would be invisible to every other test: the writer would
 * still pass, the reader would still pass, and only the file on disk would be wrong.
 *
 * The shape of the format is deliberately small — blank-line separated blocks, `#` for a heading, `---` for a
 * divider, `![alt](url)` for an image — and [toChapterContent] is held to inventing nothing beyond it. That is
 * checked here from both sides: the round trip proves the writer's own output parses back, and the literal-text
 * tests prove the parser reads exactly the tokens the writer emits rather than a richer dialect that would only
 * ever exist on paper.
 */
class NovelChapterPlainTextTest {

    // ---------------------------------------------------------------------------------------------
    // The round trip, over every kind of block the writer emits.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a chapter survives being written to text and read back`() {
        val content = ChapterContent(
            blocks = listOf(
                ContentBlock.Heading("Prólogo", level = 1),
                ContentBlock.Paragraph("El edificio llevaba sesenta anos esperando a que alguien subiera."),
                ContentBlock.Divider,
                ContentBlock.Image("https://example.invalid/portada.jpg", "La portada"),
                ContentBlock.Paragraph("Segunda escena."),
                ContentBlock.Heading("Parte dos", level = 2),
                ContentBlock.Image("https://example.invalid/sin-alt.jpg"),
                ContentBlock.Divider,
            ),
        )

        assertEquals(content, content.toPlainText().toChapterContent())
    }

    @Test
    fun `every kind of block survives on its own`() {
        // One at a time as well, because a round trip over a list can pass with one block kind silently folded
        // into another: a divider rendered as a paragraph still parses back as *something*, and only an exact
        // comparison of the block list says whether it is the right something.
        val blocks = listOf(
            ContentBlock.Paragraph("Solo un parrafo."),
            ContentBlock.Divider,
            ContentBlock.Image("https://example.invalid/a.jpg", "Con alt"),
            ContentBlock.Image("https://example.invalid/b.jpg"),
        )

        blocks.forEach { block ->
            val content = ChapterContent(listOf(block))
            assertEquals(content, content.toPlainText().toChapterContent(), "$block did not survive the round trip")
        }
    }

    @Test
    fun `every heading level the writer spells out survives`() {
        (1..6).forEach { level ->
            val content = ChapterContent(listOf(ContentBlock.Heading("Nivel $level", level = level)))
            assertEquals(
                content,
                content.toPlainText().toChapterContent(),
                "a heading at level $level did not survive the round trip",
            )
        }
    }

    @Test
    fun `an image address with parentheses in it survives`() {
        // The address is written verbatim, so the parser has to split on the last `](` rather than the first. An
        // image url carrying its own parenthesis is ordinary enough — query strings, wiki links — and a parser that
        // split on the first one would quietly truncate it.
        val content = ChapterContent(
            listOf(ContentBlock.Image("https://example.invalid/img(1).jpg", "Alt (con parentesis)")),
        )

        assertEquals(content, content.toPlainText().toChapterContent())
    }

    @Test
    fun `a paragraph with a single newline inside it survives`() {
        // A source that returns a paragraph wrapped across lines writes those lines verbatim, with no blank line
        // between them, so they are still one block. Reading them back as two paragraphs would silently halve the
        // chapter's block count and shift every reading position past it.
        val content = ChapterContent(listOf(ContentBlock.Paragraph("Primera linea\nsegunda linea\ntercera")))

        assertEquals(content, content.toPlainText().toChapterContent())
    }

    @Test
    fun `a heading whose text carries newlines survives`() {
        val content = ChapterContent(listOf(ContentBlock.Heading("Uno\ndos", level = 3)))

        assertEquals(content, content.toPlainText().toChapterContent())
    }

    // ---------------------------------------------------------------------------------------------
    // The tokens, read literally, so the parser is held to the format and not to a richer one.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the tokens the writer writes are read back as the blocks they name`() {
        assertEquals(
            ChapterContent(
                listOf(
                    ContentBlock.Heading("Prólogo", level = 1),
                    ContentBlock.Heading("Parte dos", level = 2),
                    ContentBlock.Divider,
                    ContentBlock.Image("https://example.invalid/a.jpg", "Portada"),
                    ContentBlock.Paragraph("Un parrafo."),
                ),
            ),
            (
                "# Prólogo\n\n" +
                    "## Parte dos\n\n" +
                    "---\n\n" +
                    "![Portada](https://example.invalid/a.jpg)\n\n" +
                    "Un parrafo."
                ).toChapterContent(),
        )
    }

    @Test
    fun `an image with no alt reads back with no alt`() {
        // The writer spells "no alt" and "empty alt" the same way, so the parser answers with the state the reader
        // has an actual rendering for rather than inventing an empty string nobody asked for.
        assertEquals(
            listOf(ContentBlock.Image("https://example.invalid/a.jpg", alt = null)),
            "![](https://example.invalid/a.jpg)".toChapterContent()?.blocks,
        )
    }

    @Test
    fun `a line that starts with a hash but is not a heading is still a paragraph`() {
        // Novels talk about issue numbers and dates. Only hashes *and* a space make a heading, which is what the
        // writer emits and therefore what the parser has to accept.
        assertEquals(
            listOf(ContentBlock.Paragraph("#1, primero")),
            "#1, primero".toChapterContent()?.blocks,
        )
        assertEquals(
            listOf(ContentBlock.Paragraph("####### siete hashes, uno mas de los que el formato escribe")),
            "####### siete hashes, uno mas de los que el formato escribe".toChapterContent()?.blocks,
        )
    }

    @Test
    fun `surrounding blank lines do not become blocks`() {
        assertEquals(
            listOf(ContentBlock.Paragraph("Un parrafo.")),
            "\n\n\nUn parrafo.\n\n\n".toChapterContent()?.blocks,
        )
    }

    @Test
    fun `a file with nothing readable in it is no chapter at all`() {
        // Null rather than an empty `ChapterContent`: an empty chapter is a chapter nobody could read, and the
        // reader treats that as a failure of its own. Returning one here would put a blank page on screen instead.
        assertNull("".toChapterContent())
        assertNull("   \n\n \t \n".toChapterContent())
    }

    // ---------------------------------------------------------------------------------------------
    // The two shapes the format cannot carry, pinned so nobody discovers them on a device.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a heading deeper than six hashes comes back at six`() {
        // The writer stops spelling a heading out past six, where the hashes stop carrying meaning, so seven is
        // not something the format can express. Clamping is the documented behaviour rather than a round trip that
        // quietly changes the chapter under the reader.
        val deep = ChapterContent(listOf(ContentBlock.Heading("Profundo", level = 9)))

        assertEquals(
            ChapterContent(listOf(ContentBlock.Heading("Profundo", level = 6))),
            deep.toPlainText().toChapterContent(),
        )
    }

    @Test
    fun `a paragraph that is exactly three dashes comes back as a divider`() {
        // A plain text file cannot say "this line is prose", and escaping it would be new syntax in a format that
        // already has files written to it on real devices. Recorded here so the limit is a known property of the
        // format instead of a surprise found by a user.
        assertEquals(
            listOf(ContentBlock.Divider),
            "---".toChapterContent()?.blocks,
        )
    }
}
