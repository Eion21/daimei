package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.net.Uri
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import nl.adaptivity.xmlutil.serialization.XML
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.novel.interactor.GetChapterContent
import tachiyomi.domain.novel.repository.ChapterContentRepository
import tachiyomi.domain.storage.service.StorageManager
import java.io.ByteArrayOutputStream

/**
 * What a novel chapter is downloaded as: the file it writes, the file it refuses to write, the second copy it
 * does not write, and the one the delete takes back.
 *
 * The failure this guards is the one the fork shipped with. `Downloader.downloadChapter` asked every chapter for a
 * page list, and `NovelSourceAdapter.getPageList` throws by design, so downloading a novel chapter reached the
 * error path with nothing written and nothing said — which is what the user saw as a download that silently did
 * nothing. The source in this file refuses to hand out pages in every test, so a path that still asks for them
 * fails loudly instead of passing by never looking.
 *
 * The provider is a real one, spied only where the download has to be pointed at a fake folder: the directory
 * name a chapter is downloaded under is what ties the write, the index and the delete together, and a made-up one
 * would let all three drift apart without any of them noticing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NovelChapterDownloadTest {

    private val storageManager = mockk<StorageManager>(relaxed = true)
    private val provider = spyk(
        DownloadProvider(mockk(relaxed = true), storageManager, libraryPreferences),
    )
    private val cache = mockk<DownloadCache>(relaxed = true)
    private val notifier = mockk<DownloadNotifier>(relaxed = true)
    private val getChapterContent = mockk<GetChapterContent>()
    private val source = mockk<HttpSource>()
    private val chapterContentRepository = mockk<ChapterContentRepository>(relaxed = true)

    private val manga = Manga.create().copy(id = MANGA_ID, title = "La torre de cristal", source = SOURCE_ID)

    /** What the downloader, the index and the delete all have to agree on for a chapter to be found again. */
    private val chapterDirName = downloadProvider.getChapterDirName(CHAPTER_NAME, null, CHAPTER_URL)
    private val novelFileName = "$chapterDirName.txt"

    @BeforeEach
    fun setUp() {
        // A download folder is named after `Source.toString()`, and a mock's own string carries an id that the
        // harness would have to guess at to stub a lookup by. Spelled out here the folder name is a fact both
        // halves of the test agree on.
        every { source.toString() } returns "Novel Web (ES)"
        coEvery { source.getPageList(any()) } throws NO_PAGES
        // The manga-level delete asks the repository which rows a manga has. A relaxed mock would hand back a flow
        // that never emits, so the delete would hang instead of finishing with nothing to clear.
        coEvery { chapterContentRepository.getCachedChapterIdsByMangaId(any()) } returns flowOf(emptySet())
    }

    @Test
    fun `a novel chapter is written to the download folder as one text file`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val dir = FakeMangaDir()
            coEvery { provider.getMangaDir(any(), any()) } returns Result.success(dir.uniFile)
            coEvery { getChapterContent.await(CHAPTER_ID) } returns chapterContent()
            val download = downloadFor(novelChapter())

            downloader().downloadChapter(download, Semaphore(1), CompletableDeferred())

            assertEquals(Download.State.DOWNLOADED, download.status)
            // Written under the temp name and renamed, so a download killed mid-write cannot leave behind a `.txt`
            // the index would read as a chapter that finished
            assertEquals(listOf("$novelFileName$TMP"), dir.written.keys.toList())
            assertEquals(listOf("$novelFileName$TMP" to novelFileName), dir.renames)
            assertEquals("# Capítulo 1\n\nLa torre de cristal.", dir.written.values.single().text())
            // The index has to hear about it, or the file on disk and the library disagree
            coVerify { cache.addChapter(chapterDirName, dir.uniFile, manga) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a novel download never asks its source for pages`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val dir = FakeMangaDir()
            coEvery { provider.getMangaDir(any(), any()) } returns Result.success(dir.uniFile)
            coEvery { getChapterContent.await(CHAPTER_ID) } returns chapterContent()

            downloader().downloadChapter(downloadFor(novelChapter()), Semaphore(1), CompletableDeferred())

            coVerify(exactly = 0) { source.getPageList(any()) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a novel chapter with no content is an error rather than an empty file`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val dir = FakeMangaDir()
            coEvery { provider.getMangaDir(any(), any()) } returns Result.success(dir.uniFile)
            // `GetChapterContent` answers null for a chapter it could neither read nor fetch
            coEvery { getChapterContent.await(CHAPTER_ID) } returns null
            val download = downloadFor(novelChapter())

            downloader().downloadChapter(download, Semaphore(1), CompletableDeferred())

            assertEquals(Download.State.ERROR, download.status)
            // It reached the novel path to find that out. The buggy path also ended in ERROR, by asking a novel
            // source for pages, so the error alone would not have told the two apart.
            coVerify(exactly = 1) { getChapterContent.await(CHAPTER_ID) }
            coVerify(exactly = 0) { source.getPageList(any()) }
            // An empty `.txt` is indistinguishable from a downloaded chapter to the index and to the user, so the
            // miss has to surface instead of being filed away
            assertTrue(dir.written.isEmpty(), "an empty text file was written for a chapter with no content")
            assertTrue(dir.renames.isEmpty(), "a file was published for a chapter with no content")
            coVerify(exactly = 0) { cache.addChapter(any(), any(), any()) }
            verify { notifier.onError(any(), any(), any(), any()) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a novel chapter that is already on disk is not written a second time`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val dir = FakeMangaDir(novelFileName)
            coEvery { provider.getMangaDir(any(), any()) } returns Result.success(dir.uniFile)
            val download = downloadFor(novelChapter())

            downloader().downloadChapter(download, Semaphore(1), CompletableDeferred())

            assertEquals(Download.State.DOWNLOADED, download.status)
            assertTrue(dir.written.isEmpty(), "a second copy was written next to the first")
            // Not even fetched: the file on disk already is the download
            coVerify(exactly = 0) { getChapterContent.await(any()) }
            coVerify { cache.addChapter(chapterDirName, dir.uniFile, manga) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a manga chapter still asks its source for pages`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // A text file standing where a manga's chapter should be is not a download. Recognising it for a
            // manga would skip the download that never happened, which is the regression that extending the
            // shared already-downloaded short circuit with `.txt` could have introduced.
            val dir = FakeMangaDir(novelFileName)
            coEvery { provider.getMangaDir(any(), any()) } returns Result.success(dir.uniFile)
            val download = downloadFor(imageChapter())

            downloader().downloadChapter(download, Semaphore(1), CompletableDeferred())

            coVerify(exactly = 1) { source.getPageList(any()) }
            coVerify(exactly = 0) { getChapterContent.await(any()) }
            // `getPageList` threw, so this proves the chapter took the image path rather than the novel one
            assertEquals(Download.State.ERROR, download.status)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a renamed novel chapter keeps its extension`() = runTest {
        // A sync that renames a chapter moves the file. Dropping the extension on the way would leave a name no
        // lookup asks for: the chapter would be undeletable and invisible from the moment it was renamed, and
        // nothing would say why.
        val dir = FakeMangaDir(novelFileName)
        val manager = downloadManager(dir)
        coEvery { provider.getMangaDir(any(), any()) } returns Result.success(dir.uniFile)
        val renamed = novelChapter().copy(name = "Capítulo 2")

        manager.renameChapter(source, manga, novelChapter(), renamed)

        assertEquals(
            listOf(novelFileName to "${provider.getChapterDirName(RENAMED_NAME, null, CHAPTER_URL)}.txt"),
            dir.renames,
            "the text file was renamed to something the chapter is no longer found under",
        )
    }

    @Test
    fun `a renamed manga chapter still keeps its cbz extension`() = runTest {
        val cbzName = "$chapterDirName.cbz"
        val dir = FakeMangaDir(cbzName)
        val manager = downloadManager(dir)
        coEvery { provider.getMangaDir(any(), any()) } returns Result.success(dir.uniFile)
        val renamed = imageChapter().copy(name = RENAMED_NAME)

        manager.renameChapter(source, manga, imageChapter(), renamed)

        assertEquals(
            listOf(cbzName to "${provider.getChapterDirName(RENAMED_NAME, null, CHAPTER_URL)}.cbz"),
            dir.renames,
        )
    }

    @Test
    fun `deleting a downloaded novel chapter takes its text file with it`() = runTest {
        val dir = FakeMangaDir(novelFileName)
        val manager = downloadManager(dir)

        manager.deleteChapters(listOf(novelChapter()), manga, source)

        assertTrue(
            dir.deleted.contains(novelFileName),
            "the text file survived a delete that had resolved a chapter to delete",
        )
    }

    @Test
    fun `deleting a downloaded novel chapter drops the content row that makes it readable`() = runTest {
        // The download wrote two things: the file the user finds in the folder and the row that opens the chapter
        // with no network. A delete that took only the first leaves the chapter readable with the server off, which
        // is the exact state "deleted" is supposed to mean the end of.
        val dir = FakeMangaDir(novelFileName)
        val manager = downloadManager(dir)

        manager.deleteChapters(listOf(novelChapter()), manga, source)

        coVerify(exactly = 1) { chapterContentRepository.delete(CHAPTER_ID) }
    }

    @Test
    fun `deleting a manga chapter never touches a content row`() = runTest {
        val dir = FakeMangaDir()
        val manager = downloadManager(dir)

        manager.deleteChapters(listOf(imageChapter()), manga, source)

        coVerify(exactly = 0) { chapterContentRepository.delete(any()) }
    }

    @Test
    fun `deleting a whole novel takes its folder and every content row it had`() = runTest {
        // The manga-level delete, which is what the "delete downloads?" prompt now runs for a novel too. It
        // takes the whole folder, so the text files in it go with it, and it has to clear the content rows as
        // well: they live outside that folder, and leaving them behind is how a novel deleted from disk kept
        // opening with the server off, forever. This is also the path that runs when a novel leaves the library.
        val dir = FakeMangaDir(novelFileName)
        val manager = downloadManager(dir)
        coEvery { provider.findMangaDir(any(), any()) } returns dir.uniFile
        coEvery { chapterContentRepository.getCachedChapterIdsByMangaId(MANGA_ID) } returns flowOf(
            setOf(CHAPTER_ID, OTHER_CHAPTER_ID),
        )

        manager.deleteManga(manga, source)

        assertTrue(
            dir.deleted.contains(FakeMangaDir.MANGA_DIR_NAME),
            "the manga's download folder survived, so every text file in it survived with it",
        )
        coVerify(exactly = 1) { chapterContentRepository.delete(CHAPTER_ID) }
        coVerify(exactly = 1) { chapterContentRepository.delete(OTHER_CHAPTER_ID) }
        coVerify(exactly = 1) { cache.removeManga(manga) }
    }

    @Test
    fun `deleting a manga chapter leaves a text file it never wrote alone`() = runTest {
        val dir = FakeMangaDir(novelFileName)
        val manager = downloadManager(dir)

        manager.deleteChapters(listOf(imageChapter()), manga, source)

        assertTrue(dir.deleted.isEmpty(), "a manga delete removed a text file it had no claim to")
    }

    @Test
    fun `a deleted novel chapter stops counting as downloaded`() = runTest {
        // The index holds one entry per chapter whatever shape the file took, so removing it is the same act for
        // both — but only if the name the delete hands the index is the name the write put there.
        val dir = FakeMangaDir(novelFileName)
        val manager = downloadManager(dir)

        manager.deleteChapters(listOf(novelChapter()), manga, source)

        coVerify(exactly = 1) { cache.removeChapters(listOf(novelChapter()), manga) }
        coVerify(exactly = 0) { cache.addChapter(any(), any(), any()) }
    }

    private fun TestScope.downloader(): Downloader = Downloader(
        scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        context = mockk<Context>(relaxed = true),
        provider = provider,
        cache = cache,
        sourceManager = mockk(relaxed = true),
        chapterCache = mockk(relaxed = true),
        downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
        xml = mockk<XML>(relaxed = true),
        getCategories = mockk(relaxed = true),
        getTracks = mockk(relaxed = true),
        store = mockk(relaxed = true),
        notifier = notifier,
        getChapterContent = getChapterContent,
    )

    /**
     * A manager whose provider really finds [dir], rather than one whose lookup is stubbed to an answer.
     *
     * The delete used to reach a novel's text file through a second, private lookup that bypassed the provider,
     * because the provider could not see a `.txt`. It can now, so this wires the real folder up behind it: the
     * test then covers the same path production takes, and stops passing if the naming ever loses the extension
     * again — which is the failure the bypass was hiding.
     */
    private fun downloadManager(dir: FakeMangaDir): DownloadManager {
        // The source folder is handed over directly rather than reached through the downloads directory: a source
        // folder is named after `Source.toString()`, and a mock's own string is not a name a test can rely on.
        // Everything below it — the manga folder, the chapter file inside it, the lookup itself — is real.
        coEvery { provider.findSourceDir(any()) } returns dir.sourceFolderOf(provider.getMangaDirName(manga.title))
        return DownloadManager(
            context = mockk<Context>(relaxed = true),
            provider = provider,
            cache = cache,
            getCategories = mockk(relaxed = true),
            getManga = mockk(relaxed = true),
            getChapter = mockk(relaxed = true),
            sourceManager = mockk(relaxed = true),
            downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
            downloader = mockk(relaxed = true),
            pendingDeleter = mockk(relaxed = true),
            chapterContentRepository = chapterContentRepository,
        )
    }

    private fun downloadFor(chapter: Chapter) = Download(source, manga, chapter)

    /** Carries the marker `ChapterOpener` reads, which `NovelMapper` writes when the chapter is listed. */
    private fun novelChapter() = Chapter.create().copy(
        id = CHAPTER_ID,
        mangaId = MANGA_ID,
        name = CHAPTER_NAME,
        url = CHAPTER_URL,
        memo = buildJsonObject { put("novelSourceId", JsonPrimitive("demo")) },
    )

    private fun imageChapter() = Chapter.create().copy(
        id = CHAPTER_ID,
        mangaId = MANGA_ID,
        name = CHAPTER_NAME,
        url = CHAPTER_URL,
    )

    private fun chapterContent() = ChapterContent(
        blocks = listOf(
            ContentBlock.Heading("Capítulo 1", level = 1),
            ContentBlock.Paragraph("La torre de cristal."),
        ),
    )

    private companion object {
        const val MANGA_ID = 1L
        const val SOURCE_ID = 2L
        const val CHAPTER_ID = 3L
        const val OTHER_CHAPTER_ID = 4L
        const val CHAPTER_NAME = "Capítulo 1"
        const val RENAMED_NAME = "Capítulo 2"
        const val CHAPTER_URL = "/chapter/1"
        const val TMP = Downloader.TMP_DIR_SUFFIX

        val NO_PAGES = IllegalStateException("a novel chapter has no pages to list")

        /**
         * Real naming, so the name the download writes is the name the index and the delete look for. Only the
         * naming rules are shared with [provider]; the provider the tests use is wired to their own fake folder.
         */
        val libraryPreferences: LibraryPreferences = LibraryPreferences(InMemoryPreferenceStore()).also {
            it.disallowNonAsciiFilenames.set(false)
            it.enableChapterNameHash.set(true)
        }

        val downloadProvider: DownloadProvider = DownloadProvider(
            mockk(relaxed = true),
            mockk(relaxed = true),
            libraryPreferences,
        )

        fun ByteArrayOutputStream.text() = toString(Charsets.UTF_8)
    }
}

/**
 * One manga's download directory, as much of a [UniFile] as the download and delete paths touch.
 *
 * `findFile` answers by exact name, `createFile` hands back a file that records what was written to it and where
 * it was renamed to, and deletes are recorded by name. That is enough to tell a download that wrote its file from
 * one that decided it had nothing to write, without a real filesystem underneath it.
 */
private class FakeMangaDir(vararg existingNames: String) {

    /** What each created file was handed, keyed by the name it was created under. */
    val written = mutableMapOf<String, ByteArrayOutputStream>()

    /** `created name to published name`, so a temp file is tellable apart from the file the user ends up with. */
    val renames = mutableListOf<Pair<String, String>>()

    /** Names of the entries that were deleted. */
    val deleted = mutableListOf<String>()

    private val existing = existingNames.associateWith(::file)

    val uniFile: UniFile = mockk<UniFile>(relaxed = true).also { dir ->
        // Named so a folder delete is tellable apart from a file delete: the manga-level delete removes the
        // folder, and the test has to be able to say so rather than only seeing some delete happen
        every { dir.name } returns MANGA_DIR_NAME
        every { dir.isDirectory } returns true
        every { dir.isFile } returns false
        every { dir.delete() } answers {
            deleted += MANGA_DIR_NAME
            true
        }
        existing.forEach { (name, file) -> every { dir.findFile(name) } returns file }
        // Explicitly null, not left to the relaxed default: a relaxed mock answers a lookup that misses with a
        // child mock, so a folder built the obvious way reports every chapter as already on disk
        every { dir.findFile(match { it !in existing }) } returns null
        // Never empty for a folder that has something in it, so the "delete the manga folder once it is empty"
        // branch in `deleteChapters` is not what is under test here
        every { dir.listFiles() } returns existing.values.toTypedArray()
        every { dir.createFile(any()) } answers { file(firstArg<String>()) }
        // Anything but a document tree, so the provider looks the chapter up name by name
        every { dir.uri } returns mockk<Uri>(relaxed = true).also { every { it.scheme } returns "file" }
    }

    /**
     * The source folder this manga's folder sits in, reachable under [mangaDirName] and nowhere else.
     *
     * Each name is stubbed on its own and every other name is stubbed to null on purpose: a relaxed mock answers a
     * lookup that misses with a child mock, so a folder built the obvious way reports every chapter as already on
     * disk and the delete this file is about would find nothing to delete for the wrong reason.
     */
    fun sourceFolderOf(mangaDirName: String): UniFile = mockk<UniFile>(relaxed = true).also { dir ->
        every { dir.findFile(mangaDirName) } returns uniFile
        every { dir.findFile(match { it != mangaDirName }) } returns null
        every { dir.listFiles() } returns arrayOf(uniFile)
    }

    private fun file(name: String): UniFile = mockk<UniFile>(relaxed = true).also { file ->
        every { file.name } returns name
        every { file.isFile } returns true
        every { file.isDirectory } returns false
        every { file.delete() } answers {
            deleted += name
            true
        }
        every { file.openOutputStream() } answers { ByteArrayOutputStream().also { written[name] = it } }
        every { file.renameTo(any()) } answers {
            renames += name to firstArg<String>()
            true
        }
    }

    companion object {
        const val MANGA_DIR_NAME = "La torre de cristal"
    }
}
