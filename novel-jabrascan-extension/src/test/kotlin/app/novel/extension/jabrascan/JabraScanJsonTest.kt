package app.novel.extension.jabrascan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The JSON reader on its own. The extension may not add a JSON library, so this reader is the
 * contract every other parser in the module leans on; it is tested apart from them so a failure here
 * is never mistaken for a failure of the format's shape.
 */
class JabraScanJsonTest {

    @Test
    fun `every JSON shape is read, nested ones included`() {
        val root = parsed(TestFixtures.JSON_DOCUMENT)

        val body = objectOf(root["object"])
        assertEquals(
            setOf("string", "number", "integer", "bool", "nothing", "array"),
            body.keys,
        )
        assertEquals("con \"comillas\" y ñ", JabraScanJson.asString(body["string"]))
        val number: Float = JabraScanJson.asFloat(body["number"])!!
        val integer: Float = JabraScanJson.asFloat(body["integer"])!!
        assertEquals(78.5f, number)
        assertEquals(49f, integer)
        assertEquals(true, body["bool"])
        assertNull(body["nothing"])
        assertEquals(emptyMap<String, Any?>(), JabraScanJson.asObject(root["empty"]))
        assertEquals(emptyList<Any?>(), JabraScanJson.asArray(root["emptyArray"]))

        val array = JabraScanJson.asArray(body["array"])!!
        assertEquals(5, array.size)
        assertEquals("uno", JabraScanJson.asString(array[0]))
        assertEquals(2f, JabraScanJson.asFloat(array[1]))
        assertEquals(false, array[2])
        assertNull(array[3])
        assertEquals("si", JabraScanJson.asString(objectOf(array[4])["anidado"]))
    }

    @Test
    fun `a number written as a string is a number only when one is asked for`() {
        val root = parsed("""{"n":"78.5","m":true}""")

        // The real `numCapitulo` is a string, which is why the reader accepts both spellings.
        assertEquals(78.5f, JabraScanJson.asFloat(root["n"]))
        assertNull(JabraScanJson.asFloat(root["m"]))
        assertNull(JabraScanJson.asFloat(null))
        assertNull(JabraScanJson.asString(78.5))
        assertNull(JabraScanJson.asObject(root["n"]))
        assertNull(JabraScanJson.asArray(root["n"]))
    }

    @Test
    fun `escapes are decoded the way JSON writes them`() {
        val root = parsed("""{"a":"salto\nlínea","b":"barra\/x","c":"\u0041"}""")

        assertEquals("salto\nlínea", JabraScanJson.asString(root["a"]))
        assertEquals("barra/x", JabraScanJson.asString(root["b"]))
        assertEquals("A", JabraScanJson.asString(root["c"]))
    }

    @Test
    fun `a truncated document fails naming the document and where it stopped`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            JabraScanJson.parse("""{"clave":""", "capitulos.json")
        }

        assertTrue(failure.message!!.contains("capitulos.json"), failure.message)
        assertTrue(failure.message!!.contains("offset"), failure.message)
    }

    @Test
    fun `content after the value fails instead of being ignored`() {
        assertThrows(IllegalStateException::class.java) { JabraScanJson.parse("""{} {}""", "capitulos.json") }
    }

    @Test
    fun `a document of the wrong shape reads as null, not as a crash`() {
        assertNull(JabraScanJson.asObject(JabraScanJson.parse("[]", "capitulos.json")))
        assertNull(JabraScanJson.asArray(JabraScanJson.parse("{}", "capitulos.json")))
    }

    private fun parsed(json: String): Map<String, Any?> =
        objectOf(JabraScanJson.parse(json, "capitulos.json"))

    private fun objectOf(value: Any?): Map<String, Any?> = JabraScanJson.asObject(value)!!
}
