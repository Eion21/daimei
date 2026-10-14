package tachiyomi.domain.novel.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.novel.repository.ReadingProgress
import tachiyomi.domain.novel.repository.ReadingProgressRepository

@Inject
class SaveReadingProgress(
    private val repository: ReadingProgressRepository,
) {
    suspend fun await(chapterId: Long, progress: ReadingProgress) =
        repository.save(chapterId, progress)
}
