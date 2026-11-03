package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

class DownloadManagerNovelRenameTest {
    private val source = mockk<Source>(relaxed = true) {
        every { name } returns "Source"
        every { lang } returns "en"
    }
    private val manga = Manga.create().copy(id = 1, source = 2, title = "Book")
    private val preferences = LibraryPreferences(InMemoryPreferenceStore()).also {
        it.enableChapterNameHash.set(false)
        it.disallowNonAsciiFilenames.set(false)
    }
    private val storage = mockk<StorageManager>()
    private val provider = DownloadProvider(mockk(relaxed = true), storage, preferences)
    private val cache = mockk<DownloadCache>(relaxed = true)
    private val manager = DownloadManager(
        context = mockk(relaxed = true), provider = provider, cache = cache,
        getCategories = mockk(relaxed = true), getManga = mockk(relaxed = true),
        getChapter = mockk(relaxed = true), sourceManager = mockk(relaxed = true),
        downloadPreferences = mockk(relaxed = true), downloader = mockk(relaxed = true),
        pendingDeleter = mockk(relaxed = true), chapterContentRepository = mockk(relaxed = true),
    )

    private fun chapter(name: String, novel: Boolean = false) = Chapter.create().copy(
        id = 3,
        mangaId = 1,
        name = name,
        url = "/chapter",
        memo = if (novel) buildJsonObject { put("novelSourceId", JsonPrimitive("demo")) } else Chapter.create().memo,
    )

    /** Provider naming is real; UniFile bridges rename and lookup to a real isolated filesystem. */
    private fun tree(dir: File, failRename: Boolean = false): UniFile {
        val mangaDir = mockk<UniFile>()
        val sourceDir = mockk<UniFile>()
        val root = mockk<UniFile>()
        every { storage.getDownloadsDirectory() } returns root
        every { root.createDirectory(any()) } returns sourceDir
        every { sourceDir.createDirectory(any()) } returns mangaDir
        every { mangaDir.findFile(any()) } answers {
            val file = File(dir, firstArg<String>())
            if (!file.exists()) {
                null
            } else {
                mockk<UniFile> {
                    every { name } answers { file.name }
                    every { isFile } answers { file.isFile }
                    every { renameTo(any()) } answers {
                        if (failRename) false else file.renameTo(File(dir, firstArg<String>()))
                    }
                }
            }
        }
        return mangaDir
    }

    @Test
    fun `novel rename preserves text file and caches bare identifier`(@TempDir dir: File) = runTest {
        val mangaDir = tree(dir)
        val old = chapter("Old", novel = true)
        val new = chapter("New", novel = true)
        val oldName = provider.getChapterDirName(old.name, old.scanlator, old.url)
        val newName = provider.getChapterDirName(new.name, new.scanlator, new.url)
        File(dir, "$oldName.txt").writeText("novel body")

        manager.renameChapter(source, manga, old, new)

        assertFalse(File(dir, "$oldName.txt").exists())
        assertEquals("novel body", File(dir, "$newName.txt").readText())
        coVerify(exactly = 1) { cache.removeChapter(old, manga) }
        coVerify(exactly = 1) { cache.addChapter(newName, mangaDir, manga) }
        coVerify(exactly = 1) { cache.addChapter(any(), any(), any()) }
    }

    @Test
    fun `cbz rename preserves filename content and cache argument`(@TempDir dir: File) = runTest {
        val mangaDir = tree(dir)
        val old = chapter("Old")
        val new = chapter("New")
        File(dir, "Old.cbz").writeText("archive")
        manager.renameChapter(source, manga, old, new)
        assertFalse(File(dir, "Old.cbz").exists())
        assertEquals("archive", File(dir, "New.cbz").readText())
        coVerify(exactly = 1) { cache.removeChapter(old, manga) }
        coVerify(exactly = 1) { cache.addChapter("New.cbz", mangaDir, manga) }
        coVerify(exactly = 1) { cache.addChapter(any(), any(), any()) }
        println("cbz: Old.cbz -> New.cbz; content=archive; remove=1; add=New.cbz:1")
    }

    @Test
    fun `directory rename retains literal txt and existing cache argument`(@TempDir dir: File) = runTest {
        val mangaDir = tree(dir)
        val old = chapter("Old.txt")
        val new = chapter("New.txt")
        File(dir, "Old.txt").mkdirs()
        File(dir, "Old.txt/page").writeText("page")
        manager.renameChapter(source, manga, old, new)
        assertFalse(File(dir, "Old.txt").exists())
        assertEquals("page", File(dir, "New.txt/page").readText())
        coVerify(exactly = 1) { cache.removeChapter(old, manga) }
        coVerify(exactly = 1) { cache.addChapter("New.txt", mangaDir, manga) }
        coVerify(exactly = 1) { cache.addChapter(any(), any(), any()) }
        println("directory: Old.txt -> New.txt; content=page; remove=1; add=New.txt:1")
    }

    @Test
    fun `unchanged name does not touch disk or cache`(@TempDir dir: File) = runTest {
        tree(dir)
        val old = chapter("Old")
        File(dir, "Old.cbz").writeText("archive")
        manager.renameChapter(source, manga, old, old)
        assertEquals("archive", File(dir, "Old.cbz").readText())
        coVerify(exactly = 0) { cache.removeChapter(any(), any()) }
        coVerify(exactly = 0) { cache.addChapter(any(), any(), any()) }
        println("noop: Old.cbz; content=archive; remove=0; add=0")
    }

    @Test
    fun `failed rename does not touch disk or cache`(@TempDir dir: File) = runTest {
        tree(dir, failRename = true)
        val old = chapter("Old")
        File(dir, "Old.cbz").writeText("archive")
        manager.renameChapter(source, manga, old, chapter("New"))
        assertEquals("archive", File(dir, "Old.cbz").readText())
        assertFalse(File(dir, "New.cbz").exists())
        assertTrue(File(dir, "Old.cbz").isFile)
        coVerify(exactly = 0) { cache.removeChapter(any(), any()) }
        coVerify(exactly = 0) { cache.addChapter(any(), any(), any()) }
        println("failed: Old.cbz; content=archive; New.cbz absent; remove=0; add=0")
    }
}
