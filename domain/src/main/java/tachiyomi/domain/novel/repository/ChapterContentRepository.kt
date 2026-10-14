package tachiyomi.domain.novel.repository

import app.novel.source.api.ChapterContent
import kotlinx.coroutines.flow.Flow

interface ChapterContentRepository {
    suspend fun get(chapterId: Long): ChapterContent?
    fun getAsFlow(chapterId: Long): Flow<ChapterContent?>
    suspend fun save(chapterId: Long, content: ChapterContent): String
    suspend fun delete(chapterId: Long)
}
