package eu.kanade.tachiyomi.data.download

import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager

/**
 * What `DownloadProvider` knows about a novel chapter's file, which is the naming half of every "is this chapter
 * downloaded" answer the app gives.
 *
 * The index holds bare chapter names, so `DownloadCache` finds a novel chapter already. What the index cannot do
 * is find the *file*: `DownloadProvider.findChapterDir` asks the manga folder for each name in turn, and a text
 * file called `Chapter 1_a1b2c3.txt` is not answerable to `Chapter 1_a1b2c3`. That lookup is what the delete runs,
 * what the rename runs and what reindexing runs, so without the extension none of them ever sees a novel's
 * download and the file is unreachable from the app that wrote it.
 *
 * The other half of the contract is that a manga must not gain the extension. The names are asked of a chapter by
 * name only, and nothing in that name says whether the entry is prose or pixels: a text file that happens to sit
 * where a manga's chapter should be would otherwise answer for a chapter that was never downloaded, and the
 * download that never happened could never be asked for again.
 */
class DownloadProviderNovelNamesTest {

    // The names themselves.

    @Test
    fun `a novel chapter is found under the name its text file was written with`() {
        val dirName = provider.getChapterDirName(CHAPTER_NAME, null, CHAPTER_URL)

        assertTrue(
            provider.getValidChapterDirNames(CHAPTER_NAME, null, CHAPTER_URL, isNovelChapter = true)
                .contains("$dirName.txt"),
            "the name the downloader writes is not a name this chapter is looked up under",
        )
    }

    @Test
    fun `a manga chapter is never found under a text file name`() {
        val dirName = provider.getChapterDirName(CHAPTER_NAME, null, CHAPTER_URL)

        assertFalse(
            provider.getValidChapterDirNames(CHAPTER_NAME, null, CHAPTER_URL).contains("$dirName.txt"),
            "a manga chapter is being looked for as a text file, which would let any stray .txt answer for it",
        )
    }

    @Test
    fun `every naming a novel chapter could have been downloaded under answers to a text file`() {
        // The names carry the naming configs the folder may hold from earlier runs, so a novel chapter downloaded
        // before a preference changed has to stay findable exactly like a manga chapter's does.
        val names = provider.getValidChapterDirNames(CHAPTER_NAME, SCANLATOR, CHAPTER_URL, isNovelChapter = true)
        val bareNames = names.filterNot { it.endsWith(".cbz") || it.endsWith(".txt") }

        assertTrue(bareNames.isNotEmpty(), "there are no naming variants to pair a text file with")
        bareNames.forEach {
            assertTrue(
                names.contains("$it.txt"),
                "the naming variant '$it' has no text file name beside it, so that chapter cannot be found",
            )
        }
    }

    @Test
    fun `a manga chapter's cbz and page folder names are untouched`() {
        val names = provider.getValidChapterDirNames(CHAPTER_NAME, null, CHAPTER_URL)

        assertTrue(names.contains(provider.getChapterDirName(CHAPTER_NAME, null, CHAPTER_URL)))
        assertTrue(names.contains("${provider.getChapterDirName(CHAPTER_NAME, null, CHAPTER_URL)}.cbz"))
    }

    // The lookup itself, against a folder that really holds the file.

    @Test
    fun `the chapter lookup finds a novel's text file on disk`() = runTest {
        val dirName = provider.getChapterDirName(CHAPTER_NAME, null, CHAPTER_URL)
        val source = novelSource()
        val tree = downloadsTree(source, "$dirName.txt")

        val found = tree.provider.findChapterDirs(listOf(novelChapter()), manga(), source)

        assertEquals(
            listOf("$dirName.txt"),
            found.second.map { it.name },
            "the delete, the rename and the reindex all go through this lookup and none of them can see the file",
        )
    }

    @Test
    fun `the chapter lookup still finds a manga's cbz and page folder`() = runTest {
        val dirName = provider.getChapterDirName(CHAPTER_NAME, null, CHAPTER_URL)
        // One source instance for both halves: an `HttpSource` folder is named after `toString()`, and a mock's
        // own string is not the same across two instances
        val source = mangaSource()
        val tree = downloadsTree(source, "$dirName.cbz")

        val found = tree.provider.findChapterDirs(listOf(imageChapter()), manga(), source)

        assertEquals(listOf("$dirName.cbz"), found.second.map { it.name })
    }

    @Test
    fun `a text file that is not a chapter's is not found for it`() = runTest {
        val source = novelSource()
        val tree = downloadsTree(source, "notes.txt")

        val found = tree.provider.findChapterDirs(listOf(novelChapter()), manga(), source)

        assertTrue(found.second.isEmpty(), "a text file with no chapter behind it was returned as one")
    }

    @Test
    fun `a novel's text file does not answer for a manga chapter in the same folder`() = runTest {
        // Same folder, same name, opposite answer: this is the whole reason the extension is gated on the chapter.
        val dirName = provider.getChapterDirName(CHAPTER_NAME, null, CHAPTER_URL)
        val source = mangaSource()
        val tree = downloadsTree(source, "$dirName.txt")

        val found = tree.provider.findChapterDirs(listOf(imageChapter()), manga(), source)

        assertTrue(found.second.isEmpty(), "a manga chapter was resolved by a text file it never downloaded")
    }

