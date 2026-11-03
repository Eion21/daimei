package app.novel.extension.jabrascan

import android.content.Context
import android.util.Log
import app.novel.source.api.Chapter
import app.novel.source.api.ChapterContent
import app.novel.source.api.NetworkedNovelSource
import app.novel.source.api.Novel
import app.novel.source.api.NovelNetwork
import app.novel.source.api.NovelPage
import app.novel.source.api.SourceInfo
import app.novel.source.api.android.ContextAwareNovelSource
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** The logcat tag every warning about a JabraScan document carries. */
private const val TAG = "JabraScanSource"

/** The one catalogue page: the library is one file, so it never pages. */
private const val FIRST_PAGE = 1

/** The `server` value whose rows live on the PDF repository, the same root an absent server implies. */
private const val IO_PDFS = "io-pdfs"

/** The `server` value whose rows live on the site itself. */
private const val LOCAL = "local"

/**
 * The real source for `https://jabrascan.net/`, the third after MatchaNovels and SkyNovels.
 *
 * JabraScan is not a text site: it is a **PDF library**. Its catalogue (`obras.xml`), its chapter index
 * (`capitulos.json`) and its per-work chapter lists (`books/{clave}.json`) are small text files, but
 * every chapter body is a PDF and no HTML or JSON endpoint carries the prose. The reader models content
 * as paragraphs, so a chapter only becomes readable after its PDF is turned into text — this source
 * fetches the bytes with the host's `fetchBytes` and hands them to [JabraScanPdf], which does the
 * extraction (and the `withContext(Dispatchers.IO)`) the host's call does not.
 *
 * Three decisions shape the class:
 *
 * - **The identity is the file, not the address.** A [Chapter]'s `url` is the bare file name the list
 *   gives and its `id` is `{obra}/{file}`; neither carries a root or an encoding. The reader stores and
 *   matches chapters by that pair, so a work whose rows move between the site and the repository keeps
 *   every chapter's saved progress. The full, percent-encoded address is assembled in [JabraScanUrls]
 *   only when the bytes are requested.
 * - **The root is chosen per row.** The `server` field is read from each chapter row — `CDMNQTMHC` and
 *   `CultivationOnline` mix `local` rows with repository rows in one work — and an absent field or
 *   `io-pdfs` means the PDF repository while `local` means the site. The per-file map is filled while
 *   [getChapters] reads the list and kept in a [ConcurrentHashMap], because chapters download
 *   concurrently; a download in a fresh process whose list was never read refetches `books/{clave}.json`
 *   once to fill it. An unknown `server` keeps the row (dropping it would make the host delete the
 *   stored chapter and its progress) and its download fails naming the value — there is no default root.
 * - **The catalogue is the whole library**, so [popular] and [search] both fetch one file and never page.
 *
 * Warnings go through [logSink], whose default is a no-op so a plain-JVM test cannot reach
 * `android.util.Log`; [bindContext] wires it to `Log.w` on the device.
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
     * tables the AAR ships under `assets/` through it; `lateinit` for the same reason [network] is.
     */
    private lateinit var context: Context

    /**
     * Each work's rows, by file name, so a download knows which root its row belongs to without reading
     * the chapter list again. [ConcurrentHashMap] because a chapter list can be read while other
     * chapters of other works are already downloading; each value is replaced whole, never mutated.
     */
    private val serversByWork = ConcurrentHashMap<String, Map<String, String?>>()

    /** One lock per work, so a concurrent refetch of its chapter list is done once instead of per caller. */
    private val listLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Where a warning about a document goes. The default is a no-op so that a plain-JVM test, which
     * cannot call `android.util.Log`, never reaches it: every test injects its own sink here, and only
     * the device (through [bindContext]) wires it to `Log.w`.
     */
    internal var logSink: (String) -> Unit = {}

    override val info = SourceInfo(
        id = JabraScanParsing.SOURCE_ID,
        name = "JabraScan",
        version = "1.0.1",
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
     *
     * The same binding wires [logSink] to `Log.w`. It is done here, and not in the constructor, on
     * purpose: the unit tests drive every other path but cannot call this Android-only method, so they
     * keep the no-op default and never touch `android.util.Log` — which the test runner does not mock.
     */
    override fun bindContext(context: Context) {
        this.context = context
        logSink = { Log.w(TAG, it) }
        PDFBoxResourceLoader.init(this.context)
    }

    /** The whole catalogue lives in one `obras.xml`, so the library never pages. */
    override suspend fun popular(page: Int): NovelPage =
        if (page == FIRST_PAGE) catalogue() else NovelPage(emptyList(), hasNextPage = false)

    /** The site searches client-side, so the catalogue is fetched and filtered here, and never pages. */
    override suspend fun search(query: String, page: Int): NovelPage =
        if (page == FIRST_PAGE) {
            NovelPage(JabraScanParsing.filterByTitle(catalogue().novels, query), hasNextPage = false)
        } else {
            NovelPage(emptyList(), hasNextPage = false)
        }

    override suspend fun getNovelDetails(novel: Novel): Novel =
        JabraScanParsing.parseNovelDetails(network.fetch(catalogueUrl()), novel)

    /**
     * The work's chapters, and the server map they are downloaded with.
     *
     * The index (`capitulos.json`) is the site's own map from a clave to its chapter list, so it decides
     * which path is read and a work the index does not carry is simply a work with no chapters. The list
     * is joined against the PDF repository — the index's paths are relative to it — and both halves of
     * [JabraScanParsing.parseChapterListReport] are put to use: the chapters are returned and the servers
     * are kept for the downloads that follow.
     */
    override suspend fun getChapters(novel: Novel): List<Chapter> {
        val index = JabraScanParsing.parseChapterIndex(network.fetch(indexUrl()))
        val path = index[novel.id] ?: return emptyList()
        val list = JabraScanParsing.parseChapterListReport(
            network.fetch(join(JabraScanUrls.PDF_REPOSITORY, path)),
            novel,
        )
        serversByWork[novel.id] = list.servers
        return list.chapters
    }

    /**
     * The chapter's prose, extracted from its PDF.
     *
     * The root comes from the row's `server` alone: absent or `io-pdfs` is the PDF repository, `local` is
     * the site, and anything else has no root and fails here — before the download and before anything
     * touches PdfBox — naming the offending value and the work. A row with no known root is never
     * silently pointed at a default, because the wrong root is a 404 the reader cannot diagnose.
     *
     * The server map is filled by [getChapters] in the normal flow. A fresh process can reach a download
     * before any list was read, so this refetches `books/{clave}.json` exactly once and keeps the result;
     * a work already in the map costs no request.
     */
    override suspend fun getChapterContent(chapter: Chapter): ChapterContent {
        val work = chapter.novelId
        val file = chapter.url
            ?: throw IllegalStateException("Chapter ${chapter.id} carries no file name, so its PDF cannot be addressed")
        val server = serverFor(work, file)
        val root = rootFor(server)
            ?: throw IllegalStateException(
                "The work \"$work\" has a row \"$file\" whose server is \"$server\", which names no known root; " +
                    "the row is kept, but there is no default root to download it from",
            )
        val bytes = network.fetchBytes(JabraScanUrls.chapterPdfUrl(root, work, file, chapter.number))
        val paragraphs = JabraScanPdf.extract(
            bytes,
            ChapterIdentity(work = work, chapter = chapter.title),
            scratchDir = if (this::context.isInitialized) context.cacheDir else null,
        )
        return ChapterContent(paragraphs)
    }

    /** The catalogue, with every dropped obra reported through [logSink] rather than silently lost. */
    private suspend fun catalogue(): NovelPage {
        val report = JabraScanParsing.parseCatalogueReport(network.fetch(catalogueUrl()))
        for (discard in report.discarded) logSink(discard.describe())
        return report.page
    }

    /** The row's server, from the map when the list is known and from one refetch when it is not. */
    private suspend fun serverFor(work: String, file: String): String? {
        serversByWork[work]?.let { return it[file] }
        val servers = listLocks.computeIfAbsent(work) { Mutex() }.withLock {
            serversByWork[work] ?: loadServers(work).also { serversByWork[work] = it }
        }
        return servers[file]
    }

    /**
     * Reads and keeps the work's chapter list on its own, for a download that arrived before
     * [getChapters] did. The path is the site's conventional `books/{clave}.json` — the one the index
     * maps every clave to — so filling the map costs one request and not a second one for the index.
     */
    private suspend fun loadServers(work: String): Map<String, String?> {
        val json = network.fetch(join(JabraScanUrls.PDF_REPOSITORY, "books/$work.json"))
        val list = JabraScanParsing.parseChapterListReport(json, Novel(JabraScanParsing.SOURCE_ID, work, work))
        return list.servers
    }

    /** The root a row's `server` names, or `null` when it names none: no default is ever substituted. */
    private fun rootFor(server: String?): String? = when (server) {
        null, IO_PDFS -> JabraScanUrls.PDF_REPOSITORY
        LOCAL -> JabraScanUrls.SITE
        else -> null
    }

    private fun catalogueUrl(): String = join(JabraScanUrls.CONTENT_REPOSITORY, "obras.xml")

    private fun indexUrl(): String = join(JabraScanUrls.CONTENT_REPOSITORY, "capitulos.json")

    private fun join(root: String, path: String): String = root.trimEnd('/') + "/" + path.trimStart('/')
}
