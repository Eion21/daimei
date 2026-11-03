package app.novel.extension.jabrascan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The element scanner on its own: `obras.xml` is written by hand, so the tests pin down what the
 * scanner accepts (unknown elements, CDATA beside plain text, the stray `"` the real file carries) and
 * what it refuses (an element that is never closed, which is a truncated download).
 */
class JabraScanXmlTest {

    @Test
    fun `every body of an element is taken, in document order`() {
        val obras = JabraScanXml.elements(TestFixtures.CATALOGUE, "obra", "obras.xml")

        assertEquals(3, obras.size)
        assertTrue(obras[0].contains("<clave>TorreDeCristal</clave>"), obras[0])
        assertTrue(obras[1].contains("<clave>ObraOculta</clave>"), obras[1])
        assertTrue(obras[2].contains("<clave>ObraMinima</clave>"), obras[2])
    }

    @Test
    fun `the first body of an element is what element returns`() {
        val clave = JabraScanXml.element(TestFixtures.CATALOGUE, "clave", "obras.xml")!!

        assertEquals("TorreDeCristal", JabraScanXml.text(clave))
    }

    @Test
    fun `an element is never closed fails naming the document and the element`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            JabraScanXml.elements(TestFixtures.CATALOGUE_TRUNCATED, "obra", "obras.xml")
        }

        assertTrue(failure.message!!.contains("obras.xml"), failure.message)
        assertTrue(failure.message!!.contains("<obra>"), failure.message)
    }

    @Test
    fun `a document without the element parses to no body at all`() {
        assertTrue(JabraScanXml.elements(TestFixtures.NOT_A_CATALOGUE, "obra", "obras.xml").isEmpty())
        assertNull(JabraScanXml.element(TestFixtures.NOT_A_CATALOGUE, "obra", "obras.xml"))
    }

    @Test
    fun `text unwraps CDATA, drops markup and decodes the five entities`() {
        val body = JabraScanXml.element(TestFixtures.CATALOGUE, "sinopsis", "obras.xml")!!

        assertEquals("Una torre, un secreto. Blog\n\tY una segunda línea.", JabraScanXml.text(body))
    }

    @Test
    fun `text of a plain body is its content, entities included`() {
        val body = JabraScanXml.element(TestFixtures.CATALOGUE, "meta", "obras.xml")!!

        assertEquals("Textos & más textos.", JabraScanXml.text(body))
    }

    @Test
    fun `a lone less-than in the prose survives instead of eating up to the next greater-than`() {
        val text = JabraScanXml.text("<![CDATA[Puntúa <3 y comprueba que a < b, si c > d.]]>")

        assertEquals("Puntúa <3 y comprueba que a < b, si c > d.", text)
    }

    @Test
    fun `the intentional HTML link in a CDATA sinopsis is stripped as a tag`() {
        val text = JabraScanXml.text(
            "<![CDATA[Ver <a href=\"https://frizcosas.blogspot.com/2024/05/argonauta.html\" " +
                "target=\"_blank\">Turret Translations</a>]]>",
        )

        assertEquals("Ver Turret Translations", text)
    }

    @Test
    fun `a CDATA section that is never closed fails`() {
        assertThrows(IllegalStateException::class.java) { JabraScanXml.text("<![CDATA[sin cierre") }
    }

    @Test
    fun `tag-like comparisons and quoted attributes preserve complete synopsis prose`() {
        assertEquals(
            "Si a<b y c>d. Fin & más.",
            JabraScanXml.text("<![CDATA[Si a<b y c>d. <a title=\"x > y\" href='url'>Fin</a> &amp; más.]]>"),
        )
    }

    @Test
    fun `escaped tags stay literal and incomplete tags cannot consume prose`() {
        assertEquals(
            "<b>literal</b> y <em sin cierre",
            JabraScanXml.text("&lt;b&gt;literal&lt;/b&gt; y <em sin cierre"),
        )
    }
}
