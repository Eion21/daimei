package app.novel.extension.matchanovels

/**
 * Trimmed excerpts of the real pages served by `https://matchanovels.com/`, captured for the tests.
 *
 * They are kept as literals rather than resource files on purpose: the parser under test is pure
 * (String in, data out), so feeding it a String keeps the tests free of file access, which is
 * stubbed out in this fork's JVM unit tests.
 *
 * The shapes are the measured ones: `article[data-mn-tarjeta]` cards whose cover points at
 * `/_next/image?url=...`, a `Book` JSON-LD block beside a `BreadcrumbList`, chapter anchors that
 * carry `data-bloqueado`/`data-leido` (ignored in lists; access is determined by the chapter page),
 * and chapter prose in `<p>` next to header/footer chrome.
 */
internal object TestPages {

    const val SITE = "https://matchanovels.com"

    /** Three catalogue cards: one `_next/image` cover, one with no image at all, one with a plain url. */
    const val CATALOGUE = """<!doctype html><html><body><div data-mn-catalogo="1">
<article data-mn-tarjeta="1" style="animation-delay:0ms"><a aria-label="I Married The Dragon I Killed" draggable="false" data-mn-tarjeta-portada="1" href="/novela/I-Married-The-Dragon-I-Killed"><div style="position:relative"><img alt="I Married The Dragon I Killed" loading="eager" data-nimg="fill" srcSet="/_next/image?url=https%3A%2F%2Fmatchanovels.com%2Fstatic%2Fimagenes-portadas%2Fportada-I-Married-The-Dragon-I-Killed.webp%3Fv%3D1784448982&amp;w=384&amp;q=75 384w" src="/_next/image?url=https%3A%2F%2Fmatchanovels.com%2Fstatic%2Fimagenes-portadas%2Fportada-I-Married-The-Dragon-I-Killed.webp%3Fv%3D1784448982&amp;w=3840&amp;q=75"/></div></a><div data-mn-tarjeta-pie="1"><a data-mn-tarjeta-titulo="1" href="/novela/I-Married-The-Dragon-I-Killed">I Married The Dragon I Killed</a><div data-mn-tarjeta-fila="1"><span>Finalizada</span></div></div></article>
<article data-mn-tarjeta="1" style="animation-delay:0ms"><a aria-label="Corazón de Tinta" draggable="false" href="/novela/Corazon-de-Tinta"><div style="position:relative"></div></a><div data-mn-tarjeta-pie="1"><a href="/novela/Corazon-de-Tinta">Corazón de Tinta</a></div></article>
<article data-mn-tarjeta="1" style="animation-delay:0ms"><a aria-label="La Dama del Norte" draggable="false" href="/novela/La-Dama-del-Norte"><div style="position:relative"><img alt="La Dama del Norte" src="https://cdn.example.com/covers/dama.jpg"/></div></a><div data-mn-tarjeta-pie="1"><a href="/novela/La-Dama-del-Norte">La Dama del Norte</a></div></article>
</div></body></html>"""

    /** The `Book` JSON-LD carries the metadata; the `BreadcrumbList` block must be skipped. */
    const val BOOK_JSON = """{"@context":"https://schema.org","@type":"Book",
"name":"I Married The Dragon I Killed",
"description":"Línea uno.\n\"Cita\".\nFin\u200b",
"image":"https://matchanovels.com/static/imagenes-portadas/portada-I-Married-The-Dragon-I-Killed.webp?v=1784448982",
"author":{"@type":"Organization","name":"MatchaNovels"}}"""

    const val BREADCRUMB_JSON = """{"@context":"https://schema.org","@type":"BreadcrumbList",
"itemListElement":[{"@type":"ListItem","position":3,
"name":"I Married The Dragon I Killed",
"item":"https://matchanovels.com/novela/I-Married-The-Dragon-I-Killed"}]}"""

    /** A novel page: header, a `BreadcrumbList` before the `Book`, the CTA plus the real chapter list. */
    const val NOVEL = """<!doctype html><html><head>
<script type="application/ld+json">$BREADCRUMB_JSON</script>
<script type="application/ld+json">$BOOK_JSON</script>
</head><body>
<header data-mn-cabecera="1" data-buscando="0"><nav><a aria-label="MatchaNovels" href="/">MatchaNovels</a><a href="/biblioteca">Biblioteca</a></nav><a href="/novela/I-Married-The-Dragon-I-Killed">I Married The Dragon I Killed</a><h1>I Married The Dragon I Killed</h1></header>
<main><p>Impulsado por el odio y la sed de venganza.</p>
<div data-mn-lista="1" style="max-height:min(620px,72vh);overflow-y:auto">
<a data-mn-continuar-boton="1" href="/novela/I-Married-The-Dragon-I-Killed/capitulo/1">Empezar</a>
<a data-mn-fila="1" data-actual="0" data-leido="0" data-bloqueado="0" href="/novela/I-Married-The-Dragon-I-Killed/capitulo/1"><span>Capítulo 1</span><span>19 jul 26</span></a>
<a data-mn-fila="1" data-actual="0" data-leido="0" data-bloqueado="1" href="/novela/I-Married-The-Dragon-I-Killed/capitulo/2"><span>Capítulo 2<span> · Have I returned?</span></span><span>19 jul 26</span></a>
<a data-mn-fila="1" data-actual="0" data-leido="0" data-bloqueado="0" href="/novela/I-Married-The-Dragon-I-Killed/capitulo/3"><span>Capítulo 3</span><span>19 jul 26</span></a>
<a data-mn-fila="1" data-bloqueado="0" href="/novela/Another-Novel/capitulo/1"><span>Capítulo 1</span></a>
</div></main>
</body></html>"""

