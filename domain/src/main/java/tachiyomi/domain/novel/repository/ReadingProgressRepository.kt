package tachiyomi.domain.novel.repository

data class ReadingProgress(val blockIndex: Int, val blockOffsetPx: Int, val percent: Float)

interface ReadingProgressRepository {
    suspend fun get(chapterId: Long): ReadingProgress?
    suspend fun save(chapterId: Long, progress: ReadingProgress)
    suspend fun delete(chapterId: Long)
}
