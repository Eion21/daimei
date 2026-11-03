package eu.kanade.tachiyomi.data.download

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.novel.ChapterOpener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.novel.repository.ChapterContentRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR

/**
 * This class is used to manage chapter downloads in the application. It must be instantiated once
 * and retrieved through dependency injection. You can use this class to queue new chapters or query
 * downloaded chapters.
 */
@Inject
@SingleIn(AppScope::class)
class DownloadManager(
    private val context: Context,
    private val provider: DownloadProvider,
    private val cache: DownloadCache,
    private val getCategories: GetCategories,
    private val getManga: GetManga,
    private val getChapter: GetChapter,
    private val sourceManager: SourceManager,
    private val downloadPreferences: DownloadPreferences,
    private val downloader: Downloader,
    private val pendingDeleter: DownloadPendingDeleter,
    private val chapterContentRepository: ChapterContentRepository,
) {

    val queueState
        get() = downloader.queueState

    val isDownloaderRunning
        get() = DownloadWorker.isRunningFlow(context)

    /**
     * Starts the download worker, which runs the downloader.
     */
    fun startDownloads() {
        if (downloader.isRunning) return

        DownloadWorker.start(context)
    }

    /**
     * Tells the downloader to pause downloads.
     */
    suspend fun pauseDownloads() {
        downloader.pause()
    }

    /**
     * Empties the download queue.
     */
    suspend fun clearQueue() {
        downloader.clearQueue()
        downloader.stop()
    }

    /**
     * Returns the download from queue if the chapter is queued for download
     * else it will return null which means that the chapter is not queued for download
     *
     * @param chapterId the chapter to check.
     */
    fun getQueuedDownloadOrNull(chapterId: Long): Download? {
        return queueState.value.find { it.chapter.id == chapterId }
    }

    /**
     * Returns the queued downloads by chapter id, for looking up many chapters at once.
     */
    fun getQueuedDownloadsByChapterId(): Map<Long, Download> {
        return queueState.value.associateBy { it.chapter.id }
    }

    suspend fun startDownloadNow(chapterId: Long) {
        // If not in queue try to start a new download
        val toAdd = getQueuedDownloadOrNull(chapterId) ?: downloadFromChapterId(chapterId) ?: return
        downloader.updateQueue { queue ->
            val (existing, others) = queue.partition { it.chapter.id == chapterId }
            listOf(existing.firstOrNull() ?: toAdd) + others
        }
        startDownloads()
    }

    private suspend fun downloadFromChapterId(chapterId: Long): Download? {
        val chapter = getChapter.await(chapterId) ?: return null
        val manga = getManga.await(chapter.mangaId) ?: return null
        // Same rule as the bulk path: any installed source, because "download now" on a novel chapter is the same
        // action as on any other. Casting to `HttpSource` here returned null and made the tap do nothing at all.
        val source = sourceManager.get(manga.source) ?: return null

        return Download(source, manga, chapter)
    }

    /**
     * Reorders the download queue.
     *
     * @param downloads the queue in its new order. Downloads that left the queue since are dropped, and ones
     * that joined it are kept at the end.
     */
    suspend fun reorderQueue(downloads: List<Download>) {
        downloader.updateQueue { queue ->
            val queueById = queue.associateBy { it.chapter.id }
            val orderedIds = downloads.mapTo(HashSet()) { it.chapter.id }
            downloads.mapNotNull { queueById[it.chapter.id] } + queue.filter { it.chapter.id !in orderedIds }
        }
    }

    /**
     * Tells the downloader to enqueue the given list of chapters.
     *
     * @param manga the manga of the chapters.
     * @param chapters the list of chapters to enqueue.
     * @param autoStart whether to start the downloader after enqueing the chapters.
     */
    suspend fun downloadChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean = true) {
        if (downloader.queueChapters(manga, chapters, autoStart)) {
            startDownloads()
        }
    }

    /**
     * Tells the downloader to enqueue the given list of downloads at the start of the queue.
     *
     * @param downloads the list of downloads to enqueue.
     */
    suspend fun addDownloadsToStartOfQueue(downloads: List<Download>) {
        if (downloads.isEmpty()) return
        downloader.updateQueue { queue ->
            val chapterIds = downloads.mapTo(HashSet()) { it.chapter.id }
            downloads + queue.filter { it.chapter.id !in chapterIds }
        }
        startDownloads()
    }

    /**
     * Builds the page list of a downloaded chapter.
     *
     * @param source the source of the chapter.
     * @param manga the manga of the chapter.
     * @param chapter the downloaded chapter.
     * @return the list of pages from the chapter.
     */
    suspend fun buildPageList(source: Source, manga: Manga, chapter: Chapter): List<Page> {
        val chapterDir = provider.findChapterDir(chapter.name, chapter.scanlator, chapter.url, manga.title, source)
        val files = withContext(Dispatchers.IO) {
            chapterDir?.listFiles().orEmpty()
                .filter { it.isFile && ImageUtil.isImage(it.name) { it.openInputStream() } }
        }

        if (files.isEmpty()) {
            throw Exception(context.stringResource(MR.strings.page_list_empty_error))
        }

        return files.sortedBy { it.name }
            .mapIndexed { i, file ->
                Page(i, uri = file.uri).apply { status = Page.State.Ready }
            }
    }

    /**
     * Returns the ids of the downloaded chapters among the given chapters of the manga.
     *
     * @param chapters the chapters to query, all of [manga].
     * @param manga the manga of the chapters.
     */
    fun getDownloadedChapterIds(chapters: List<Chapter>, manga: Manga): Set<Long> {
        return cache.getDownloadedChapterIds(chapters, manga.title, manga.source)
    }

    /**
     * Returns true if the chapter is downloaded.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param mangaTitle the title of the manga to query.
     * @param sourceId the id of the source of the chapter.
     */
    fun isChapterDownloaded(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        sourceId: Long,
    ): Boolean {
        return cache.isChapterDownloaded(chapterName, chapterScanlator, chapterUrl, mangaTitle, sourceId)
    }

    /**
     * Returns true if the chapter is present on disk, bypassing the directory cache.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the chapter.
     */
    suspend fun isChapterDownloadedOnDisk(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        source: Source,
    ): Boolean {
        return provider.findChapterDir(chapterName, chapterScanlator, chapterUrl, mangaTitle, source) != null
    }

    /**
     * Returns the amount of downloaded chapters.
     */
    fun getDownloadCount(): Int {
        return cache.getTotalDownloadCount()
    }

    /**
     * Returns the amount of downloaded chapters for a manga.
     *
     * @param manga the manga to check.
     */
    fun getDownloadCount(manga: Manga): Int {
        return cache.getDownloadCount(manga)
    }

    /**
     * Returns the amount of downloaded chapters for each of the given manga, keyed by manga id.
     *
     * @param manga the manga to check.
     */
    fun getDownloadCounts(manga: List<Manga>): Map<Long, Int> {
        return cache.getDownloadCounts(manga)
    }

    suspend fun cancelQueuedDownloads(downloads: List<Download>) {
        downloader.dequeue(downloads.map { it.chapter })
    }

    /**
     * Deletes the directories of a list of downloaded chapters.
     *
     * @param chapters the list of chapters to delete.
     * @param manga the manga of the chapters.
     * @param source the source of the chapters.
     */
    suspend fun deleteChapters(chapters: List<Chapter>, manga: Manga, source: Source) {
        withContext(Dispatchers.IO) {
            val filteredChapters = getChaptersToDelete(chapters, manga)
            if (filteredChapters.isEmpty()) {
                return@withContext
            }

            downloader.dequeue(filteredChapters)

            logcat(LogPriority.INFO) {
                "[novel] Delete: removing the download of ${filteredChapters.size} chapter(s) of manga ${manga.id}"
            }
            val (mangaDir, chapterDirs) = provider.findChapterDirs(filteredChapters, manga, source)
            chapterDirs.forEach { it.delete() }
            cache.removeChapters(filteredChapters, manga)
            // The row is the other half of a novel's download: it is what opens the chapter with no network, so a
            // delete that took only the file would leave the chapter readable with the server off — "deleted" and
            // still open. Cleared here rather than by the screen, so every delete agrees with what it promised.
            dropNovelContent(filteredChapters.filter(ChapterOpener::shouldOpenNovelReader).map(Chapter::id))

            // Delete manga directory if empty
            if (mangaDir?.listFiles()?.isEmpty() == true) {
                deleteManga(manga, source, removeQueued = false)
            }
        }
    }

    /**
     * Drops the cached text of these chapters, so a deleted download stops being readable with no network.
     *
     * Ids rather than chapters because the two callers know them by different things: a selection knows which of its
     * chapters are novel ones, and a whole-manga delete is handed a set of ids by the repository itself and has no
     * chapter row to ask. A row rebuilt from an id alone carries no memo, so filtering it by "is this a novel
     * chapter" would quietly clear nothing.
     *
     * A failure to clear one is logged and the rest still go: the files are already gone by this point, so refusing
     * to finish would leave more behind than carrying on.
     */
    private suspend fun dropNovelContent(chapterIds: List<Long>) {
        chapterIds.forEach { chapterId ->
            try {
                chapterContentRepository.delete(chapterId)
                logcat(LogPriority.DEBUG) { "[novel] Dropped the cached content of chapter $chapterId" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) {
                    "[novel] Dropping the cached content of chapter $chapterId failed; it stays readable offline"
                }
            }
        }
    }

    /**
     * Deletes the directory of a downloaded manga.
     *
     * @param manga the manga to delete.
     * @param source the source of the manga.
     * @param removeQueued whether to also remove queued downloads.
     */
    suspend fun deleteManga(manga: Manga, source: Source, removeQueued: Boolean = true) {
        withContext(Dispatchers.IO) {
            if (removeQueued) {
                downloader.removeFromQueue(manga)
            }
            provider.findMangaDir(manga.title, source)?.delete()
            cache.removeManga(manga)
            // The whole folder is gone, so every chapter under it lost its download. The rows live outside that
            // folder, in the content cache, and nothing else ever clears them: leaving them behind is how a novel
            // the user deleted from disk keeps opening with the server off, forever.
            dropNovelContent(cachedNovelChapterIds(manga))

            // Delete source directory if empty
            val sourceDir = provider.findSourceDir(source)
            if (sourceDir?.listFiles()?.isEmpty() == true) {
                sourceDir.delete()
                cache.removeSource(source)
            }
        }
    }

    /**
     * The chapters of this manga whose text is on disk, asked of the repository the chapter list reads.
     *
     * A read that fails answers with nothing, which leaves a manga whose folder was just deleted with its rows in
     * place. That is the lesser of the two wrongs: the folder is gone either way, and a row with no file behind it
     * is a chapter that reads as empty rather than one that lies about being downloaded.
     */
    private suspend fun cachedNovelChapterIds(manga: Manga): List<Long> = try {
        chapterContentRepository.getCachedChapterIdsByMangaId(manga.id).first().toList()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) {
            "[novel] Reading the cached chapters of manga ${manga.id} failed; their content rows were left in place"
        }
        emptyList()
    }

    /**
     * Adds a list of chapters to be deleted later.
     *
     * @param chapters the list of chapters to delete.
     * @param manga the manga of the chapters.
     */
    suspend fun enqueueChaptersToDelete(chapters: List<Chapter>, manga: Manga) {
        pendingDeleter.addChapters(getChaptersToDelete(chapters, manga), manga)
    }

    /**
     * Triggers the execution of the deletion of pending chapters.
     */
    suspend fun deletePendingChapters() {
        val pendingChapters = pendingDeleter.getPendingChapters()
        for ((manga, chapters) in pendingChapters) {
            val source = sourceManager.get(manga.source) ?: continue
            deleteChapters(chapters, manga, source)
        }
    }

    /**
     * Renames source download folder
     *
     * @param oldSource the old source.
     * @param newSource the new source.
     */
    suspend fun renameSource(oldSource: Source, newSource: Source) = withContext(Dispatchers.IO) {
        val oldFolder = provider.findSourceDir(oldSource) ?: return@withContext
        val newName = provider.getSourceDirName(newSource)

        if (oldFolder.name == newName) return@withContext

        val capitalizationChanged = oldFolder.name.equals(newName, ignoreCase = true)
        if (capitalizationChanged) {
            val tempName = newName + Downloader.TMP_DIR_SUFFIX
            if (!oldFolder.renameTo(tempName)) {
                logcat(LogPriority.ERROR) { "Failed to rename source download folder: ${oldFolder.name}" }
                return@withContext
            }
        }

        if (!oldFolder.renameTo(newName)) {
            logcat(LogPriority.ERROR) { "Failed to rename source download folder: ${oldFolder.name}" }
        }
    }

    /**
     * Renames manga download folder
     *
     * @param manga the manga
     * @param newTitle the new manga title.
     */
    suspend fun renameManga(manga: Manga, newTitle: String) = withContext(Dispatchers.IO) {
        val source = sourceManager.getOrStub(manga.source)
        val oldFolder = provider.findMangaDir(manga.title, source) ?: return@withContext
        val newName = provider.getMangaDirName(newTitle)

        if (oldFolder.name == newName) return@withContext

        // just to be safe, don't allow downloads for this manga while renaming it
        downloader.removeFromQueue(manga)

        val capitalizationChanged = oldFolder.name.equals(newName, ignoreCase = true)
        if (capitalizationChanged) {
            val tempName = newName + Downloader.TMP_DIR_SUFFIX
            if (!oldFolder.renameTo(tempName)) {
                logcat(LogPriority.ERROR) { "Failed to rename manga download folder: ${oldFolder.name}" }
                return@withContext
            }
        }

        if (oldFolder.renameTo(newName)) {
            cache.renameManga(manga, oldFolder, newTitle)
        } else {
            logcat(LogPriority.ERROR) { "Failed to rename manga download folder: ${oldFolder.name}" }
        }
    }

    /**
     * Renames an already downloaded chapter
     *
     * @param source the source of the manga.
     * @param manga the manga of the chapter.
     * @param oldChapter the existing chapter with the old name.
     * @param newChapter the target chapter with the new name.
     */
    suspend fun renameChapter(source: Source, manga: Manga, oldChapter: Chapter, newChapter: Chapter) =
        withContext(Dispatchers.IO) {
            val oldNames = provider.getValidChapterDirNames(
                oldChapter.name,
                oldChapter.scanlator,
                oldChapter.url,
                ChapterOpener.shouldOpenNovelReader(oldChapter),
            )
            val mangaDir = provider.getMangaDir(manga.title, source).getOrElse { e ->
                logcat(LogPriority.ERROR, e) {
                    "Manga download folder doesn't exist. Skipping renaming after source sync"
                }
                return@withContext
            }

            // Assume there's only 1 version of the chapter name formats present
            val oldDownload = oldNames.asSequence()
                .mapNotNull { mangaDir.findFile(it) }
                .firstOrNull() ?: return@withContext

            val isNovelTextFile = oldDownload.isFile && oldDownload.extension == Downloader.NOVEL_CHAPTER_EXTENSION
            val cacheName = provider.getChapterDirName(newChapter.name, newChapter.scanlator, newChapter.url)
            var newName = cacheName
            // The extension is part of what was found, so the renamed entry has to keep it: a novel's `.txt`
            // renamed to the bare name would stop being a name `findChapterDir` asks for, and the chapter would
            // become undeletable and invisible the moment a sync changed its title
            if (oldDownload.isFile && oldDownload.extension == "cbz") {
                newName += ".cbz"
            } else if (isNovelTextFile) {
                newName += ".${Downloader.NOVEL_CHAPTER_EXTENSION}"
            }

            if (oldDownload.name == newName) return@withContext

            if (oldDownload.renameTo(newName)) {
                cache.removeChapter(oldChapter, manga)
                // Novel text files keep their suffix on disk, while the download cache indexes the bare name.
                cache.addChapter(if (isNovelTextFile) cacheName else newName, mangaDir, manga)
            } else {
                logcat(LogPriority.ERROR) { "Could not rename downloaded chapter: ${oldNames.joinToString()}" }
            }
        }

    private suspend fun getChaptersToDelete(chapters: List<Chapter>, manga: Manga): List<Chapter> {
        // Retrieve the categories that are set to exclude from being deleted on read
        val categoriesToExclude = downloadPreferences.removeExcludeCategories.get().map(String::toLong)

        val categoriesForManga = getCategories.await(manga.id)
            .map { it.id }
            .ifEmpty { listOf(0) }
        val filteredCategoryManga = if (categoriesForManga.intersect(categoriesToExclude).isNotEmpty()) {
            chapters.filterNot { it.read }
        } else {
            chapters
        }

        return if (!downloadPreferences.removeBookmarkedChapters.get()) {
            filteredCategoryManga.filterNot { it.bookmark }
        } else {
            filteredCategoryManga
        }
    }

    fun statusFlow(): Flow<Download> = queueState
        .flatMapLatest { downloads ->
            downloads
                .map { download ->
                    download.statusFlow.drop(1).map { download }
                }
                .merge()
        }
        .onStart {
            emitAll(
                queueState.value.filter { download -> download.status == Download.State.DOWNLOADING }.asFlow(),
            )
        }

    fun progressFlow(): Flow<Download> = queueState
        .flatMapLatest { downloads ->
            downloads
                .map { download ->
                    download.progressFlow.drop(1).map { download }
                }
                .merge()
        }
        .onStart {
            emitAll(
                queueState.value.filter { download -> download.status == Download.State.DOWNLOADING }
                    .asFlow(),
            )
        }
}
