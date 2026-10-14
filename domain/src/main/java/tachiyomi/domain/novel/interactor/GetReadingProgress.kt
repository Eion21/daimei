package tachiyomi.domain.novel.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.novel.repository.ReadingProgress
import tachiyomi.domain.novel.repository.ReadingProgressRepository

@Inject
class GetReadingProgress(
    private val repository: ReadingProgressRepository,
) {
    suspend fun await(chapterId: Long): ReadingProgress? =
        repository.get(chapterId)
}
