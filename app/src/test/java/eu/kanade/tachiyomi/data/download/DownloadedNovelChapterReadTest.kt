package eu.kanade.tachiyomi.data.download

import app.novel.runtime.SourceRegistry
import app.novel.source.api.Chapter
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import app.novel.source.api.Novel
import app.novel.source.api.NovelPage
import app.novel.source.api.NovelSource
import app.novel.source.api.SourceInfo
import app.novel.source.http.HttpNovelSource
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.novel.NovelSourceAdapter
import eu.kanade.tachiyomi.ui.reader.novel.NovelReaderViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.novel.interactor.GetChapterContent
import tachiyomi.domain.novel.interactor.GetReadingProgress
import tachiyomi.domain.novel.interactor.SaveReadingProgress
import tachiyomi.domain.novel.repository.ChapterContentRepository
import tachiyomi.domain.novel.repository.ReadingProgress
import tachiyomi.domain.novel.repository.ReadingProgressRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import tachiyomi.domain.chapter.model.Chapter as DomainChapter

/**
 * The situation a downloaded chapter is in once the app cache has been cleared and the device is offline.
 *
 * Every unit of this was green while the chapter still refused to open. `GetChapterContent` reads the content cache
 * and then asks the source, and the `.txt` the downloader wrote took part in neither: `Downloader` wrote it,
 * `DownloadCache` indexed it — which is why the badge said "downloaded" — and the reader never opened it. Clearing
 * the cache therefore left a library of files that could not be read at all, and the read chain reported an honest
 * network failure, so the symptom looked like an outage rather than a chapter nobody ever read from disk.
 *
 * So nothing here is tested one half at a time. Each test drives the real view model over the real provider and the
 * real content cache, against a real `HttpNovelSource` whose only interceptor throws `IOException` — the same failure
 * an emulator in airplane mode produces, with no socket and no server anywhere. `DownloadCache` is deliberately not
 * in this path: it answers "is this downloaded", not "what does it say", and it cannot be constructed in a JVM test
 * at all, because its `init` builds an `AtomicFile` over `context.cacheDir`.
 */
class DownloadedNovelChapterReadTest {

    // ---------------------------------------------------------------------------------------------
    // The situation that shipped: cache empty, file on disk, source unreachable.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a downloaded chapter opens from its file with the cache empty and the source unreachable`() = runBlocking {
        val downloads = FakeDownloads()
        downloads.writeChapterFile(DOWNLOADED_TEXT)
        val source = OfflineSource()
        val viewModel = viewModel(downloads, source, cached = emptyMap())

        val content = openChapter(viewModel)

