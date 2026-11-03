package tachiyomi.data.novel

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.novel.source.api.ChapterContent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.novel.repository.ChapterContentRepository
import java.io.File
import kotlin.time.Clock

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ChapterContentRepositoryImpl(
    private val database: Database,
    private val contentDir: NovelContentDir,
) : ChapterContentRepository {
    private val json = Json

    private fun contentFile(chapterId: Long) = File(contentDir.path, "chapter_$chapterId.json")

    // Keep JSON and file work off Main while preserving the caller's suspend and flow contexts.
    override suspend fun get(chapterId: Long): ChapterContent? = withContext(Dispatchers.IO) {
        val filePath = database.chapter_contentQueries
            .getByChapterId(chapterId) { _: Long, path: String, _: kotlin.time.Instant -> path }
            .awaitAsOneOrNull()
        // The three ways this can come back empty are different problems, so each is logged on its own
        // rather than collapsing into a silent null.
        if (filePath == null) {
            logcat(LogPriority.WARN) { "No chapter_content row for chapter $chapterId" }
            return@withContext null
        }
        val file = File(filePath)
        if (!file.exists()) {
            logcat(LogPriority.WARN) { "Chapter content file missing for chapter $chapterId at $filePath" }
            return@withContext null
        }
        try {
            json.decodeFromString(ChapterContent.serializer(), file.readText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Could not decode chapter content for chapter $chapterId at $filePath" }
            null
        }
    }

    override fun getAsFlow(chapterId: Long): Flow<ChapterContent?> = flow {
        emit(get(chapterId))
    }

    override suspend fun save(chapterId: Long, content: ChapterContent): String = withContext(Dispatchers.IO) {
        val file = contentFile(chapterId)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(ChapterContent.serializer(), content))
        database.chapter_contentQueries.upsert(
            chapterId = chapterId,
            filePath = file.absolutePath,
            stateDateDownload = Clock.System.now(),
        )
        file.absolutePath
    }

    override suspend fun delete(chapterId: Long) {
        withContext(Dispatchers.IO) {
            val filePath = database.chapter_contentQueries
                .getByChapterId(chapterId) { _: Long, path: String, _: kotlin.time.Instant -> path }
                .awaitAsOneOrNull()
            filePath?.let { File(it).delete() }
            database.chapter_contentQueries.delete(chapterId)
        }
    }

    /**
     * The chapters of this manga that have content on disk, in one read.
     *
     * The join to `chapter` happens in SQL, so a manga of image chapters reads nothing and only the chapter
     * list's own query is paid. The query listens on `chapter` and `chapter_content` alike, which is what lets
     * a chapter saved from the reader become a downloaded row without the screen being rebuilt by hand.
     */
    override fun getCachedChapterIdsByMangaId(mangaId: Long): Flow<Set<Long>> {
        return database.chapter_contentQueries
            .getCachedChapterIdsByMangaId(mangaId)
            .subscribeToList()
            .map { it.toSet() }
    }
}
