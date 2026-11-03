package app.novel.extension.jabrascan

/** Conservative HTML tokenizer: only complete known tags with valid attributes are markup.
 * Quoted `>` never ends a tag, and an invalid candidate leaves every prose character intact.
 * Entities are decoded by the caller after tokenization, never reinterpreted as tags.
 */
internal object ProseHtml {
    data class Tag(val name: String, val closing: Boolean, val attributes: Map<String, String>)

    private val names = setOf(
        "a", "abbr", "b", "bdi", "bdo", "blockquote", "br", "caption", "cite", "code", "col",
        "colgroup", "dd", "del", "details", "dfn", "div", "dl", "dt", "em", "figcaption", "figure",
        "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "img", "ins", "kbd", "li", "mark",
        "ol", "p", "pre", "q", "rp", "rt", "ruby", "s", "samp", "section", "small", "span",
        "strike", "strong", "sub", "summary", "sup", "table", "tbody", "td", "tfoot", "th",
        "thead", "tr", "tt", "u", "ul", "var", "wbr", "article", "font", "center",
    )
    private val booleanAttributes = setOf("hidden", "disabled", "checked", "selected", "reversed", "open")

    fun convert(text: String, render: (Tag) -> String): String = buildString {
        var cursor = 0
        while (cursor < text.length) {
            val tag = if (text[cursor] == '<') readTag(text, cursor) else null
            if (tag == null) {
                append(text[cursor++])
            } else {
                append(render(tag.first))
                cursor = tag.second
            }
        }
    }

    private fun readTag(text: String, start: Int): Pair<Tag, Int>? {
        var cursor = start + 1
        val closing = text.getOrNull(cursor) == '/'
        if (closing) cursor++
        val nameStart = cursor
        while (text.getOrNull(cursor)?.isLetterOrDigit() == true) cursor++
        val name = text.substring(nameStart, cursor).lowercase()
        if (name !in names) return null
        val attributes = linkedMapOf<String, String>()
        while (cursor < text.length) {
            val beforeSpace = cursor
            while (text.getOrNull(cursor)?.isWhitespace() == true) cursor++
            if (text.getOrNull(cursor) == '>') return Tag(name, closing, attributes) to cursor + 1
            if (!closing && text.startsWith("/>", cursor)) return Tag(name, false, attributes) to cursor + 2
            if (closing || cursor == beforeSpace) return null
            val attributeStart = cursor
            while (text.getOrNull(cursor)?.let { it.isLetterOrDigit() || it in "-_:" } == true) cursor++
            if (attributeStart == cursor) return null
            val attribute = text.substring(attributeStart, cursor).lowercase()
            val afterName = cursor
            while (text.getOrNull(cursor)?.isWhitespace() == true) cursor++
            if (text.getOrNull(cursor) != '=') {
                if (attribute !in booleanAttributes) return null
                attributes[attribute] = ""
                cursor = afterName
                continue
            }
            cursor++
            while (text.getOrNull(cursor)?.isWhitespace() == true) cursor++
            val quote = text.getOrNull(cursor)
            val value: String
            if (quote == '\'' || quote == '"') {
                cursor++
                val valueStart = cursor
                while (cursor < text.length && text[cursor] != quote) cursor++
                if (cursor >= text.length) return null
                value = text.substring(valueStart, cursor++)
            } else {
                val valueStart = cursor
                while (cursor < text.length && !text[cursor].isWhitespace() && text[cursor] != '>') {
                    if (text[cursor] in "<\"'`=") return null
                    cursor++
                }
                if (cursor == valueStart) return null
                value = text.substring(valueStart, cursor)
            }
            attributes[attribute] = value
        }
        return null
    }
}