    // The folder the whole thing lives under.

    @Test
    fun `a novel source's download folder is the same every time it is asked for`() {
        // `Source.toString()` is identity for a class that does not override it, so a folder named after it
        // carries a hash that changes on every launch: the download is written somewhere nothing can find again,
        // and the chapter never counts as downloaded a second time.
        val first = novelSource()
        val second = novelSource()

        assertEquals(first.name, second.name)
        assertEquals(
            provider.getSourceDirName(first),
            provider.getSourceDirName(second),
            "two instances of one novel source resolved to two different download folders",
        )
        assertFalse(
            provider.getSourceDirName(first).contains("NovelSourceAdapter"),
            "the download folder is named after a class, not after the source",
        )
    }

    @Test
    fun `an http source's download folder name is exactly what it was`() {
        val source = mangaSource()

        assertEquals(
            diskSafe(source.toString()),
            provider.getSourceDirName(source),
            "changing how a source becomes a folder name moved every manga download already on disk",
        )
    }

    @Test
    fun `a chapter that was never downloaded is still not found`() {
        assertNull(provider.getChapterDirName(CHAPTER_NAME, null, CHAPTER_URL).takeIf { it.isBlank() })
    }

    // Helpers.

    private fun diskSafe(name: String): String =
        eu.kanade.tachiyomi.util.storage.DiskUtil.buildValidFilename(name, disallowNonAscii = false)

    private fun novelSource(): Source = mockk<Source>(relaxed = true).also {
        every { it.name } returns "Novel Web"
        every { it.lang } returns "es"
    }

    private fun mangaSource(): HttpSource = mockk<HttpSource>(relaxed = true).also {
        every { it.name } returns "Manga Web"
        every { it.lang } returns "es"
    }

    private fun manga() = Manga.create().copy(id = MANGA_ID, title = MANGA_TITLE, source = 42L)

    /** Carries the memo `NovelMapper` writes, which is the only thing that makes a chapter a novel one. */
    private fun novelChapter() = Chapter.create().copy(
        id = 1L,
        mangaId = MANGA_ID,
        name = CHAPTER_NAME,
        url = CHAPTER_URL,
        memo = buildJsonObject { put("novelSourceId", JsonPrimitive("demo")) },
    )

    private fun imageChapter() = Chapter.create().copy(
        id = 1L,
        mangaId = MANGA_ID,
        name = CHAPTER_NAME,
        url = CHAPTER_URL,
    )

    /**
     * A provider whose downloads folder really holds [fileNames] under the manga's own folder, so the lookup runs
     * against a folder rather than against a stub of the lookup.
     *
     * Every name is stubbed individually and every other name is stubbed to null on purpose: a relaxed mock hands
     * back a child mock for a lookup that misses, so a folder built the obvious way answers "found" to a chapter
     * that was never downloaded and this whole file would assert nothing.
     */
    private fun downloadsTree(source: Source, vararg fileNames: String): DownloadsTree {
        val files = fileNames.associateWith { name -> file(name) }
        val mangaDir = folderOf(files)
        val sourceDir = folderOf(mapOf(provider.getMangaDirName(MANGA_TITLE) to mangaDir))
        val downloadsDir = folderOf(mapOf(provider.getSourceDirName(source) to sourceDir))
        return DownloadsTree(
            provider = DownloadProvider(
                context = mockk(relaxed = true),
                storageManager = mockk<StorageManager>(relaxed = true).also {
                    every { it.getDownloadsDirectory() } returns downloadsDir
                },
                libraryPreferences = libraryPreferences,
            ),
        )
    }

    /** A folder holding [entries] by exact name, answering null for anything else. */
    private fun folderOf(entries: Map<String, UniFile>): UniFile = mockk<UniFile>(relaxed = true).also { dir ->
        entries.forEach { (name, file) -> every { dir.findFile(name) } returns file }
        every { dir.findFile(match { it !in entries }) } returns null
        every { dir.listFiles() } returns entries.values.toTypedArray()
        // Anything but a document tree, so the lookup goes name by name as it does for a plain folder
        every { dir.uri } returns mockk<Uri>(relaxed = true).also { every { it.scheme } returns "file" }
    }

    private fun file(name: String): UniFile = mockk<UniFile>(relaxed = true).also {
        every { it.name } returns name
        every { it.isFile } returns true
        every { it.isDirectory } returns false
    }

    private data class DownloadsTree(val provider: DownloadProvider)

    private companion object {
        const val MANGA_ID = 1L
        const val MANGA_TITLE = "La torre de cristal"
        const val CHAPTER_NAME = "Capítulo 1"
        const val SCANLATOR = "Group"
        const val CHAPTER_URL = "/chapter/1"

        val libraryPreferences: LibraryPreferences = LibraryPreferences(InMemoryPreferenceStore()).also {
            it.disallowNonAsciiFilenames.set(false)
            it.enableChapterNameHash.set(true)
        }

        val provider: DownloadProvider =
            DownloadProvider(mockk(relaxed = true), mockk(relaxed = true), libraryPreferences)
    }
}
