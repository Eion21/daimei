package app.novel.extension.matchanovels

/** Strict JSON decoding local to the Matcha flight payload; no types cross the source API. */
internal object MatchaNovelsJson {
    fun read(text: String, start: Int = 0): Any? = Parser(text, start).readValue()

    private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    private class Parser(private val text: String, private var index: Int) {

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (atEnd()) error("MatchaNovels: incomplete JSON value")
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
                check(!members.containsKey(key)) { "MatchaNovels: duplicate JSON key $key" }
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
                        check(current >= ' ') { "MatchaNovels: control character in JSON string" }
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
            check(NUMBER.matches(literal)) { "MatchaNovels: invalid number at $start" }
            return literal.toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: error("MatchaNovels: invalid number at $start")
        }

        private fun isNumberChar(c: Char): Boolean =
            c.isDigit() || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-'

        private fun peek(): Char? = if (index < text.length) text[index] else null
    }
}