    /** A chapter page: the chrome (header, CTA, footer) wraps the real prose. */
    const val CHAPTER = """<!doctype html><html><body>
<header data-mn-cabecera="1" data-buscando="0"><p>ESEntrarCrear cuentaBibliotecaVIPAnteriorÍndice de la novelaÍndiceSiguienteI Married The Dragon I KilledCapítulo 1# Leer ahora</p><nav><button>Anterior</button><a data-mn-compacto="1" href="/novela/I-Married-The-Dragon-I-Killed">Índice</a><button>Siguiente</button></nav></header>
<header style="margin:36px 0 28px"><a href="/novela/I-Married-The-Dragon-I-Killed">I Married The Dragon I Killed</a><h1>Capítulo 1</h1></header>
<div data-mn-anuncio="inicio" data-estado="reservado"></div>
<div class="mn-lector" data-mn-texto-capitulo="1"><p># Leer ahora</p>
<p>Había una vez un hombre singular que se convirtió en un gran mago únicamente a través del odio.</p>
<p>Su nombre era Ferda Rosnova.</p>
<p>—Tu compromiso con la Reina de Valdrova ha sido arreglado.</p></div>
<nav><a href="/novela/I-Married-The-Dragon-I-Killed/capitulo/1">Anterior</a><a href="/novela/I-Married-The-Dragon-I-Killed">Índice</a><a href="/novela/I-Married-The-Dragon-I-Killed/capitulo/2">Siguiente</a></nav>
<section data-mn-comentarios="1"><h2>Comentarios</h2><span>Inicia sesión para comentar</span></section>
<footer><p>Contacto &amp; DMCASobre nosotrosTérminos de usoPolítica de privacidad© 2026 MatchaNovels. Todos los derechos reservados.</p></footer>
</body></html>"""

    /** Synthetic restriction card based on the observed chapter 405 heading and access notice. */
    const val CHAPTER_REQUIRES_VIP = """<!doctype html><html><body>
<header><h1>Capítulo 405</h1></header>
<div><h2>Este capítulo es para miembros VIP</h2>
<p>El capítulo está bloqueado hasta obtener acceso.</p><a href="/vip">Membresía VIP</a></div>
</body></html>"""

    /** Synthetic narrative: mentioning VIP does not itself mean access is restricted. */
    const val CHAPTER_WITH_VIP_PROSE = """<!doctype html><html><body>
<header><h1>Capítulo 2</h1><h2>Este capítulo es para miembros VIP</h2></header>
<div data-mn-texto-capitulo="1"><p>La invitada VIP cruzó la plaza.</p></div>
</body></html>"""

    /** A page whose prose container holds only chrome, so it must parse to zero blocks. */
    const val CHAPTER_WITHOUT_PROSE = """<!doctype html><html><body>
<header><h1>Capítulo 0</h1></header>
<div data-mn-texto-capitulo="0"><p># Leer ahora</p></div>
</body></html>"""

    // ---- Chapter-list payload fixtures -------------------------------------------------------

    /** One row of the `capitulos` array, in the measured `["<n>","<date>",false,null,null]` shape. */
    fun chapterRow(number: String): String = """["$number","22 jul 26",false,null,null]"""

    /**
     * A novel page whose chapter rows live in the Next.js flight payload rather than in rendered
     * anchors. With [escaped] the payload is embedded inside a JavaScript string literal and every
     * quote is backslash-escaped — `\"capitulos\":[[\"1\",...` — which is exactly what the server
     * serves. [links] also renders that many anchor rows for the same slug, so a test can prove
     * which of the two paths wins.
     */
    fun payloadNovel(slug: String, rows: List<String>, escaped: Boolean = true, links: Int = 0): String {
        val json = """{"lang":"es","slug":"$slug","primerCapitulo":1,"ultimoCapitulo":${rows.size},""" +
            """"total":${rows.size},"capitulos":[${rows.joinToString(",")}]}"""
        val embedded = if (escaped) json.replace("\"", "\\\"") else json
        val anchors = (1..links).joinToString("") {
            """<a data-mn-fila="1" href="/novela/$slug/capitulo/$it"><span>Capítulo $it</span></a>"""
        }
        return """<!doctype html><html><head><script>self.__next_f.push([1,"$embedded"])</script></head>""" +
            """<body><h1>$slug</h1><div data-mn-lista="1">$anchors</div></body></html>"""
    }
}
