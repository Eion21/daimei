package tachiyomi.domain.novel.interactor

import app.novel.source.api.ChapterContent
import dev.zacsweers.metro.Inject
import tachiyomi.domain.novel.repository.ChapterContentRepository

@Inject
class GetChapterContent(
    private val repository: ChapterContentRepository,
) {
    suspend fun await(chapterId: Long): ChapterContent? =
        repository.get(chapterId)
}
