package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * What `DownloadCache` counts as a downloaded chapter once a novel chapter is downloaded as a text file.
 *
 * The index answers "is this chapter downloaded" by comparing the names it collected from a manga's folder with
 * the names `DownloadProvider.getValidChapterDirNames` says that chapter could have been downloaded under. Both
 * sides of that comparison are exercised here against the real provider, because a `.txt` is only reported as
 * downloaded when the name it is indexed under is one of those names — and the risk of accepting one is a folder
 * that starts answering for chapters it never downloaded.
 *
 * The manga shapes are covered too, because the same branch reads every entry in every manga's folder.
 */
class DownloadCacheNovelIndexTest {

    @Test
    fun `a novel chapter's text file is indexed under a name the chapter is looked up by`() {
        val chapterDirName = provider.getChapterDirName(CHAPTER_NAME, null, CHAPTER_URL)

        val indexed = chapterDirNameOrNull(file("$chapterDirName.txt"))

        assertEquals(chapterDirName, indexed)
        // The lookup itself, in the shape `getDownloadedChapterIds` and `isChapterDownloaded` perform it
        assertTrue(
            provider.getValidChapterDirNames(CHAPTER_NAME, null, CHAPTER_URL).contains(indexed),
            "the index collected a name the chapter would never be asked under",
        )
    }

    @Test
    fun `a text file that is not a chapter cannot make a manga look downloaded`() {
        val indexed = chapterDirNameOrNull(file("notes.txt"))

        assertTrue(
            provider.getValidChapterDirNames(CHAPTER_NAME, null, CHAPTER_URL).none { it == indexed },
            "a text file whose name matches no chapter of this manga was indexed as one",
        )
    }

    @Test
    fun `a manga chapter's cbz and its page folder are still indexed`() {
        val chapterDirName = provider.getChapterDirName(CHAPTER_NAME, null, CHAPTER_URL)

        assertEquals(chapterDirName, chapterDirNameOrNull(file("$chapterDirName.cbz")))
        assertEquals(chapterDirName, chapterDirNameOrNull(file(chapterDirName, isDirectory = true)))
    }

    @Test
    fun `an unfinished download is still ignored`() {
        val chapterDirName = provider.getChapterDirName(CHAPTER_NAME, null, CHAPTER_URL)

        // The temp names both formats go through, so a download killed mid-write is never counted as a chapter
        assertNull(chapterDirNameOrNull(file("$chapterDirName${Downloader.TMP_DIR_SUFFIX}", isDirectory = true)))
        assertNull(chapterDirNameOrNull(file("$chapterDirName.txt${Downloader.TMP_DIR_SUFFIX}")))
    }

    @Test
    fun `anything that is not a chapter is still irrelevant`() {
        // A manga folder also carries what the app puts there itself, none of which is a chapter
        assertNull(chapterDirNameOrNull(file(".nomedia")))
        assertNull(chapterDirNameOrNull(file("ComicInfo.xml")))
        assertNull(chapterDirNameOrNull(file("cover.jpg")))
    }

    private fun file(name: String, isDirectory: Boolean = false): UniFile = mockk<UniFile>(relaxed = true).also {
        every { it.name } returns name
        every { it.isFile } returns !isDirectory
        every { it.isDirectory } returns isDirectory
    }

    private companion object {
        const val CHAPTER_NAME = "Capítulo 1"
        const val CHAPTER_URL = "/chapter/1"

        /** The real naming, so what the index collects is checked against the names a download actually uses. */
        val provider: DownloadProvider = run {
            val preferences = LibraryPreferences(InMemoryPreferenceStore())
            preferences.disallowNonAsciiFilenames.set(false)
            preferences.enableChapterNameHash.set(true)
            DownloadProvider(mockk(relaxed = true), mockk(relaxed = true), preferences)
        }
    }
}
