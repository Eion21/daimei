package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.data.download.model.Download
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

/**
 * Covers the two pure decisions behind the chapter list's progress subtitle: what an image chapter reports, and
 * what a novel chapter's saved fraction turns into.
 *
 * The rendered string itself is Compose's `stringResource`, which needs a device; what these pin down is the value
 * handed to it and the decision about whether there is one at all, which is where the novel and image paths could
 * silently disagree.
 */
class ChapterReadProgressTest {

    // Novel: the fraction the reader saved becomes a whole percentage.

    @Test
    fun `a saved novel fraction rounds to a whole percentage`() {
        assertEquals(46, novelChapterProgressPercent(read = false, percent = 0.456f))
    }

    @Test
    fun `a novel read to the end reads as one hundred percent`() {
        assertEquals(100, novelChapterProgressPercent(read = false, percent = 1.0f))
    }

    @Test
    fun `a novel position at the top reads as zero percent`() {
        assertEquals(0, novelChapterProgressPercent(read = false, percent = 0.0f))
    }

    @Test
    fun `a novel chapter with no saved position shows nothing`() {
        assertNull(novelChapterProgressPercent(read = false, percent = null))
    }

    @Test
    fun `the percentage rounds to nearest rather than truncating`() {
        // Truncating would report 45% for a reader 45.6% through, and 0% for one at half a percent.
        assertEquals(46, novelChapterProgressPercent(read = false, percent = 0.456f))
        assertEquals(23, novelChapterProgressPercent(read = false, percent = 0.234f))
        assertEquals(1, novelChapterProgressPercent(read = false, percent = 0.005f))
    }

    @Test
    fun `a chapter most of the way through reads as a hundred percent`() {
        // 99.9% is nearer 100 than 99, so this is rounding doing its job and not the clamp reaching in: the clamp
        // is covered by the out-of-range case below.
        assertEquals(100, novelChapterProgressPercent(read = false, percent = 0.999f))
        assertEquals(99, novelChapterProgressPercent(read = false, percent = 0.994f))
    }

    @Test
    fun `a fraction past the end cannot read as more than a hundred percent`() {
        // The reader clamps its own value, so this only guards a hand-edited or corrupt row from reaching the UI.
        assertEquals(100, novelChapterProgressPercent(read = false, percent = 1.5f))
        assertEquals(0, novelChapterProgressPercent(read = false, percent = -0.5f))
    }

    @Test
    fun `a finished novel chapter does not repeat where it stopped`() {
        // Same rule as the page branch: the greyed-out title already says it was read, and every finished novel
        // sitting at 100% would otherwise carry a "Read: 100%" the image list never shows.
        assertNull(novelChapterProgressPercent(read = true, percent = 1.0f))
        assertNull(novelChapterProgressPercent(read = true, percent = 0.521f))
    }

    @Test
    fun `an unread novel at the very end still reports a hundred percent`() {
        // The reader keeps position and read flag apart, so finishing without marking read is a real state and
        // the percentage is the only thing on screen that says so.
        assertEquals(100, novelChapterProgressPercent(read = false, percent = 1.0f))
    }

    // Image: the existing page-based behaviour, unchanged.

    @Test
    fun `an unread image chapter shows the page it stopped on`() {
        assertEquals(3, imageChapterProgressPage(read = false, lastPageRead = 3))
    }

    @Test
    fun `an image chapter never opened shows nothing`() {
        assertNull(imageChapterProgressPage(read = false, lastPageRead = 0))
    }

    @Test
    fun `a finished image chapter does not repeat where it stopped`() {
        assertNull(imageChapterProgressPage(read = true, lastPageRead = 3))
    }

    @Test
    fun `a novel chapter carries its progress beside the page field rather than in it`() {
        // The page slot is the image reader's index and stays 0 for a novel, which is exactly why the percentage
        // needs its own field: overwriting this one would make "page 3" and "30% read" the same value.
        val novelChapter = ChapterList.Item(
            chapter = Chapter.create().copy(id = 9, lastPageRead = 0, read = false),
            downloadState = Download.State.NOT_DOWNLOADED,
            novelReadProgress = 0.3f,
        )

        assertEquals(0, novelChapter.chapter.lastPageRead)
        assertEquals(0.3f, novelChapter.novelReadProgress)
        assertNull(imageChapterProgressPage(novelChapter.chapter.read, novelChapter.chapter.lastPageRead))
        assertEquals(30, novelChapterProgressPercent(novelChapter.chapter.read, novelChapter.novelReadProgress))
    }

    @Test
    fun `an image chapter keeps no novel progress so the page branch is the one that shows`() {
        val imageChapter = ChapterList.Item(
            chapter = Chapter.create().copy(id = 4, lastPageRead = 2, read = false),
            downloadState = Download.State.NOT_DOWNLOADED,
        )

        assertNull(imageChapter.novelReadProgress)
        assertEquals(2, imageChapterProgressPage(imageChapter.chapter.read, imageChapter.chapter.lastPageRead))
    }
}
