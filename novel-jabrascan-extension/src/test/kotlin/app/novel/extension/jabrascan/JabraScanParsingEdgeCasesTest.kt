package app.novel.extension.jabrascan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * LEFTOVER NAME, real tests — read this before keeping the file.
 *
 * This path held the one-off reality check that read the three real JabraScan samples from disk and checked the
 * numbers the parsers produced against the synthetic fixtures (48 visible obras, 49 index entries, 321 Arifureta
 * chapters, and their titles, numbers and cover paths). It ran, printed its findings, and was reverted in place
 * instead of deleted, because this sandbox forbids removing files — and a ktlint rule forbids an empty one.
 *
 * What is left are three catalogue edges the synthetic fixtures do not otherwise reach. They belong in
 * `JabraScanParsingTest`, next to the rest of the catalogue's cases; move them there and delete this file.
 */
class JabraScanParsingEdgeCasesTest {

    @Test
    fun `a visible written in another case still shows the obra`() {
        val page = JabraScanParsing.parseCatalogue(
            """
            <obras>
            <obra>
            <visible>SI</visible>
            <clave>TorreDeCristal</clave>
            <nombreobra>La torre de cristal</nombreobra>
            </obra>
            </obras>
            """,
        )

        assertEquals(listOf("TorreDeCristal"), page.novels.map { it.id })
    }

    @Test
    fun `an empty sinopsis falls back to the one-line meta`() {
        val page = JabraScanParsing.parseCatalogue(
            """
            <obras>
            <obra>
            <visible>si</visible>
            <clave>TorreDeCristal</clave>
            <nombreobra>La torre de cristal</nombreobra>
            <sinopsis></sinopsis>
            <meta>Una torre y un secreto.</meta>
            </obra>
            </obras>
            """,
        )

        assertEquals("Una torre y un secreto.", page.novels.single().description)
    }

    @Test
    fun `a clave padded with newlines still addresses the obra`() {
        val page = JabraScanParsing.parseCatalogue(
            """
            <obras>
            <obra>
            <visible>si</visible>
            <clave>
                TorreDeCristal
            </clave>
            <nombreobra>La torre de cristal</nombreobra>
            </obra>
            </obras>
            """,
        )

        assertEquals("TorreDeCristal", page.novels.single().id)
        assertNull(page.novels.single().author)
    }
}
