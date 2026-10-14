package tachiyomi.domain.novel.repository

import kotlinx.coroutines.flow.Flow

/**
 * Where the reader stopped in a chapter.
 *
 * [blockFraction] is how far through [blockIndex] the viewport had scrolled, from 0 at the top to 1 at the
 * bottom. It is what makes the position survive a font-size change: a pixel offset would land somewhere else on
 * the page as soon as the text reflowed.
 */
data class ReadingProgress(
    val blockIndex: Int,
    val blockOffsetPx: Int,
    val blockFraction: Float = 0f,
    val percent: Float,
)

interface ReadingProgressRepository {
    suspend fun get(chapterId: Long): ReadingProgress?
    suspend fun save(chapterId: Long, progress: ReadingProgress)
    suspend fun delete(chapterId: Long)

    /**
     * How far through its chapter each of [mangaId]'s chapters was read, as [ReadingProgress.percent] from 0 to 1.
     *
     * Keyed by chapter id, and only chapters that have a saved position appear at all: a manga whose chapters are
     * all images has nothing in the table, so it comes back empty rather than as a row of zeroes.
     *
     * Observable rather than a one-shot read so a position saved while the chapter list is on screen shows up
     * without the screen having to be reopened. [save] and [delete] notify the same table this query reads, so
     * there is no second place to ask.
     */
    fun getPercentByMangaId(mangaId: Long): Flow<Map<Long, Float>>
}
