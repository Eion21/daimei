package tachiyomi.domain.novel.repository

import app.novel.source.api.ChapterContent
import kotlinx.coroutines.flow.Flow

interface ChapterContentRepository {
    suspend fun get(chapterId: Long): ChapterContent?
    fun getAsFlow(chapterId: Long): Flow<ChapterContent?>
    suspend fun save(chapterId: Long, content: ChapterContent): String
    suspend fun delete(chapterId: Long)

    /**
     * The ids of the chapters of [mangaId] whose content is cached on disk.
     *
     * Downloading a novel chapter *is* writing a [ChapterContent], so this is what "downloaded" means for one:
     * the chapter list reads it to show the state without opening anything, and it re-emits on its own so a
     * chapter cached in the reader turns into a downloaded row without the screen being rebuilt.
     *
     * A flow rather than a one-shot read, and a set rather than a list, because membership is the only question
     * asked of it. The result is a snapshot: a chapter deleted underneath a caller stays in the set it was
     * handed, which is the same guarantee the rest of this interface gives.
     */
    fun getCachedChapterIdsByMangaId(mangaId: Long): Flow<Set<Long>>
}
