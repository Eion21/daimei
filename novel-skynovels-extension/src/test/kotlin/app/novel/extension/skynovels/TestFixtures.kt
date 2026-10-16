package app.novel.extension.skynovels

/**
 * Captured excerpts of the real `https://api.skynovels.net/api` responses, in the measured shapes.
 *
 * They are kept as literals rather than resource files on purpose: the parser under test is pure
 * (String in, data out), so feeding it a String keeps the tests free of file access, which is stubbed
 * out in this fork's JVM unit tests.
 *
 * The JSON is embedded in raw strings so its escapes are the ones the API sends: `\n` is a JSON
 * newline escape and `\u200b`/`\u200c` are the zero-width watermark, decoded by [SkyNovelsJson] and
 * then removed by [SkyNovelsParsing.stripInvisible].
 */
internal object TestFixtures {

    const val API = "https://api.skynovels.net/api"

    val CATALOGUE_URL = "$API/novels?page=1"
    val LAST_CATALOGUE_URL = "$API/novels?page=35"
    val SEARCH_URL = "$API/novels?q=dragon&page=1"
    val BASE_URL = "$API/novels/866/base"
    val CHAPTER_LIST_URL = "$API/novel-chapters/866"
    val CHAPTER_URL = "$API/chapters/355958"

    /** A first catalogue page: `page` 1 of 35, so the host still has pages to ask for. */
    const val CATALOGUE = """
{"novels":[
{"id":866,"nvl_title":"El Dominio del Emperador","nvl_titlealternative":"Emperor's Domination","nvl_name":"-el-dominio-del-emperador","nvl_writer":"Yan Bi Xiao Sheng","nvl_content":"Sinopsis con <b>HTML</b> y acentos: corazón.","nvl_status":"Active","nvl_chapters":7190,"image":"abc123.webp"},
{"id":42,"nvl_title":"Mago Supremo","nvl_writer":"Autor Y","nvl_content":"Otra sinopsis","nvl_chapters":4034,"image":"def456.webp"}
],"page":1,"limit":20,"total":684,"totalPages":35}
"""

    /** The last catalogue page, so there is nothing after it. */
    const val LAST_CATALOGUE = """
{"novels":[{"id":7,"nvl_title":"Ultima Novela","nvl_writer":"Autor Z","nvl_content":"d","image":"last.webp"}],"page":35,"limit":20,"total":684,"totalPages":35}
"""

    /** A search result, paged the same way as the catalogue. */
    const val SEARCH = """
{"novels":[{"id":7,"nvl_title":"Dragon Slayer","nvl_writer":"Autor Q","nvl_content":"Un dragon","image":"d1.webp"}],"page":1,"limit":20,"total":51,"totalPages":3}
"""

    /** The base-detail response, wrapped under `novel` the way `/novel-chapters` wraps its array. */
    const val NOVEL_BASE = """
{"novel":[{"id":866,"nvl_title":"El Dominio del Emperador","nvl_writer":"Yan Bi Xiao Sheng","nvl_content":"Sinopsis completa en <em>detalle</em>.","image":"abc123.webp","nvl_name":"-el-dominio-del-emperador"}]}
"""

    const val GENRES = """{"genres":[{"id":1,"genre_name":"Acción"},{"id":2,"genre_name":"Fantasía"}]}"""

    /** A chapter list that agrees with its declared total: three chapters, `nvl_chapters` 3. */
    val CHAPTER_LIST = chapterList(declared = 3, ids = listOf(355958, 355959, 355960))

    /** A two-chapter list whose second chapter is VIP, to pin the label without dropping a chapter. */
    const val CHAPTER_LIST_WITH_VIP = """
{"novel":[{"id":866,"nvl_chapters":2,"chapters":[{"id":1,"chp_index_title":"Capítulo 1","chp_number":1,"isVip":"0"},{"id":2,"chp_index_title":"Capítulo 2","chp_number":2,"isVip":"1"}]}]}
"""

    /**
     * A `/api/novel-chapters/{id}` response. [declared] is the `nvl_chapters` the API announces;
     * passing null omits it entirely. [ids] are the global chapter ids, reused as their numbers.
     */
    fun chapterList(declared: Int?, ids: List<Int>): String {
        val chapters = ids.joinToString(",") { id ->
            """{"id":$id,"chp_index_title":"Capítulo $id","chp_name":"capitulo-$id",""" +
                """"chp_number":$id,"isVip":"0","chp_status":"0"}"""
        }
        val declaredField = if (declared == null) "" else """"nvl_chapters":$declared,"""
        return """{"novel":[{"id":866,"nvl_name":"-el-dominio-del-emperador",$declaredField"chapters":[$chapters]}]}"""
    }

    /** The zero-width watermark `\u200b`/`\u200c` seeded through a markdown chapter (H2, H3). */
    const val CHAPTER_MARKDOWN = """
{"chapter":{"chp_content":"## **Capítulo 1: El comienzo**\n\nH\u200bab\u200cía una vez un hombre singular.\nSu nombre era Ferda.\n---\nFin.","chp_number":1,"nvl_id":866,"isVip":"0"}}
"""

    /** An HTML chapter: inline tags, a break, a paragraph and named entities (H3). */
    const val CHAPTER_HTML = """
{"chapter":{"chp_content":"<strong>Capítulo 1186 - La batalla</strong><br>El cielo &amp; la tierra temblaron.<p>—No &iexcl;huyas!</p>","chp_number":1186,"nvl_id":866,"isVip":"0"}}
"""

    /** A chapter that carries both a markdown heading and an HTML tag (H3, mixed). */
    const val CHAPTER_MIXED = """
{"chapter":{"chp_content":"## **Capítulo 7**\n<strong>Un giro</strong> inesperado.","chp_number":7,"nvl_id":866,"isVip":"1"}}
"""

    /** A chapter whose whole payload is the watermark, so it must parse to no blocks. */
    const val CHAPTER_INVISIBLE_ONLY = """
{"chapter":{"chp_content":"\u200b\u200c\u200b","chp_number":1,"nvl_id":866,"isVip":"0"}}
"""

    /** A chapter carrying a markdown image and a horizontal rule. */
    const val CHAPTER_IMAGE_AND_RULE = """
{"chapter":{"chp_content":"![Portada](https://img.example/cover.png)\n---\nTexto final.","chp_number":2,"nvl_id":866,"isVip":"0"}}
"""

    /** The watermark as the raw characters, to prove the strip is not only a JSON-escape artifact. */
    const val ZERO_WIDTH_TEXT = "H\u200bel\u200clo\u200d,\u200c mundo\u200b."

    const val EMPTY = "{}"
}
