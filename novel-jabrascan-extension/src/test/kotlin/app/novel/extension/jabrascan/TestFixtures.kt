package app.novel.extension.jabrascan

/**
 * Hand-written excerpts of the three JabraScan documents, in their measured shapes.
 *
 * They are synthetic, not captured: the publishable samples are large (129 KB to 81 KB) PDF-library
 * listings, and what the tests need is not their size but their *edge cases*, which are reproduced
 * here one by one — a `visible` that says `no`, a repeated `<nombreobra>` whose first copy is empty, a
 * sinopsis inside CDATA and another one in plain text, the stray `"` that follows `</sinopsis>` in the
 * real `obras.xml`, elements the format does not define (`pendejos`, `server`), an `<obra>` that carries
 * no sinopsis at all, `numCapitulo` written as a string, and a chapter row with no file name.
 *
 * They are kept as string literals rather than resource files because the parsers under test are pure
 * (String in, data out), so a literal keeps the tests free of file access and of fixtures tied to the
 * publishable samples.
 *
 * The XML is written at the left margin so the indentation a real file carries never becomes part of an
 * assertion; the sinopsis below states its own tabs and newlines.
 */
internal object TestFixtures {

    /** Three obras: one visible, one hidden, one carrying the bare minimum. */
    val CATALOGUE = """
<obras>
<obra>
<visible>si</visible>
<clave>TorreDeCristal</clave>
<nombreobra></nombreobra>
<nombreobra>La torre de cristal</nombreobra>
<autor>Autor Uno</autor>
<sinopsis><![CDATA[Una torre, un secreto. <a href="https://blog.example.com/x" target="_blank">Blog</a>
	Y una segunda línea.]]></sinopsis>"
<meta>Textos &amp; más textos.</meta>
<keywords>Torre, Cristal</keywords>
<idioma>es</idioma>
<imagen>TorreDeCristal/TorreDeCristal01.webp</imagen>
<imagen>TorreDeCristal/TorreDeCristal02.webp</imagen>
<pendejos>un elemento que el formato no define</pendejos>
</obra>
<obra>
<visible>no</visible>
<clave>ObraOculta</clave>
<nombreobra>Obra oculta</nombreobra>
<autor>Autor Dos</autor>
<sinopsis>Sinopsis de la obra oculta.</sinopsis>
<imagen>ObraOculta/ObraOculta01.webp</imagen>
</obra>
<obra>
<visible>si</visible>
<clave>ObraMinima</clave>
<nombreobra>Obra mínima</nombreobra>
<autor>Autor Tres</autor>
</obra>
</obras>
"""

    /**
     * The three ways an obra can be incomplete: no `clave` (nothing to address it by), no `nombreobra`
     * (nothing to render), and no `visible` at all, which is *not* the same as `visible=no`.
     */
    val CATALOGUE_DAMAGED = """
<obras>
<obra>
<nombreobra>Sin clave</nombreobra>
<autor>Autor A</autor>
</obra>
<obra>
<clave>SinTitulo</clave>
<visible>si</visible>
<autor>Autor B</autor>
</obra>
<obra>
<clave>SinVisible</clave>
<nombreobra>Sin campo visible</nombreobra>
<autor>Autor C</autor>
</obra>
</obras>
"""

    /** Truncated mid-obra, the shape a cut-off download has. */
    val CATALOGUE_TRUNCATED = """
<obras>
<obra>
<clave>TorreDeCristal</clave>
<nombreobra>La torre de cristal</nombreobra>
"""

    /** A catalogue with no obras in it. */
    val CATALOGUE_EMPTY = "<obras></obras>"

    /** Every obra is unusable — one with no clave, one with no title — so the catalogue is broken. */
    val CATALOGUE_ALL_DISCARDED = """
<obras>
<obra>
<nombreobra>Sin clave</nombreobra>
</obra>
<obra>
<clave>SinTitulo</clave>
<visible>si</visible>
</obra>
</obras>
"""

    /** What the host gets back when the request never reached the catalogue. */
    val NOT_A_CATALOGUE = """<!doctype html><html><body><h1>502 Bad Gateway</h1></body></html>"""

    /** Two entries, one per obra. Keys are the claves, values are paths relative to the library root. */
    val INDEX = """{"TorreDeCristal":"books/TorreDeCristal.json","ObraOculta":"books/ObraOculta.json"}"""

    /** A third entry whose value is not a path at all. */
    val INDEX_WITH_UNKNOWN_ENTRY =
        """{"TorreDeCristal":"books/TorreDeCristal.json","Rota":null,"Vacia":"  "}"""

    val INDEX_EMPTY = "{}"

    /** The wrong shape: an index is an object, never an array. */
    val INDEX_NOT_AN_OBJECT = "[]"

    val INDEX_TRUNCATED = """{"TorreDeCristal":"books/Torre"""

    /** The chapter list of one work, in the measured field order. */
    val CHAPTERS = """
{"TorreDeCristal":[
{"Fecha":"28-12-2025","NombreArchivo":"TorreDeCristal-0000.0v01_TorreDeCristal.pdf","numCapitulo":"0","nombreCapitulo":"Prólogo","tituloObra":"TorreDeCristal Vol 01"},
{"Fecha":"29-12-2025","NombreArchivo":"TorreDeCristal-0001.1v01_TorreDeCristal.pdf","numCapitulo":"1.1","nombreCapitulo":"Capítulo \"El pacto\"","tituloObra":"TorreDeCristal Vol 01"},
{"Fecha":"01-01-2026","NombreArchivo":"TorreDeCristal-0002.0v02_TorreDeCristal.pdf","numCapitulo":"2","nombreCapitulo":"Capítulo 2","tituloObra":"TorreDeCristal Vol 02"}
]}
"""

    /** A work whose chapters were removed. */
    val CHAPTERS_EMPTY = """{"TorreDeCristal":[]}"""

    /**
     * One array under a key that is not the clave. Refused, because it may be another work's list and
     * filing it under this work would carry the wrong progress and downloads.
     */
    val CHAPTERS_UNDER_ANOTHER_KEY =
        """{"OtroNombre":[{"NombreArchivo":"TorreDeCristal-0001.0v01.pdf","numCapitulo":"1"}]}"""

    /** Keys that hold no array of chapters. */
    val CHAPTERS_SHAPE_MISMATCH = """{"TorreDeCristal":{},"OtraObra":[]}"""

    /** Rows that carry no number, and a title that is not the hand-written one. */
    val CHAPTERS_WITHOUT_NUMBERS =
        """{"TorreDeCristal":[{"NombreArchivo":"sin-numero.pdf","nombreCapitulo":"Sin número"},{"NombreArchivo":"TorreDeCristal-0078.0v09.pdf","numCapitulo":"78"}]}"""

    /** A row with no file name cannot be opened, so it is dropped; the next row survives. */
    val CHAPTERS_WITH_DAMAGED_ROW =
        """{"TorreDeCristal":[{"Fecha":"01-01-2026","nombreCapitulo":"Sin archivo"},{"NombreArchivo":"buena.pdf","numCapitulo":"1"}]}"""

    val CHAPTERS_MALFORMED = """{"TorreDeCristal":[{"NombreArchivo":"TorreDeCristal-0001.0v01.pdf}"""

    /** Every JSON shape the reader has to tell apart, in one document. */
    val JSON_DOCUMENT = """
{"object":{"string":"con \"comillas\" y \u00f1","number":78.5,"integer":49,"bool":true,"nothing":null,
"array":["uno",2,false,null,{"anidado":"si"}]},"empty":{},"emptyArray":[]}
"""
}
