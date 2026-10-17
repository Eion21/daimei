package app.novel.extension.jabrascan

import android.content.Context
import app.novel.source.api.Chapter
import app.novel.source.api.ChapterContent
import app.novel.source.api.NetworkedNovelSource
import app.novel.source.api.Novel
import app.novel.source.api.NovelNetwork
import app.novel.source.api.NovelPage
import app.novel.source.api.SourceInfo
import app.novel.source.api.android.ContextAwareNovelSource
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * The scaffold for `https://jabrascan.net/`, the third real novel source after MatchaNovels and
 * SkyNovels.
 *
 * JabraScan is not a text site: it is a **PDF library**. Its catalogue (`obras.xml`), its chapter
 * index (`capitulos.json`) and its per-work chapter lists (`books/{clave}.json`) are small text
 * files, but every chapter body is a PDF hosted on a GitHub raw repository, and no HTML or JSON
 * endpoint carries the prose. The reader models content as paragraphs, so a chapter only becomes
 * readable after its PDF is turned into text.
 *
 * That shapes this source in two ways the previous two do not have:
 *
 * - it needs the *bytes* of a response, not text, so it uses the host's `fetchBytes`. The text
 *   `fetch` would decode a PDF as text and destroy it. This is why it needs contract 1.2, the version
 *   that added `fetchBytes`;
 * - its parsing is not light regex over a few hundred KB. PdfBox extraction is CPU-heavy (the spike
 *   measured 16-24 ms per page, 0.13-0.34 s for a chapter), and `GetChapterContent` calls this source
 *   with no `withContext` of its own, so the extension wraps its own CPU work in
 *   `withContext(Dispatchers.IO)` (T5). That is why coroutines are `compileOnly` in the build file:
 *   the host provides them at runtime and the APK must not ship a second copy.
 *
 * This class is **scaffolding only**. It exists so the module builds an APK that loads under contract
 * 1.3; the five data methods deliberately throw so nobody mistakes it for a working source. T4 fills
 * in the parsing, T5 the PdfBox extraction, and T6 wires both into these methods and covers them with
 * tests.
 */
class JabraScanSource : NetworkedNovelSource, ContextAwareNovelSource {

    /**
     * Injected through [bindNetwork] before the host registers this source. `lateinit` rather than a
     * nullable field so a source reached before binding fails loudly instead of silently fetching
     * nothing.
     */
    private lateinit var network: NovelNetwork

    /**
     * Injected through [bindContext] before the host registers this source. PdfBox reads the glyph
     * tables the AAR ships under `assets/` through it, and T5 will need it to open the extension's own
     * assets too; `lateinit` for the same reason [network] is.
     */
    private lateinit var context: Context

    override val info = SourceInfo(
        id = "jabrascan",
        name = "JabraScan",
        version = "1.0",
        language = "es",
    )

    override fun bindNetwork(network: NovelNetwork) {
        this.network = network
    }

    /**
     * The host calls this right after construction and before the source is registered, with a context
     * of the extension's **own** package — it wraps `createPackageContext`, so this is never the host's
     * context. PdfBox is the reason the context is needed at all: `PDFBoxResourceLoader.init` reads the
     * glyph tables the AAR ships under `assets/` through
     * `context.getApplicationContext().getAssets()`, so extraction returns nothing until it has been
     * initialised with one. It has to run here, before anything else touches PdfBox: a
     * `LegacyPDFStreamEngine`-touching class whose static initializer fails poisons the whole process.
     */
    override fun bindContext(context: Context) {
        this.context = context
        PDFBoxResourceLoader.init(this.context)
    }

    /** Scaffolding: T4 parses `obras.xml` into a [NovelPage] and T6 binds it here. */
    override suspend fun popular(page: Int): NovelPage =
        throw NotImplementedError("popular is scaffolding: T4 parses obras.xml and T6 wires it here")

    /** Scaffolding: T4 matches the query against `obras.xml` and T6 binds it here. */
    override suspend fun search(query: String, page: Int): NovelPage =
        throw NotImplementedError("search is scaffolding: T4 parses obras.xml and T6 wires it here")

    /** Scaffolding: T4 parses the work entry (`nombreobra`, `autor`, `sinopsis`) and T6 binds it here. */
    override suspend fun getNovelDetails(novel: Novel): Novel =
        throw NotImplementedError("getNovelDetails is scaffolding: T4 parses the work and T6 wires it here")

    /** Scaffolding: T4 reads `books/{clave}.json` and builds the PDF URL, and T6 binds it here. */
    override suspend fun getChapters(novel: Novel): List<Chapter> =
        throw NotImplementedError("getChapters is scaffolding: T4 parses the chapter list and T6 wires it here")

    /** Scaffolding: T5 fetches the PDF with `fetchBytes` and extracts it with PdfBox, and T6 binds it here. */
    override suspend fun getChapterContent(chapter: Chapter): ChapterContent =
        throw NotImplementedError("getChapterContent is scaffolding: T5 extracts the PDF and T6 wires it here")
}