        assertNull(
            viewModel.error.value,
            "the chapter refused to open even though its text file was on disk: ${viewModel.error.value?.reason}",
        )
        assertEquals(
            DOWNLOADED_BLOCKS,
            content?.blocks,
            "the chapter opened, but not with the text that was in the file",
        )
        // The point of the whole change, said in the only direction that matters: the network was never needed. A
        // count above zero here would mean the file did not answer and something else quietly did.
        assertEquals(
            0,
            source.chapterRequests.get(),
            "the chapter needed the source after all, so the file is not what answered it",
        )
    }

    @Test
    fun `the source this file is offline against really cannot answer the chapter`() = runBlocking {
        // The condition every other test in this file rests on, pinned on its own. Left implicit it would be an
        // assumption, and a source that quietly started answering — or an interceptor that stopped throwing — would
        // leave them all green while they tested nothing about a device with no network.
        val thrown = runCatching { OfflineSource().getChapterContent(novelChapter()) }.exceptionOrNull()
        assertTrue(thrown is IOException, "the offline source answered the chapter anyway: $thrown")
    }

    @Test
    fun `the downloaded file wins over a content cache that disagrees with it`() = runBlocking {
        // The badge promises the file, and the file is what the user has on disk. The cache is a copy — one written
        // under `cache/`, which clearing the app cache takes away without the user deleting a single download. So a
        // cache that disagrees is the stale one, whichever of the two was written more recently.
        val downloads = FakeDownloads()
        downloads.writeChapterFile(DOWNLOADED_TEXT)
        val viewModel = viewModel(downloads, OfflineSource(), cached = mapOf(CHAPTER_ID to CACHED_CONTENT))

        val content = openChapter(viewModel)

        assertEquals(
            DOWNLOADED_BLOCKS,
            content?.blocks,
            "the content cache answered over a downloaded file that says something else",
        )
    }

    // ---------------------------------------------------------------------------------------------
    // The neighbouring branches, so the order cannot be quietly rewritten into something else.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a chapter with no file still fails when the source is unreachable`() = runBlocking {
        val source = OfflineSource()
        val viewModel = viewModel(FakeDownloads(), source, cached = emptyMap())

        viewModel.load(CHAPTER_ID)
        withTimeout(OPEN_TIMEOUT) { viewModel.isLoading.first { !it } }

        assertNull(viewModel.content.value, "a chapter with nothing to read published content")
        assertNotNull(viewModel.error.value, "no file, no cache and an unreachable source still opened the chapter")
    }

    @Test
    fun `the content cache still answers a chapter that was read but never downloaded`() = runBlocking {
        // The cache is not the thing this change fixes, and dropping it would trade one bug for another: a chapter
        // read on a train and never downloaded has no file, and only the cache can answer for it.
        val source = OfflineSource()
        val viewModel = viewModel(FakeDownloads(), source, cached = mapOf(CHAPTER_ID to CACHED_CONTENT))

        val content = openChapter(viewModel)

        assertEquals(CACHED_CONTENT.blocks, content?.blocks, "the content cache stopped answering")
        assertEquals(
            0,
            source.chapterRequests.get(),
            "the source was asked for a chapter the content cache already had",
        )
    }

    @Test
    fun `a manga chapter is never answered from a text file`() = runBlocking {
        // `ChapterOpener.shouldOpenNovelReader` is the one discriminator, reused rather than restated. Asking it per
        // call site is how a `.txt` that happens to sit in a manga's folder starts standing in for a chapter that
        // was never downloaded, and the download that never happened could never be asked for again.
        val downloads = FakeDownloads()
        downloads.writeChapterFile(DOWNLOADED_TEXT)
        val viewModel = viewModel(
            downloads,
            OfflineSource(),
            cached = mapOf(CHAPTER_ID to CACHED_CONTENT),
            novel = false,
        )

        val content = openChapter(viewModel)

        assertEquals(
            CACHED_CONTENT.blocks,
            content?.blocks,
            "a manga chapter was answered from the text file standing next to it",
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Wiring
    // ---------------------------------------------------------------------------------------------

    /**
     * Opens the chapter and waits for the answer.
     *
     * `runBlocking` rather than `runTest` on purpose: the read hops to `Dispatchers.IO` for the folder lookup, which
     * is a real thread pool, and `runTest` only advances its own virtual clock — it cannot wait for one. This waits
     * on the state flow with a real timeout, so a chain that never answers fails the test instead of hanging it.
     */
    private suspend fun openChapter(viewModel: NovelReaderViewModel): ChapterContent? {
        viewModel.load(CHAPTER_ID)
        return withTimeout(OPEN_TIMEOUT) { viewModel.content.filterNotNull().first() }
    }

    private fun viewModel(
        downloads: FakeDownloads,
        source: OfflineSource,
        cached: Map<Long, ChapterContent>,
        novel: Boolean = true,
    ): NovelReaderViewModel {
        val chapters = chapterRepository(novel)
        return NovelReaderViewModel(
            getChapterContent = GetChapterContent(
                FakeContentRepository(cached),
                chapters,
                mangaRepository(),
                SourceRegistry().apply { register(source) },
            ),
            getReadingProgress = GetReadingProgress(FakeProgressRepository()),
            saveReadingProgress = SaveReadingProgress(FakeProgressRepository()),
            updateChapter = UpdateChapter(chapters),
            upsertHistory = UpsertHistory(mockk<HistoryRepository>(relaxed = true)),
            downloadProvider = downloads.provider,
            getChapter = GetChapter(chapters),
            getManga = GetManga(mangaRepository()),
            sourceManager = sourceManager(source),
        )
    }

    /** The `sourceManager` half: the folder is named after the adapter, which is what the downloader named it after. */
    private fun sourceManager(source: OfflineSource) = mockk<SourceManager>()
        .also { manager ->
            coEvery { manager.get(MANGA_SOURCE_ID) } returns NovelSourceAdapter(source)
        }

    /**
     * The chapter as `NovelMapper` writes it, so `ChapterOpener.shouldOpenNovelReader` reads it as a novel chapter
     * without this file having to know what the memo means.
     */
    private fun chapter(novel: Boolean) = DomainChapter.create().copy(
        id = CHAPTER_ID,
        mangaId = MANGA_ID,
        name = CHAPTER_NAME,
        url = CHAPTER_URL,
        memo = if (novel) novelMemo() else JsonObject(emptyMap()),
    )

    private fun chapterRepository(novel: Boolean) = mockk<ChapterRepository>().also {
        coEvery { it.getChapterById(CHAPTER_ID) } returns chapter(novel)
    }

    /** The chapter as `GetChapterContent` rebuilds it for the source, so the source is asked a real question. */
    private fun novelChapter() = Chapter(
        sourceId = SOURCE_ID,
        novelId = NOVEL_ID,
        id = CHAPTER_URL,
        title = CHAPTER_NAME,
        url = "${BASE_URL}novel/chapter.html",
    )

    private fun mangaRepository() = mockk<MangaRepository>().also {
        coEvery { it.getMangaById(MANGA_ID) } returns manga
    }

    private fun novelMemo() = buildJsonObject {
        put("novelSourceId", JsonPrimitive(SOURCE_ID))
        put("novelId", JsonPrimitive(NOVEL_ID))
    }

    /**
     * A real `HttpNovelSource` behind an interceptor that throws, which is what an emulator in airplane mode does.
     *
     * Not a stubbed source: the failure has to travel the path it travels on a device. `HttpNovelSource.get` reads
     * the `IOException` as "no document", `getChapterContent` turns that null into a thrown failure, and
     * `GetChapterContent` catches it into a miss — which is the whole reason the shipped bug looked like an outage.
     * A fake that threw directly would skip the step that decides the chapter cannot be fetched, and the test would
     * keep passing even if that step were broken.
     */
    private class OfflineSource : NovelSource {
        private val http = HttpNovelSource(
            baseUrl = BASE_URL,
            httpClient = OkHttpClient.Builder()
                .addInterceptor { _: Interceptor.Chain -> throw IOException("no route to host") }
                .build(),
        )

        val chapterRequests = AtomicInteger()

        override val info: SourceInfo = http.info
        override suspend fun popular(page: Int): NovelPage = http.popular(page)
        override suspend fun search(query: String, page: Int): NovelPage = http.search(query, page)
        override suspend fun getNovelDetails(novel: Novel): Novel = http.getNovelDetails(novel)
        override suspend fun getChapters(novel: Novel): List<Chapter> = http.getChapters(novel)

        override suspend fun getChapterContent(chapter: Chapter): ChapterContent {
            chapterRequests.incrementAndGet()
            return http.getChapterContent(chapter)
        }
    }

    /**
     * The downloads tree, with the chapter file in it or without it.
     *
     * None of the three names are spelled out here. They are the ones the real provider builds, so a rename of the
     * source folder, the manga folder or the chapter file cannot leave this test describing a layout that nothing
     * writes any more — which is exactly how a reader ends up looking for a file under a name no downloader uses.
     */
    private class FakeDownloads {
        /** `file name -> text`, as the tree the storage manager hands over. */
        private val files = mutableMapOf<String, String>()

        private val storageManager = mockk<StorageManager>()

        /**
         * The real provider, over the storage manager below. It is the one under test, not a stand-in: the folder
         * names it builds are the names this tree is built to, so a rename in either place shows up as a failing
         * lookup rather than as a test that quietly stopped describing the layout.
         */
        val provider = DownloadProvider(mockk(relaxed = true), storageManager, libraryPreferences)

        private val names = Naming(provider, NovelSourceAdapter(reachableSource()))

        fun writeChapterFile(content: String) {
            files[names.chapterFile] = content
        }

        val chapterFileName: String get() = names.chapterFile
        val sourceFolderName: String get() = names.sourceFolder
        val mangaFolderName: String get() = names.mangaFolder

        init {
            // Built per call, not once at stub time: a test writes its file after this object exists. The root is
            // the downloads directory itself, with the source folder below it, because that is the shape
            // `StorageManager.getDownloadsDirectory()` hands over and the one the provider walks down.
            coEvery { storageManager.getDownloadsDirectory() } answers {
                fakeTree(
                    DOWNLOADS_DIR_NAME,
                    mapOf(names.sourceFolder to mapOf(names.mangaFolder to files)),
                )
            }
        }

        /**
         * The three names a novel chapter is stored under, as the provider builds them.
         *
         * The folder is named after the *adapter* rather than after the novel source, because that is the object
         * `Downloader` named it after and the only one `SourceManager` hands back for a stored manga row.
         */
        private class Naming(provider: DownloadProvider, adapter: NovelSourceAdapter) {
            val sourceFolder: String = provider.getSourceDirName(adapter)
            val mangaFolder: String = provider.getMangaDirName(MANGA_TITLE)
            val chapterFile: String =
                "${provider.getChapterDirName(CHAPTER_NAME, null, CHAPTER_URL)}.${Downloader.NOVEL_CHAPTER_EXTENSION}"
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The content on disk, and the repositories the chain runs over.
    // ---------------------------------------------------------------------------------------------

    private class FakeContentRepository(initial: Map<Long, ChapterContent>) : ChapterContentRepository {
        private val cached = initial.toMutableMap()
        override suspend fun get(chapterId: Long): ChapterContent? = cached[chapterId]
        override fun getAsFlow(chapterId: Long): Flow<ChapterContent?> = emptyFlow()
        override suspend fun save(chapterId: Long, content: ChapterContent): String = "/dev/null"
        override suspend fun delete(chapterId: Long) {
            cached -= chapterId
        }

        override fun getCachedChapterIdsByMangaId(mangaId: Long): Flow<Set<Long>> = emptyFlow()
    }

    private class FakeProgressRepository : ReadingProgressRepository {
        override suspend fun get(chapterId: Long): ReadingProgress? = null
        override suspend fun save(chapterId: Long, progress: ReadingProgress) = Unit
        override suspend fun delete(chapterId: Long) = Unit
        override fun getPercentByMangaId(mangaId: Long): Flow<Map<Long, Float>> = emptyFlow()
    }

    private companion object {
        const val MANGA_ID = 1L
        const val CHAPTER_ID = 3L
        const val CHAPTER_NAME = "Capítulo 1"
        const val CHAPTER_URL = "/chapter/1"
        const val MANGA_TITLE = "La torre de cristal"
        const val SOURCE_ID = "http-fixtures"
        const val NOVEL_ID = "novel-1"

        /** A port nothing listens on: the interceptor throws before any of this is ever dialled. */
        const val BASE_URL = "http://127.0.0.1:1/"

        /** What the storage manager hands over; only its children are ever asked for by name. */
        const val DOWNLOADS_DIR_NAME = "downloads"

        val OPEN_TIMEOUT = 20.seconds

        val DOWNLOADED_BLOCKS = listOf(
            ContentBlock.Heading("Prólogo", level = 1),
            ContentBlock.Paragraph("El edificio llevaba sesenta anos esperando a que alguien subiera."),
            ContentBlock.Divider,
            ContentBlock.Image("https://example.invalid/cover.jpg", "Portada"),
            ContentBlock.Heading("Capítulo", level = 2),
        )

        /**
         * The file as the user has it on disk: exactly what `toPlainText` writes, spelled out here so this test
         * describes what a user actually has rather than trusting the writer to still agree with itself.
         */
        const val DOWNLOADED_TEXT = "# Prólogo\n\n" +
            "El edificio llevaba sesenta anos esperando a que alguien subiera.\n\n" +
            "---\n\n" +
            "![Portada](https://example.invalid/cover.jpg)\n\n" +
            "## Capítulo\n"

        val CACHED_CONTENT = ChapterContent(
            blocks = listOf(ContentBlock.Paragraph("A stale copy from the content cache.")),
        )

        val manga = Manga.create().copy(
            id = MANGA_ID,
            title = MANGA_TITLE,
            source = MANGA_SOURCE_ID,
            url = NOVEL_ID,
        )

        val libraryPreferences: LibraryPreferences = LibraryPreferences(InMemoryPreferenceStore()).also {
            it.disallowNonAsciiFilenames.set(false)
            it.enableChapterNameHash.set(true)
        }
    }
}

/** `manga.source_id`, as `NovelMapper` writes it for a novel source. */
private val MANGA_SOURCE_ID: Long = app.novel.runtime.NovelSourceId.generate("http-fixtures")

/** The source `DownloadProvider.getSourceDirName` is asked about, without a client that throws. */
private fun reachableSource(): NovelSource = HttpNovelSource(
    baseUrl = "http://127.0.0.1:1/",
    httpClient = OkHttpClient.Builder().build(),
)

/**
 * A downloads tree of mocked [UniFile]s, addressed by name the way the real one is.
 *
 * A `Map` value is a folder and a `String` is a file holding that text, which is the smallest shape that can answer
 * `findFile` and `openInputStream` at every level the read path walks down.
 */
private fun fakeTree(name: String, node: Any): UniFile {
    val children: Map<String, Any> = buildMap {
        (node as? Map<*, *>)?.forEach { (key, value) ->
            if (key != null && value != null) put(key.toString(), value)
        }
    }
    return mockk<UniFile>(relaxed = true).also { file ->
        every { file.name } returns name
        every { file.isDirectory } returns children.isNotEmpty()
        every { file.isFile } returns children.isEmpty()
        every { file.findFile(any()) } answers {
            children.entries.firstOrNull { it.key == firstArg<String>() }?.let { fakeTree(it.key, it.value) }
        }
        every { file.listFiles() } returns children.map { fakeTree(it.key, it.value) }.toTypedArray()
        if (node is String) {
            every { file.openInputStream() } answers { ByteArrayInputStream(node.toByteArray(Charsets.UTF_8)) }
        }
    }
}
