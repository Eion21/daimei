package app.novel.extension.jabrascan

/**
 * A minimal, hand-written JSON reader for JabraScan's two JSON documents (`capitulos.json` and each
 * `books/{clave}.json`).
 *
 * The extension bundles no JSON library on purpose — the host resolves a class from the APK's own dex
 * before falling back to its own, so a bundled parser is weight it does not need — and the files are
 * small and well formed. A recursive-descent scan over the raw text turns them into Kotlin's own
 * `Map`/`List`/`String`/`Double`/`Boolean`/null, which [JabraScanParsing] reads.
 *
 * This is not a general-purpose JSON library. It accepts the subset these files use (objects, arrays,
 * strings with escapes, numbers, booleans and null) and refuses everything else — including content
 * after the value, which a cut-off download often produces — rather than guessing at it. Every refusal
 * names the [document] it happened in, because the two JSON files fail the same way.
 */
internal object JabraScanJson {

    /** Parses [text] into `Map<String, Any?>`, `List<Any?>`, `String`, `Double`, `Boolean` or null. */
    fun parse(text: String, document: String): Any? = Parser(text, document).readDocument()

    @Suppress("UNCHECKED_CAST")
    fun asObject(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun asArray(value: Any?): List<Any?>? = value as? List<Any?>

    fun asString(value: Any?): String? = value as? String

    /**
     * A decimal field, tolerating both a JSON number and a numeric string: `numCapitulo` is written as a
     * string (`"0"`, `"1.1"`, `"78"`) in every measured `books/{clave}.json`.
     */
    fun asFloat(value: Any?): Float? = when (value) {
        is Double -> value.toFloat()
        is String -> value.trim().toFloatOrNull()
        else -> null
    }

    private class Parser(private val text: String, private val document: String) {
        private var index = 0

        /** Reads the document's single value and insists that nothing follows it. */
        fun readDocument(): Any? {
            skipWhitespace()
            if (atEnd()) return null
            val value = readAny()
            skipWhitespace()
            if (!atEnd()) fail("unexpected content after the document's value")
            return value
        }

        private fun atEnd(): Boolean = index >= text.length

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun readAny(): Any? {
            skipWhitespace()
            if (atEnd()) fail("the document ends where a value was expected")
            val current = text[index]
            return when {
                current == '{' -> readObject()
                current == '[' -> readArray()
                current == '"' -> readString()
                current == 't' || current == 'f' || current == 'n' -> readKeyword()
                current == '-' || current.isDigit() -> readNumber()
                else -> fail("unexpected character '$current'")
            }
        }

        private fun readObject(): Map<String, Any?> {
            val members = LinkedHashMap<String, Any?>()
            index++ // consume '{'
            skipWhitespace()
            if (peek() == '}') {
                index++
                return members
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected an object key")
                val key = readString()
                skipWhitespace()
                if (peek() != ':') fail("expected ':'")
                index++
                members[key] = readAny()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return members
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val items = ArrayList<Any?>()
            index++ // consume '['
            skipWhitespace()
            if (peek() == ']') {
                index++
                return items
            }
            while (true) {
                items += readAny()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return items
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            if (peek() != '"') fail("expected a string")
            index++
            val out = StringBuilder()
            while (index < text.length) {
                val current = text[index]
                when {
                    current == '"' -> {
                        index++
                        return out.toString()
                    }
                    current != '\\' -> {
                        out.append(current)
                        index++
                    }
                    else -> {
                        index++
                        if (atEnd()) fail("unterminated escape")
                        when (val escape = text[index]) {
                            '"', '\\', '/' -> {
                                out.append(escape)
                                index++
                            }
                            'b' -> {
                                out.append('\b')
                                index++
                            }
                            'f' -> {
                                out.append('\u000C')
                                index++
                            }
                            'n' -> {
                                out.append('\n')
                                index++
                            }
                            'r' -> {
                                out.append('\r')
                                index++
                            }
                            't' -> {
                                out.append('\t')
                                index++
                            }
                            'u' -> {
                                if (index + 4 >= text.length) fail("truncated \\u escape")
                                val code = text.substring(index + 1, index + 5).toIntOrNull(16)
                                    ?: fail("invalid \\u escape")
                                out.append(code.toChar())
                                index += 5
                            }
                            else -> fail("unknown escape '\\$escape'")
                        }
                    }
                }
            }
            fail("unterminated string")
        }

        private fun readKeyword(): Any? = when {
            text.startsWith("true", index) -> {
                index += 4
                true
            }
            text.startsWith("false", index) -> {
                index += 5
                false
            }
            text.startsWith("null", index) -> {
                index += 4
                null
            }
            else -> fail("unexpected token")
        }

        private fun readNumber(): Double {
            val start = index
            if (peek() == '-' || peek() == '+') index++
            while (index < text.length && isNumberChar(text[index])) index++
            val literal = text.substring(start, index)
            return literal.toDoubleOrNull() ?: error("$document: invalid number '$literal' at offset $start")
        }

        private fun isNumberChar(c: Char): Boolean =
            c.isDigit() || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-'

        private fun peek(): Char? = if (index < text.length) text[index] else null

        private fun fail(message: String): Nothing = error("$document: $message at offset $index")
    }
}
