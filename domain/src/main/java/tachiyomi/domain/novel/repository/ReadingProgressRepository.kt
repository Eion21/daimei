package tachiyomi.domain.novel.repository

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
}
