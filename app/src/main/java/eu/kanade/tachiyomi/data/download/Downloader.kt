// Modified for Daimei; see MODIFICATIONS.md.

package eu.kanade.tachiyomi.data.download

import android.content.Context
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.getComicInfo
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.novel.ChapterOpener
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.DiskUtil.NOMEDIA_FILE
import eu.kanade.tachiyomi.util.storage.saveTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.archive.ZipWriter
import mihon.core.metro.AppCoroutineScope
import nl.adaptivity.xmlutil.serialization.XML
import okhttp3.Response
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.novel.interactor.GetChapterContent
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * This class is the one in charge of downloading chapters.
 *
 * Its queue contains the list of chapters to download.
 */
@Inject
@SingleIn(AppScope::class)
class Downloader(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val context: Context,
    private val provider: DownloadProvider,
    private val cache: DownloadCache,
    private val sourceManager: SourceManager,
    private val chapterCache: ChapterCache,
    private val downloadPreferences: DownloadPreferences,
    private val xml: XML,
    private val getCategories: GetCategories,
    private val getTracks: GetTracks,
    private val store: DownloadStore,
    private val notifier: DownloadNotifier,
    private val getChapterContent: GetChapterContent,
) {
    /**
     * Queue where active downloads are kept.
     */
    private val _queueState = MutableStateFlow<List<Download>>(emptyList())
    val queueState = _queueState.asStateFlow()

    // Held by every start, stop and queue edit, which come from the main thread, the download worker and the
    // deletion jobs, so a check of isRunning can't interleave with another caller's start or stop
    private val lock = Mutex()

    // Kept when canceled, so the next job can wait for it to finish
    @Volatile
    private var downloaderJob: Job? = null

    /**
     * Whether the downloader is running.
     */
    val isRunning: Boolean
        get() = downloaderJob?.isActive ?: false

    /**
     * Whether the downloader is paused
     */
    @Volatile
    var isPaused: Boolean = false

    init {
        scope.launch(Dispatchers.Main, CoroutineStart.UNDISPATCHED) {
            addAllToQueue(withContext(Dispatchers.IO) { store.restore() })
        }
    }

    /**
     * Starts the downloader. It doesn't do anything if it's already running or there isn't anything
     * to download.
     *
     * @return true if the downloader is started, false otherwise.
     */
    suspend fun start(): Boolean = lock.withLock { startLocked() }

    /**
     * Stops the downloader.
     */
    suspend fun stop(reason: String? = null) = lock.withLock { stopLocked(reason) }

    // Download jobs check these under the lock: by the time they get it, chapters may have been queued or the
    // downloader stopped and started again, and that newer run must not be stopped
    private suspend fun stopIfFinished(owner: Job) = lock.withLock {
        if (downloaderJob === owner && owner.isActive && areAllDownloadsFinished()) stopLocked()
    }

    private suspend fun stopIfCurrent(owner: Job) = lock.withLock {
        if (downloaderJob === owner && owner.isActive) stopLocked()
    }

    /**
     * Pauses and stops the downloader, keeping the queue for a later start
     */
    suspend fun pause() = lock.withLock {
        pauseLocked()
        stopLocked()
    }

    /**
     * Removes everything from the queue.
     */
    suspend fun clearQueue() = lock.withLock {
        cancelDownloaderJob()

        internalClearQueue()
        notifier.dismissProgress()
    }

    private fun startLocked(): Boolean {
        if (isRunning || queueState.value.isEmpty()) {
            return false
        }

        val pending = queueState.value.filter { it.status != Download.State.DOWNLOADED }
        pending.forEach { if (it.status != Download.State.QUEUE) it.status = Download.State.QUEUE }

        isPaused = false

        launchDownloaderJob()

        return pending.isNotEmpty()
    }

    private fun stopLocked(reason: String? = null) {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING }
            .forEach { it.status = Download.State.ERROR }

        if (reason != null) {
            notifier.onWarning(reason)
            return
        }

        if (isPaused && queueState.value.isNotEmpty()) {
            notifier.onPaused()
        } else {
            notifier.onComplete()
        }

        isPaused = false
    }

    private fun pauseLocked() {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING }
            .forEach { it.status = Download.State.QUEUE }
        isPaused = true
    }

    /**
     * Prepares the subscriptions to start downloading.
     */
    private fun launchDownloaderJob() {
        if (isRunning) return

        val previousJob = downloaderJob
        downloaderJob = scope.launch {
            // Page writes block, so a canceled job can still be writing the pages this one would start on. Not
            // cancelable, so a job canceled while waiting still finishes after the one before it.
            withContext(NonCancellable) { previousJob?.join() }
            val owner = coroutineContext.job

            val activeDownloadsFlow = combine(
                queueState,
                downloadPreferences.parallelSourceLimit.changes(),
            ) { a, b -> a to b }.transformLatest { (queue, parallelCount) ->
                while (true) {
                    val activeDownloads = queue.asSequence()
                        // Ignore completed downloads, leave them in the queue
                        .filter { it.status.value <= Download.State.DOWNLOADING.value }
                        .groupBy { it.source }
                        .toList()
                        .take(parallelCount)
                        .map { (_, downloads) -> downloads.first() }
                    emit(activeDownloads)

                    if (activeDownloads.isEmpty()) break
                    // Suspend until a download enters the ERROR state
                    val activeDownloadsErroredFlow =
                        combine(activeDownloads.map(Download::statusFlow)) { states ->
                            states.contains(Download.State.ERROR)
                        }.filter { it }
                    activeDownloadsErroredFlow.first()
                }
            }
                .distinctUntilChanged()

            // Use supervisorScope to cancel child jobs when the downloader job is cancelled
            supervisorScope {
                val sourceJobs = mutableMapOf<Source, Job>()
                val downloadJobs = ConcurrentHashMap<Download, Job>()

                // A source runs several of its chapters at once, so a removed one is stopped by itself
                launch {
                    queueState.collect { queue ->
                        downloadJobs.keys.filter { it !in queue }.forEach { downloadJobs.remove(it)?.cancel() }
                    }
                }

                activeDownloadsFlow.collectLatest { activeDownloads ->
                    val activeSources = activeDownloads.map { it.source }
                    val sourceJobsToStop = sourceJobs.filter { it.key !in activeSources }
                    sourceJobsToStop.forEach { (source, job) ->
                        job.cancel()
                        sourceJobs.remove(source)
                    }

                    val sourcesToStart = activeSources.filter { it !in sourceJobs }
                    sourcesToStart.forEach { source ->
                        sourceJobs[source] = launchSourceJob(source, downloadJobs, owner)
                    }
                }
            }
        }
    }

    /**
     * Downloads the queued chapters of [source] in queue order. They share the source's page slots, and the next
     * chapter starts once every page of the current one has a slot, so slots don't sit idle while a chapter's last
     * pages finish and it's archived.
     */
    private fun CoroutineScope.launchSourceJob(
        source: Source,
        downloadJobs: MutableMap<Download, Job>,
        owner: Job,
    ) = launch(Dispatchers.IO) {
        val slots = Semaphore(downloadPreferences.parallelPageLimit.get())
        while (true) {
            // A canceled job can mark a chapter as downloading after it's been reset, so that counts as waiting too
            val download = queueState
                .map { queue ->
                    queue.firstOrNull {
                        it.source == source &&
                            (it.status == Download.State.QUEUE || it.status == Download.State.DOWNLOADING) &&
                            it !in downloadJobs
                    }
                }
                .filterNotNull()
                .first()
            val pagesStarted = CompletableDeferred<Unit>()
            val job = launchDownloadJob(download, slots, pagesStarted, owner)
            downloadJobs[download] = job
            job.invokeOnCompletion {
                downloadJobs.remove(download, job)
                // A job canceled before it starts never reaches its finally block
                pagesStarted.complete(Unit)
            }
            pagesStarted.await()
        }
    }

    private fun CoroutineScope.launchDownloadJob(
        download: Download,
        slots: Semaphore,
        pagesStarted: CompletableDeferred<Unit>,
        owner: Job,
    ) = launch(Dispatchers.IO) {
        try {
            // Removed from the queue between being picked and starting
            if (download !in queueState.value) return@launch

            downloadChapter(download, slots, pagesStarted)

            // Remove successful download from queue
            if (download.status == Download.State.DOWNLOADED) {
                removeFromQueue(download)
            }
            stopIfFinished(owner)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            notifier.onError(e.message)
            stopIfCurrent(owner)
        } finally {
            // Also when the chapter fails before its pages, so the source moves on to the next one
            pagesStarted.complete(Unit)
        }
    }

    /**
     * Destroys the downloader subscriptions.
     */
    private fun cancelDownloaderJob() {
        downloaderJob?.cancel()
    }

    /**
     * Creates a download object for every chapter and adds them to the downloads queue.
     *
     * @param manga the manga of the chapters to download.
     * @param chapters the list of chapters to download.
     * @param autoStart whether to start the downloader after enqueing the chapters.
     * @return true if the downloader should be started.
     */
    suspend fun queueChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean): Boolean {
        if (chapters.isEmpty()) return false

        // Any source the manager knows, not only an `HttpSource`. A novel chapter is downloaded too and its
        // source is a plain `Source`; casting here used to drop every one of them on the floor with no error, no
        // notification and no log, which is the silent no-op this phase exists to remove. A manga whose source is
        // not installed is still refused, because there is genuinely nothing to download it with — and that is
        // logged now, because a silent skip is indistinguishable from a working one.
        val source = sourceManager.get(manga.source)
        if (source == null) {
            logcat(LogPriority.WARN) {
                "Source ${manga.source} of manga ${manga.id} is not installed; its chapters were not queued"
            }
            return false
        }
        val wasEmpty = queueState.value.isEmpty()
        val chaptersToQueue = withContext(Dispatchers.IO) {
            val downloadedChapterIds = provider.findDownloadedChapterIds(chapters, manga, source)
            val queuedChapterIds = queueState.value.mapTo(HashSet()) { it.chapter.id }
            chapters
                // Filter out those already downloaded.
                .filter { it.id !in downloadedChapterIds }
                .asSequence()
                // Add chapters to queue from the start.
                .sortedByDescending { it.sourceOrder }
                // Filter out those already enqueued.
                .filter { it.id !in queuedChapterIds }
                // Create a download for each one.
                .map { Download(source, manga, it) }
                .toList()
        }

        if (chaptersToQueue.isNotEmpty()) {
            addAllToQueue(chaptersToQueue)

            // Start downloader if needed
            if (autoStart && wasEmpty) {
                val queuedDownloads = queueState.value.count { it.source !is UnmeteredSource }
                val maxDownloadsFromSource = queueState.value
                    .groupBy { it.source }
                    .filterKeys { it !is UnmeteredSource }
                    .maxOfOrNull { it.value.size }
                    ?: 0
                if (
                    queuedDownloads > DOWNLOADS_QUEUED_WARNING_THRESHOLD ||
                    maxDownloadsFromSource > CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD
                ) {
                    notifier.onWarning(
                        context.stringResource(
                            MR.strings.download_queue_size_warning,
                            context.stringResource(MR.strings.app_name),
                        ),
                        WARNING_NOTIF_TIMEOUT_MS,
                        NotificationHandler.openUrl(context, LibraryUpdateNotifier.HELP_WARNING_URL),
                    )
                }
                return true
            }
        }
        return false
    }

    /**
     * Downloads a chapter.
     *
     * @param download the chapter to be downloaded.
     * @param slots the page download slots of the chapter's source.
     * @param pagesStarted completed once every page has a slot.
     */
    internal suspend fun downloadChapter(
        download: Download,
        slots: Semaphore,
        pagesStarted: CompletableDeferred<Unit>,
    ) {
        val mangaDir = provider.getMangaDir(download.manga.title, download.source).getOrElse { e ->
            download.status = Download.State.ERROR
            notifier.onError(e.message, download.chapter.name, download.manga.title, download.manga.id)
            return
        }

        val availSpace = DiskUtil.getAvailableStorageSpace(mangaDir)
        if (availSpace != -1L && availSpace < MIN_DISK_SPACE) {
            download.status = Download.State.ERROR
            notifier.onError(
                context.stringResource(MR.strings.download_insufficient_space),
                download.chapter.name,
                download.manga.title,
                download.manga.id,
            )
            return
        }

        val chapterDirname = provider.getChapterDirName(
            download.chapter.name,
            download.chapter.scanlator,
            download.chapter.url,
        )

        // A novel chapter is downloaded as one text file where a manga chapter is a folder of images or a CBZ, so
        // the checks below have to know which of the two they are looking for
        val isNovelChapter = ChapterOpener.shouldOpenNovelReader(download.chapter)

        // Being stopped after the download is moved into place but before it's indexed leaves a finished download
        // on disk that is still queued, and downloading it again would save a second copy next to it
        if (
            mangaDir.findFile("$chapterDirname.cbz") != null ||
            mangaDir.findFile(chapterDirname) != null ||
            // Asked of a novel only: a stray text file standing where a manga's chapter should be is not a
            // download, and letting it answer here would skip the download that never happened
            (isNovelChapter && mangaDir.findFile("$chapterDirname.$NOVEL_CHAPTER_EXTENSION") != null)
        ) {
            mangaDir.findFile(chapterDirname + TMP_DIR_SUFFIX)?.delete()
            cache.addChapter(chapterDirname, mangaDir, download.manga)
            download.status = Download.State.DOWNLOADED
            return
        }

        // Before the page list is asked for rather than after it throws: a novel chapter has no pages at all, and
        // `getPageList` refuses to pretend otherwise, so everything below this point is unreachable for one
        if (isNovelChapter) {
            downloadNovelChapter(download, mangaDir, chapterDirname)
            return
        }

        // The rest of this method is the image pipeline: a page list, image URLs, image bytes, a CBZ. All of that
        // lives on `HttpSource`, and only there. Resolving it here rather than letting it fail three frames down
        // means a source with no page pipeline is reported as a download that could not be made, instead of
        // crashing the worker or — worse, the shape this bug had — being skipped without a word.
        val httpSource = download.source as? HttpSource
        if (httpSource == null) {
            logcat(LogPriority.ERROR) {
                "[download] ${download.source} has no page pipeline, so chapter ${download.chapter.id} cannot " +
                    "be downloaded as pages"
            }
            download.status = Download.State.ERROR
            notifier.onError(null, download.chapter.name, download.manga.title, download.manga.id)
            return
        }

        val tmpDir = mangaDir.createDirectory(chapterDirname + TMP_DIR_SUFFIX)!!

        try {
            // If the page list already exists, start from the file
            val pageList = download.pages ?: run {
                // Otherwise, pull page list from network and add them to download object
                val pages = download.source.getPageList(download.chapter.toSChapter())

                if (pages.isEmpty()) {
                    throw Exception(context.stringResource(MR.strings.page_list_empty_error))
                }
                // Don't trust index from source
                val reIndexedPages = pages.mapIndexed { index, page -> Page(index, page.url, page.imageUrl, page.uri) }
                download.pages = reIndexedPages
                reIndexedPages
            }

            download.status = Download.State.DOWNLOADING

            // Start downloading images, consider we can have downloaded images already
            coroutineScope {
                pageList.forEach { page ->
                    slots.acquire()
                    // Released on completion, since a page canceled before it starts never runs its body
                    launch(Dispatchers.IO) {
                        // Fetch image URL if necessary
                        if (page.imageUrl.isNullOrEmpty()) {
                            page.status = Page.State.LoadPage
                            try {
                                page.imageUrl = httpSource.getImageUrl(page)
                            } catch (e: Throwable) {
                                page.status = Page.State.Error(e)
                            }
                        }

                        getOrDownloadImage(page, download, tmpDir)
                        notifier.onProgressChange(download)
                    }.invokeOnCompletion { slots.release() }
                }
                pagesStarted.complete(Unit)
            }

            // Do after download completes

            if (!isDownloadSuccessful(download, tmpDir)) {
                download.status = Download.State.ERROR
                return
            }

            createComicInfoFile(
                tmpDir,
                download.manga,
                download.chapter,
                httpSource,
            )

            // Only rename the directory if it's downloaded
            if (downloadPreferences.saveChaptersAsCBZ.get()) {
                archiveChapter(mangaDir, chapterDirname, tmpDir)
            } else {
                tmpDir.renameTo(chapterDirname)
            }
            cache.addChapter(chapterDirname, mangaDir, download.manga)

            DiskUtil.createNoMediaFile(tmpDir, context)

            download.status = Download.State.DOWNLOADED
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // If the page list threw, it will resume here
            logcat(LogPriority.ERROR, error)
            download.status = Download.State.ERROR
            notifier.onError(error.message, download.chapter.name, download.manga.title, download.manga.id)
        }
    }

    /**
     * Downloads a novel chapter, which is prose and not pages.
     *
     * None of the image pipeline below can be reused: `getPageList` throws for a novel source by design, and the
     * page slots, the CBZ and the image files all assume pages exist to be filled. So the whole chapter is one
     * file, written under the temp name and renamed — which is what keeps a download killed mid-write from
     * leaving behind a `.txt` that the index would read as a finished chapter.
     *
     * [getChapterContent] reads its cache before the source, so downloading a chapter that was already read costs
     * nothing and never reaches the network, and it stores whatever it resolves: the file written here is the
     * copy the user finds in the download folder, while the copy that makes the chapter readable with no network
     * is the one the reader produced.
     *
     * Content that resolves to nothing is an error and not an empty file. The index reads a `.txt` as a finished
     * chapter, so an empty one would be reported as downloaded from then on and the failure behind it would
     * never surface.
     */
    private suspend fun downloadNovelChapter(download: Download, mangaDir: UniFile, chapterDirname: String) {
        download.status = Download.State.DOWNLOADING

        val novelFileName = "$chapterDirname.$NOVEL_CHAPTER_EXTENSION"
        // One line per step of the path, each naming the step, because "the download did nothing" on a device is
        // otherwise a guess between not entering this method, failing to resolve the text, failing to write the
        // file and failing to index it.
        logcat(LogPriority.INFO) {
            "[novel] Download: chapter ${download.chapter.id} resolving its text for $novelFileName"
        }
        try {
            val text = getChapterContent.await(download.chapter.id)?.toPlainText()
            if (text.isNullOrBlank()) {
                logcat(LogPriority.ERROR) {
                    "[novel] Download: chapter ${download.chapter.id} has no content, so $novelFileName was not written"
                }
                download.status = Download.State.ERROR
                // With no message of its own to give: the notifier's own wording for a chapter it could not
                // download is the honest one, and a message about pages would be about a pipeline never reached
                notifier.onError(null, download.chapter.name, download.manga.title, download.manga.id)
                return
            }

            // A temp file left by an interrupted attempt would make createFile fail, and this is where it heals
            mangaDir.findFile(novelFileName + TMP_DIR_SUFFIX)?.delete()
            val tmpFile = mangaDir.createFile(novelFileName + TMP_DIR_SUFFIX)!!
            tmpFile.openOutputStream().use { it.write(text.toByteArray(Charsets.UTF_8)) }
            tmpFile.renameTo(novelFileName)
            logcat(LogPriority.INFO) {
                "[novel] Download: wrote $novelFileName (${text.length} chars) for chapter ${download.chapter.id}"
            }

            cache.addChapter(chapterDirname, mangaDir, download.manga)
            logcat(LogPriority.INFO) {
                "[novel] Download: chapter ${download.chapter.id} indexed as $chapterDirname"
            }

            download.status = Download.State.DOWNLOADED
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logcat(LogPriority.ERROR, error) {
                "[novel] Download: chapter ${download.chapter.id} failed while writing $novelFileName"
            }
            download.status = Download.State.ERROR
            notifier.onError(error.message, download.chapter.name, download.manga.title, download.manga.id)
        }
    }

    /**
     * Gets the image from the filesystem if it exists or downloads it otherwise.
     *
     * @param page the page to download.
     * @param download the download of the page.
     * @param tmpDir the temporary directory of the download.
     */
    private suspend fun getOrDownloadImage(page: Page, download: Download, tmpDir: UniFile) {
        // If the image URL is empty, do nothing
        if (page.imageUrl == null) {
            return
        }

        // Reached only from the image pipeline, which has already resolved and rejected on a missing `HttpSource`.
        // The cast is still written out rather than passed in, so this function cannot be called for a source that
        // has no image API without saying so at the point it goes wrong.
        val httpSource = download.source as? HttpSource ?: run {
            logcat(LogPriority.ERROR) { "[download] ${download.source} cannot fetch image ${page.number}" }
            page.status = Page.State.Error(IllegalStateException("source has no image API"))
            return
        }

        val digitCount = (download.pages?.size ?: 0).toString().length.coerceAtLeast(3)
        val filename = "%0${digitCount}d".format(Locale.ENGLISH, page.number)

        // Try to find the image file
        val imageFile = tmpDir.listFiles()?.firstOrNull {
            isDownloadedPageImage(it.name ?: return@firstOrNull false, filename)
        }

        try {
            // If the image is already downloaded, do nothing. Otherwise download from network
            val file = when {
                imageFile != null -> imageFile
                chapterCache.isImageInCache(
                    page.imageUrl!!,
                ) -> copyImageFromCache(chapterCache.getImageFile(page.imageUrl!!), tmpDir, filename)
                else -> downloadImage(page, httpSource, tmpDir, filename)
            }

            // When the page is ready, set page path, progress (just in case) and status
            splitTallImageIfNeeded(page, tmpDir)

            page.uri = file.uri
            page.progress = 100
            page.status = Page.State.Ready
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // Mark this page as error and allow to download the remaining
            page.progress = 0
            page.status = Page.State.Error(e)
            notifier.onError(e.message, download.chapter.name, download.manga.title, download.manga.id)
        }
    }

    /**
     * Downloads the image from network to a file in tmpDir.
     *
     * @param page the page to download.
     * @param source the source of the page.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private suspend fun downloadImage(page: Page, source: HttpSource, tmpDir: UniFile, filename: String): UniFile {
        page.status = Page.State.DownloadImage
        page.progress = 0
        return flow {
            val file = tmpDir.findFile("$filename.tmp")
                ?: tmpDir.createFile("$filename.tmp")!!

            try {
                source.getImage(page, file.length()).use {
                    it.body.source().saveTo(
                        // If the server supports partial downloads (HTTP 206),
                        // append to the existing file.
                        // Otherwise, start from scratch and overwrite the file.
                        stream = file.openOutputStream(it.code == 206),
                    )
                    val extension = getImageExtension(it, file)
                    file.renameTo("$filename.$extension")
                }
            } catch (e: HttpException) {
                if (e.code == 416) {
                    file.delete()
                }
                throw e
            }
            emit(file)
        }
            // Retry 3 times, waiting 2, 4 and 8 seconds between attempts.
            .retryWhen { _, attempt ->
                if (attempt < 3) {
                    delay((2L shl attempt.toInt()).seconds)
                    true
                } else {
                    false
                }
            }
            .first()
    }

    /**
     * Copies the image from cache to file in tmpDir.
     *
     * @param cacheFile the file from cache.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private fun copyImageFromCache(cacheFile: File, tmpDir: UniFile, filename: String): UniFile {
        // Delete temp file if it exists
        tmpDir.findFile("$filename.tmp")?.delete()
        val tmpFile = tmpDir.createFile("$filename.tmp")!!
        cacheFile.inputStream().use { input ->
            tmpFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
        val extension = ImageUtil.findImageType(cacheFile.inputStream()) ?: return tmpFile
        tmpFile.renameTo("$filename.${extension.extension}")
        cacheFile.delete()
        return tmpFile
    }

    /**
     * Returns the extension of the downloaded image from the network response, or if it's null,
     * analyze the file. If everything fails, assume it's a jpg.
     *
     * @param response the network response of the image.
     * @param file the file where the image is already downloaded.
     */
    private fun getImageExtension(response: Response, file: UniFile): String {
        val mime = response.body.contentType()?.run { if (type == "image") "image/$subtype" else null }
        return ImageUtil.getExtensionFromMimeType(mime) { file.openInputStream() }
    }

    private fun splitTallImageIfNeeded(page: Page, tmpDir: UniFile) {
        if (!downloadPreferences.splitTallImages.get()) return

        try {
            val filenamePrefix = "%03d".format(Locale.ENGLISH, page.number)
            val imageFile = tmpDir.listFiles()?.firstOrNull { it.name.orEmpty().startsWith(filenamePrefix) }
                ?: error(context.stringResource(MR.strings.download_notifier_split_page_not_found, page.number))

            // If the original page was previously split, then skip
            if (imageFile.name.orEmpty().startsWith("${filenamePrefix}__")) return

            ImageUtil.splitTallImage(tmpDir, imageFile, filenamePrefix)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to split downloaded image" }
        }
    }

    /**
     * Checks if the download was successful.
     *
     * @param download the download to check.
     * @param tmpDir the directory where the download is currently stored.
     */
    private fun isDownloadSuccessful(
        download: Download,
        tmpDir: UniFile,
    ): Boolean {
        // Page list hasn't been initialized
        val downloadPageCount = download.pages?.size ?: return false

        // Ensure that all pages have been downloaded
        if (download.downloadedImages != downloadPageCount) {
            return false
        }

        // Ensure that the chapter folder has all the pages
        val downloadedImagesCount = tmpDir.listFiles().orEmpty().count {
            val fileName = it.name.orEmpty()
            when {
                fileName in listOf(COMIC_INFO_FILE, NOMEDIA_FILE) -> false
                fileName.endsWith(".tmp") -> false
                // Only count the first split page and not the others
                fileName.contains("__") && !fileName.endsWith("__001.jpg") -> false
                else -> true
            }
        }
        return downloadedImagesCount == downloadPageCount
    }

    /**
     * Checks if the file name matches a downloaded page image.
     *
     * @param fileName Name of the file to check
     * @param pagePrefix Expected page prefix (e.g., "001")
     */
    private fun isDownloadedPageImage(fileName: String, pagePrefix: String): Boolean =
        !fileName.endsWith(".tmp") && (
            fileName.startsWith("$pagePrefix.") ||
                fileName.startsWith("${pagePrefix}__001.")
            )

    /**
     * Archive the chapter pages as a CBZ.
     */
    private fun archiveChapter(
        mangaDir: UniFile,
        dirname: String,
        tmpDir: UniFile,
    ) {
        val zip = mangaDir.createFile("$dirname.cbz$TMP_DIR_SUFFIX")!!
        ZipWriter(context, zip).use { writer ->
            tmpDir.listFiles()?.forEach { file ->
                writer.write(file)
            }
        }
        zip.renameTo("$dirname.cbz")
        tmpDir.delete()
    }

    /**
     * Creates a ComicInfo.xml file inside the given directory.
     */
    private suspend fun createComicInfoFile(
        dir: UniFile,
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
    ) {
        val categories = getCategories.await(manga.id).map { it.name.trim() }.takeUnless { it.isEmpty() }
        val urls = getTracks.await(manga.id)
            .mapNotNull { track ->
                track.remoteUrl.takeUnless { url -> url.isBlank() }?.trim()
            }
            .plus(source.getChapterUrl(chapter.toSChapter()).trim())
            .distinct()

        val comicInfo = getComicInfo(
            manga,
            chapter,
            urls,
            categories,
            source.name,
        )

        // Remove the old file
        dir.findFile(COMIC_INFO_FILE)?.delete()
        dir.createFile(COMIC_INFO_FILE)!!.openOutputStream().use {
            val comicInfoString = xml.encodeToString(ComicInfo.serializer(), comicInfo)
            it.write(comicInfoString.toByteArray())
        }
    }

    /**
     * Returns true if all the queued downloads are in DOWNLOADED or ERROR state.
     */
    private fun areAllDownloadsFinished(): Boolean {
        return queueState.value.none { it.status.value <= Download.State.DOWNLOADING.value }
    }

    private fun addAllToQueue(downloads: List<Download>) {
        _queueState.update { queue ->
            // Callers filter off the queue's thread, so a chapter may have been queued in the meantime
            val queuedChapterIds = queue.mapTo(HashSet()) { it.chapter.id }
            val toAdd = downloads.filter { it.chapter.id !in queuedChapterIds }
            toAdd.forEach { download ->
                download.status = Download.State.QUEUE
            }
            store.addAll(toAdd)
            queue + toAdd
        }
    }

    private fun removeFromQueue(download: Download) {
        _queueState.update {
            store.remove(download)
            if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                download.status = Download.State.NOT_DOWNLOADED
            }
            it - download
        }
    }

    private inline fun removeFromQueueIf(predicate: (Download) -> Boolean) {
        _queueState.update { queue ->
            val downloads = queue.filter { predicate(it) }
            store.removeAll(downloads)
            downloads.forEach { download ->
                if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            queue - downloads
        }
    }

    private fun removeFromQueue(chapters: List<Chapter>) {
        val chapterIds = chapters.map { it.id }
        removeFromQueueIf { it.chapter.id in chapterIds }
    }

    suspend fun removeFromQueue(manga: Manga) = lock.withLock {
        removeFromQueueIf { it.manga.id == manga.id }
    }

    private fun internalClearQueue() {
        _queueState.update {
            it.forEach { download ->
                if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            store.clear()
            emptyList()
        }
    }

    /**
     * Replaces the queue with [transform] applied to the current queue. It runs under the lock, so downloads
     * queued or removed meanwhile aren't lost to a stale copy of the queue.
     */
    suspend fun updateQueue(transform: (List<Download>) -> List<Download>) = editQueue {
        // One update, since chapters are queued without the lock
        _queueState.update { queue ->
            val downloads = transform(queue).distinctBy { it.chapter.id }
            val kept = downloads.mapTo(HashSet()) { it.chapter.id }
            queue.forEach { download ->
                if (download.chapter.id !in kept &&
                    (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE)
                ) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            downloads.forEach { it.status = Download.State.QUEUE }
            store.clear()
            store.addAll(downloads)
            downloads
        }
    }

    /**
     * Removes [chapters] from the queue, keeping the state of the downloads that stay.
     */
    suspend fun dequeue(chapters: List<Chapter>) = editQueue {
        removeFromQueue(chapters)
    }

    private suspend inline fun editQueue(edit: () -> Unit) = lock.withLock {
        val wasRunning = isRunning
        if (wasRunning) pauseLocked()

        edit()

        if (wasRunning) {
            if (queueState.value.isEmpty()) stopLocked() else startLocked()
        }
    }

    companion object {
        const val TMP_DIR_SUFFIX = "_tmp"

        /**
         * The extension a novel chapter is downloaded with, without the dot.
         *
         * A novel chapter is one file of prose where a manga chapter is a folder of images or a CBZ, and the index
         * and the delete both have to tell them apart by this name.
         */
        const val NOVEL_CHAPTER_EXTENSION = "txt"

        const val WARNING_NOTIF_TIMEOUT_MS = 30_000L
        const val CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 15
        private const val DOWNLOADS_QUEUED_WARNING_THRESHOLD = 30
    }
}

// Arbitrary minimum required space to start a download: 200 MB
private const val MIN_DISK_SPACE = 200L * 1024 * 1024

// Deepest heading a text file spells out; past this the hashes stop carrying meaning and only add noise
private const val MAX_PLAIN_TEXT_HEADING_LEVEL = 6

/**
 * The chapter as the plain text that gets written to the download folder.
 *
 * `ChapterContent` is a block list shaped for the reader, so flattening it is a rendering decision and nothing
 * here can be dropped quietly: an image keeps its address in markdown syntax instead of disappearing, because a
 * `.txt` cannot carry the image and a missing block is indistinguishable from a short chapter.
 *
 * Internal rather than private because [toChapterContent] is the other half of it and both halves are needed
 * outside this file: this one writes the file, that one reads it back. They are declared side by side, on purpose.
 * A parser that lived anywhere else could drift from this writer, and the drift would not show up until a
 * chapter had been downloaded by one version and read by another.
 */
internal fun ChapterContent.toPlainText(): String = blocks.joinToString("\n\n") { block ->
    when (block) {
        is ContentBlock.Paragraph -> block.text
        // The hashes are what most text readers read back as a heading, and cost nothing to a reader that does not
        is ContentBlock.Heading -> "${"#".repeat(block.level.coerceIn(1, MAX_PLAIN_TEXT_HEADING_LEVEL))} ${block.text}"
        is ContentBlock.Image -> "![${block.alt.orEmpty()}](${block.url})"
        ContentBlock.Divider -> "---"
    }
}

/**
 * The chapter back out of the plain text [toPlainText] writes, or null when there is nothing in it.
 *
 * The exact inverse of the writer above and nothing more. Every token recognised here is a token that writer emits:
 * blocks are separated by a blank line, a heading is hashes plus a space, an image is `![alt](url)` and a divider is
 * `---`. Nothing is invented — no escaping, no front matter, no markers for a source that writes none of those —
 * because the format is not this file's to redesign. A second dialect would leave every chapter downloaded by an
 * earlier build unreadable, and would make the format's owner a matter of opinion rather than a single function.
 *
 * Null rather than an empty `ChapterContent`, because an empty chapter is not a smaller chapter: it is a chapter
 * nobody could read, and the reader treats the two as different failures. A file that holds nothing but whitespace
 * is the same thing.
 *
 * Two shapes cannot survive the trip, and neither is escaped for, because escaping them would be new syntax in a
 * format that has files written to it already:
 * - a paragraph whose text is exactly `---`, or which opens with hashes and a space, reads back as the block that
 *   marker names, since a plain text file cannot say "this line is prose";
 * - a heading deeper than [MAX_PLAIN_TEXT_HEADING_LEVEL] is clamped on the way out, because past six the hashes stop
 *   carrying meaning and the writer deliberately stops spelling them out.
 *
 * Both are documented rather than worked around. Everything the writer actually produces for a chapter the app
 * downloaded is read back identically, which is what the round trip test pins.
 */
internal fun String.toChapterContent(): ChapterContent? {
    if (isBlank()) return null

    val blocks = PLAIN_TEXT_BLOCK_SEPARATOR.split(trim()).mapNotNull { block ->
        when {
            block.isBlank() -> null
            block == PLAIN_TEXT_DIVIDER -> ContentBlock.Divider
            else -> block.toContentBlock()
        }
    }

    return if (blocks.isEmpty()) null else ChapterContent(blocks)
}

/**
 * One blank-line separated chunk of the file as the single block it was written from.
 *
 * The heading test counts the leading hashes instead of matching a pattern, so a heading whose text carries its own
 * newlines still comes back whole — and a line that merely starts with a `#`, which is prose about issue numbers and
 * very common in a novel, is left as the paragraph it always was unless a space follows the hashes.
 */
private fun String.toContentBlock(): ContentBlock {
    val level = takeWhile { it == '#' }.length
    if (level in 1..MAX_PLAIN_TEXT_HEADING_LEVEL && getOrNull(level) == ' ') {
        return ContentBlock.Heading(text = substring(level + 1), level = level)
    }

    // Greedy on both halves, so an alt or a url that contains `](` is read back the way it was written: the split
    // happens at the last `](` in the line, which is the one the writer put there.
    val image = PLAIN_TEXT_IMAGE.matchEntire(this)
    if (image != null) {
        // An empty alt reads back as none rather than as an empty string: the writer spells both the same way, and
        // "no alt text" is the state the reader has an actual rendering for.
        return ContentBlock.Image(url = image.groupValues[2], alt = image.groupValues[1].ifEmpty { null })
    }

    return ContentBlock.Paragraph(this)
}

/** What [toPlainText] puts between two blocks, and what [toChapterContent] splits on. */
private val PLAIN_TEXT_BLOCK_SEPARATOR = Regex("""\n[ \t]*\n""")

/** What [toPlainText] writes for [ContentBlock.Divider], and what it reads back as one. */
private const val PLAIN_TEXT_DIVIDER = "---"

/** What [toPlainText] writes for a [ContentBlock.Image], and what it reads back as one. */
private val PLAIN_TEXT_IMAGE = Regex("""!\[(.*)]\((.*)\)""", RegexOption.DOT_MATCHES_ALL)
