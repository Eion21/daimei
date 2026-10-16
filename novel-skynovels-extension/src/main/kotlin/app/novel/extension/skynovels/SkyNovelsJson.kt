package app.novel.extension.skynovels

/**
 * A minimal, hand-written JSON reader for the SkyNovels REST API.
 *
 * The extension bundles no JSON library on purpose. The host resolves a class from the APK's own dex
 * before falling back to its own, so a bundled parser is weight it does not need, and the API
 * responses are small and well formed. A recursive-descent scan over the raw text is enough to turn a
 * response into Kotlin's own `Map`/`List`/`String`/`Double`/`Boolean`/null, which the extensions below
 * read.
 *
 * This is not a general-purpose JSON library: it accepts exactly the subset the API serves (objects,
 * arrays, strings with escapes, numbers, booleans and null) and reports anything else as a failure
 * rather than guessing at it.
 */
internal object SkyNovelsJson {

    /** Parses [text] into `Map<String, Any?>`, `List<Any?>`, `String`, `Double`, `Boolean` or null. */
    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        return if (parser.atEnd()) null else parser.readValue()
    }

    @Suppress("UNCHECKED_CAST")
    fun asObject(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun asArray(value: Any?): List<Any?>? = value as? List<Any?>

    fun asString(value: Any?): String? = value as? String

    /** An integer field, tolerating both a JSON number and a numeric string (`"1"`). */
    fun asInt(value: Any?): Int? = when (value) {
        is Double -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    /** A decimal field, tolerating both a JSON number and a numeric string. */
    fun asFloat(value: Any?): Float? = when (value) {
        is Double -> value.toFloat()
        is String -> value.trim().toFloatOrNull()
        else -> null
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (atEnd()) return null
            val current = text[index]
            return when {
                current == '{' -> readObject()
                current == '[' -> readArray()
                current == '"' -> readString()
                current == 't' || current == 'f' || current == 'n' -> readKeyword()
                current == '-' || current.isDigit() -> readNumber()
                else -> error("Unexpected character '$current' at $index")
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
                if (peek() != '"') error("Expected an object key at $index")
                val key = readString()
                skipWhitespace()
                if (peek() != ':') error("Expected ':' at $index")
                index++
                members[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return members
                    }
                    else -> error("Expected ',' or '}' at $index")
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
                items += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return items
                    }
                    else -> error("Expected ',' or ']' at $index")
                }
            }
        }

        private fun readString(): String {
            if (peek() != '"') error("Expected a string at $index")
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
                        if (index >= text.length) error("Unterminated escape at $index")
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
                                if (index + 4 >= text.length) error("Truncated \\u escape at $index")
                                val code = text.substring(index + 1, index + 5).toIntOrNull(16)
                                    ?: error("Invalid \\u escape at $index")
                                out.append(code.toChar())
                                index += 5
                            }
                            else -> error("Unknown escape '\\$escape' at $index")
                        }
                    }
                }
            }
            error("Unterminated string at $index")
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
            else -> error("Unexpected token at $index")
        }

        private fun readNumber(): Double {
            val start = index
            if (peek() == '-' || peek() == '+') index++
            while (index < text.length && isNumberChar(text[index])) index++
            val literal = text.substring(start, index)
            return literal.toDoubleOrNull() ?: error("Invalid number '$literal' at $start")
        }

        private fun isNumberChar(c: Char): Boolean =
            c.isDigit() || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-'

        private fun peek(): Char? = if (index < text.length) text[index] else null
    }
}
