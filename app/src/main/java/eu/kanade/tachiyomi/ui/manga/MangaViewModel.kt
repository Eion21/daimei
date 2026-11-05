// Modified for Daimei; see MODIFICATIONS.md.

package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.core.preference.asState
import eu.kanade.core.util.addOrRemove
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.chaptersFiltered
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.novel.isNovelSource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.chapter.novelNextUnread
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.SubscriberGate
import tachiyomi.core.common.util.lang.pausedWhileUnsubscribed
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.service.calculateChapterGap
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.chapter.service.missingChaptersCount
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.novel.repository.ReadingProgressRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import tachiyomi.source.local.isLocal
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

@AssistedInject
class MangaViewModel(
    @Assisted private val mangaId: Long,
    @Assisted private val isFromSource: Boolean,
    @AppCoroutineScope private val appScope: CoroutineScope,
    private val context: Context,
    private val libraryPreferences: LibraryPreferences,
    trackPreferences: TrackPreferences,
    readerPreferences: ReaderPreferences,
    private val trackerManager: TrackerManager,
    private val trackChapter: TrackChapter,
    private val downloadManager: DownloadManager,
    private val downloadCache: DownloadCache,
    private val getMangaAndChapters: GetMangaWithChapters,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
    private val getAvailableScanlators: GetAvailableScanlators,
    private val getExcludedScanlators: GetExcludedScanlators,
    private val setExcludedScanlators: SetExcludedScanlators,
    private val setMangaChapterFlags: SetMangaChapterFlags,
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags,
    private val setReadStatus: SetReadStatus,
    private val updateChapter: UpdateChapter,
    private val updateManga: UpdateManga,
    private val getCategories: GetCategories,
    private val getTracks: GetTracks,
    private val readingProgressRepository: ReadingProgressRepository,
    private val addTracks: AddTracks,
    private val setMangaCategories: SetMangaCategories,
    private val filterChaptersForDownload: FilterChaptersForDownload,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val sourceManager: SourceManager,
    private val refreshTracks: RefreshTracks,
    private val coverCache: CoverCache,
) : ViewModel() {

    private val subscriberGate = SubscriberGate()

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaId: Long, isFromSource: Boolean): MangaViewModel
    }

    val snackbarHostState: SnackbarHostState = SnackbarHostState()

    private val successState: State.Success?
        get() = state.value as? State.Success

    val manga: Manga?
        get() = successState?.manga

    val source: Source?
        get() = successState?.source

    private val isFavorited: Boolean
        get() = manga?.favorite ?: false

    private val allChapters: List<ChapterList.Item>?
        get() = successState?.chapters

    private val filteredChapters: List<ChapterList.Item>?
        get() = successState?.processedChapters

    val chapterSwipeStartAction = libraryPreferences.swipeToEndAction.get()
    val chapterSwipeEndAction = libraryPreferences.swipeToStartAction.get()
    var autoTrackState = trackPreferences.autoUpdateTrackOnMarkRead.get()

    private val skipFiltered by readerPreferences.skipFiltered.asState(viewModelScope)

    val isUpdateIntervalEnabled =
        LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in libraryPreferences.autoUpdateMangaRestrictions.get()

    private val selectedPositions: Array<Int> = arrayOf(-1, -1) // first and last selected index in list
    private val selectedChapterIds = MutableStateFlow(emptySet<Long>())

    private val dialog = MutableStateFlow<Dialog?>(null)

    private val isRefreshingData = MutableStateFlow(false)

    private val downloadStates = MutableStateFlow(emptyMap<Long, Download.State>())

    // Progress ticks often, so only the download indicators that read it recompose instead of every tick rebuilding
    // the chapter list
    private val downloadProgress = mutableStateMapOf<Long, Int>()

    // A finished chapter leaves the queue, and its last status change can be lost with it, so its state is left to
    // the queried item once it's no longer queued
    private val queuedDownloadStates = combine(downloadStates, downloadManager.queueState, ::queuedDownloadStatesFor)

    private val hideMissingChapters = libraryPreferences.hideMissingChapters.get()

    private var hasPromptedToAddBefore = false

    private val defaultChapterFlagsJob = viewModelScope.launch {
        val manga = getMangaAndChapters.awaitManga(mangaId)
        if (!manga.favorite) {
            setMangaDefaultChapterFlags.await(manga)
        }
    }

    /**
     * How far through each novel chapter the reader got, as the whole percentage the chapter list shows.
     *
     * Empty for a manga of image chapters, since only the novel reader writes a position. It is read for every manga
     * anyway: it is one indexed query, and a novel's source resolves to a stub before it is installed, so asking the
     * source what it is would be a second way to get this wrong.
     *
     * It used to be joined here by the set of chapters with cached content, which is how the chapter list came to
     * call a chapter downloaded once it had merely been opened. Nothing needs those ids here any more, so the query
     * went away with the rule that asked for it.
     */
    private val novelReadProgress = readingProgressRepository.getPercentByMangaId(mangaId).distinctUntilChanged()

    private val mangaAndChapters = combine(
        flow {
            // So an entry outside the library isn't shown with its old chapter settings first
            defaultChapterFlagsJob.join()
            emitAll(getMangaAndChapters.subscribe(mangaId, applyScanlatorFilter = true))
        }
            .distinctUntilChanged(),
        downloadCache.changes.pausedWhileUnsubscribed(),
        downloadManager.queueState.pausedWhileUnsubscribed(),
        novelReadProgress,
    ) { (manga, chapters), _, _, readProgress ->
        MangaAndChapters(
            manga = manga,
            source = sourceManager.getOrStub(manga.source),
            chapters = chapters.toChapterListItems(manga, readProgress),
        )
    }

    private val scanlators = combine(
        getAvailableScanlators.subscribe(mangaId).distinctUntilChanged(),
        getExcludedScanlators.subscribe(mangaId).distinctUntilChanged(),
        ::Pair,
    )

    private val trackers = flow { emit(sourceManager.getOrStub(getMangaAndChapters.awaitManga(mangaId).source)) }
        .flatMapLatest { source ->
            combine(
                getTracks.subscribe(mangaId).catch { logcat(LogPriority.ERROR, it) },
                trackerManager.loggedInTrackersFlow(),
            ) { mangaTracks, loggedInTrackers ->
                // Show only if the service supports this manga's source
                val supportedTrackers = loggedInTrackers.filter { (it as? EnhancedTracker)?.accept(source) ?: true }
                val supportedTrackerIds = supportedTrackers.map { it.id }.toHashSet()
                val supportedTrackerTracks = mangaTracks.filter { it.trackerId in supportedTrackerIds }
                supportedTrackerTracks.size to supportedTrackers.isNotEmpty()
            }
        }
        .distinctUntilChanged()
        .onStart { emit(0 to false) }

    val state: StateFlow<State> = combine(
        mangaAndChapters,
        scanlators,
        trackers,
        combine(selectedChapterIds, queuedDownloadStates.pausedWhileUnsubscribed(), ::Pair),
        combine(dialog, isRefreshingData, ::Pair),
    ) {
            mangaAndChapters,
            (availableScanlators, excludedScanlators),
            (trackingCount, hasLoggedInTrackers),
            (selectedIds, downloads),
            (dialog, isRefreshingData),
        ->
        State.Success(
            manga = mangaAndChapters.manga,
            source = mangaAndChapters.source,
            isFromSource = isFromSource,
            chapters = mangaAndChapters.chapters.map { item ->
                withQueuedDownloadState(
                    item,
                    queuedDownloadState = downloads[item.id],
                    selected = item.id in selectedIds,
                )
            },
            availableScanlators = availableScanlators,
            excludedScanlators = excludedScanlators,
            trackingCount = trackingCount,
            hasLoggedInTrackers = hasLoggedInTrackers,
            isRefreshingData = isRefreshingData,
            dialog = dialog,
            hideMissingChapters = hideMissingChapters,
        )
    }
        // Composition would otherwise build these on first read, on the main thread, for every new state
        .onEach {
            it.processedChapters
            it.chapterListItems
            it.isAnySelected
            it.selectedChapters
            it.hasUnreadChapters
            it.hasReadChapters
            it.missingChapterCount
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope + subscriberGate, subscriberGate.whileSubscribed(5.seconds), State.Loading)

    init {
        viewModelScope.launch(Dispatchers.Default) {
            downloadManager.statusFlow()
                .filter { it.manga.id == mangaId }
                .catch { logcat(LogPriority.ERROR, it) }
                .collect(::updateDownloadState)
        }
        viewModelScope.launch(Dispatchers.Default) {
            downloadManager.progressFlow()
                .filter { it.manga.id == mangaId }
                .catch { logcat(LogPriority.ERROR, it) }
                .collect { downloadProgress[it.chapter.id] = it.progress }
        }

        viewModelScope.launch {
            // The state loads the entry and its chapters anyway, so they aren't queried separately
            val loadedState = state.filterIsInstance<State.Success>().first()
            val manga = loadedState.manga
            val needRefreshInfo = !manga.initialized
            val needRefreshChapter = loadedState.chapters.isEmpty()

            // Fetch info-chapters when needed
            if (needRefreshInfo || needRefreshChapter) {
                isRefreshingData.value = true
                fetchAllFromSource(
                    manga = manga,
                    manualFetch = false,
                    fetchDetails = needRefreshInfo,
                    fetchChapters = needRefreshChapter,
                )
                isRefreshingData.value = false
            }
        }
    }

    fun fetchAllFromSource(manualFetch: Boolean = true) {
        viewModelScope.launch {
            isRefreshingData.value = true
            fetchAllFromSource(
                manga = getMangaAndChapters.awaitManga(mangaId),
                manualFetch = manualFetch,
                fetchDetails = true,
                fetchChapters = true,
            )
            isRefreshingData.value = false
        }
    }

    private suspend fun fetchAllFromSource(
        manga: Manga,
        manualFetch: Boolean,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ) {
        try {
            val update = updateMangaFromRemote(
                source = sourceManager.getOrStub(manga.source),
                manga = manga,
                fetchDetails = fetchDetails,
                fetchChapters = fetchChapters,
                manualFetch = manualFetch,
            )
                .getOrThrow()

            if (manualFetch) {
                downloadNewChapters(update.newChapters)
            }
        } catch (_: CancellationException) {
            // ignore
        } catch (e: Exception) {
            val message = if (e is NoChaptersException) {
                context.stringResource(MR.strings.no_chapters_error)
            } else {
                logcat(LogPriority.ERROR, e)
                with(context) { e.formattedMessage }
            }

            viewModelScope.launch {
                snackbarHostState.showSnackbar(message = message)
            }
        }
    }

    // Manga info - start

    fun toggleFavorite() {
        toggleFavorite(
            onRemoved = {
                viewModelScope.launch {
                    if (!hasDownloads()) return@launch
                    val result = snackbarHostState.showSnackbar(
                        message = context.stringResource(MR.strings.delete_downloads_for_manga),
                        actionLabel = context.stringResource(MR.strings.action_delete),
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        deleteDownloads()
                    }
                }
            },
        )
    }

    /**
     * Update favorite status of manga, (removes / adds) manga (to / from) library.
     */
    fun toggleFavorite(
        onRemoved: () -> Unit,
        checkDuplicate: Boolean = true,
    ) {
        val state = successState ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val manga = state.manga

            if (isFavorited) {
                // Remove from library
                if (updateManga.awaitUpdateFavorite(manga.id, false)) {
                    // Remove covers and update last modified in db
                    if (manga.removeCovers(coverCache) != manga) {
                        updateManga.awaitUpdateCoverLastModified(manga.id)
                    }
                    onRemoved()
                }
            } else {
                // Add to library
                // First, check if duplicate exists if callback is provided
                if (checkDuplicate) {
                    val duplicates = getDuplicateLibraryManga(manga)

                    if (duplicates.isNotEmpty()) {
                        dialog.value = Dialog.DuplicateManga(manga, duplicates)
                        return@launch
                    }
                }

                // Now check if user previously set categories, when available
                val categories = getCategories()
                val defaultCategoryId = libraryPreferences.defaultCategory.get().toLong()
                val defaultCategory = categories.find { it.id == defaultCategoryId }
                when {
                    // Default category set
                    defaultCategory != null -> {
                        val result = updateManga.awaitUpdateFavorite(manga.id, true)
                        if (!result) return@launch
                        moveMangaToCategory(defaultCategory)
                    }

                    // Automatic 'Default' or no categories
                    defaultCategoryId == 0L || categories.isEmpty() -> {
                        val result = updateManga.awaitUpdateFavorite(manga.id, true)
                        if (!result) return@launch
                        moveMangaToCategory(null)
                    }

                    // Choose a category
                    else -> showChangeCategoryDialog()
                }

                // Finally match with enhanced tracking when available
                appScope.launch { addTracks.bindEnhancedTrackers(manga, state.source) }
            }
        }
    }

    fun showRemoveFromLibraryDialog() {
        if (successState?.manga?.favorite == true) dialog.value = Dialog.RemoveFromLibrary
    }

    fun showChangeCategoryDialog() {
        val manga = successState?.manga ?: return
        viewModelScope.launch {
            val categories = getCategories()
            val selection = getMangaCategoryIds(manga)
            dialog.value = Dialog.ChangeCategory(
                manga = manga,
                initialSelection = categories.mapAsCheckboxState { it.id in selection },
            )
        }
    }

    fun showSetFetchIntervalDialog() {
        val manga = successState?.manga ?: return
        dialog.value = Dialog.SetFetchInterval(manga)
    }

    fun setFetchInterval(manga: Manga, interval: Int) {
        viewModelScope.launch {
            updateManga.awaitUpdateFetchInterval(
                // Custom intervals are negative
                manga.copy(fetchInterval = -interval),
            )
        }
    }

    /**
     * Returns true if the manga has any downloads.
     *
     * One question asked of the download manager, which is now the only thing that holds a download of any kind: a
     * novel chapter's text file is in the same index a manga's page folder is in, so there is no second mechanism
     * left that could hold something this one cannot see.
     */
    private suspend fun hasDownloads(): Boolean {
        val manga = successState?.manga ?: return false
        return mangaDownloadPlan(manga) != MangaDownloadPlan.Nothing
    }

    /**
     * Deletes all the downloads for the manga.
     *
     * Planned again here rather than reusing what the prompt was built from: the user is answering a snackbar
     * that may have been on screen for a while, and what is on disk now is what they agreed to delete.
     *
     * `deleteManga` is the one path that clears everything a manga ever downloaded: the folder goes, which takes
     * a novel's text files with it, and it drops the cached content rows as well, so nothing is left behind that
     * would still open with the server off.
     */
    private suspend fun deleteDownloads() {
        val state = successState ?: return
        when (mangaDownloadPlan(state.manga)) {
            MangaDownloadPlan.Nothing -> Unit
            MangaDownloadPlan.DeleteImagePages ->
                appScope.launch { downloadManager.deleteManga(state.manga, state.source) }
        }
    }

    /**
     * What this manga's downloads are, read from the one place a download can live.
     *
     * The prompt above and the delete below both go through here, so the two cannot answer differently: what the
     * user was offered to clear is what clearing it actually does.
     */
    private suspend fun mangaDownloadPlan(manga: Manga): MangaDownloadPlan = planMangaDownloads(
        imageDownloadCount = downloadManager.getDownloadCount(manga),
    )

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.await().filterNot { it.isSystemCategory }
    }

    /**
     * Gets the category id's the manga is in, if the manga is not in a category, returns the default id.
     *
     * @param manga the manga to get categories from.
     * @return Array of category ids the manga is in, if none returns default id
     */
    private suspend fun getMangaCategoryIds(manga: Manga): List<Long> {
        return getCategories.await(manga.id)
            .map { it.id }
    }

    fun moveMangaToCategoriesAndAddToLibrary(manga: Manga, categories: List<Long>) {
        moveMangaToCategory(categories)
        if (manga.favorite) return

        viewModelScope.launch {
            updateManga.awaitUpdateFavorite(manga.id, true)
        }
    }

    /**
     * Move the given manga to categories.
     *
     * @param categories the selected categories.
     */
    private fun moveMangaToCategories(categories: List<Category>) {
        val categoryIds = categories.map { it.id }
        moveMangaToCategory(categoryIds)
    }

    private fun moveMangaToCategory(categoryIds: List<Long>) {
        viewModelScope.launch {
            setMangaCategories.await(mangaId, categoryIds)
        }
    }

    /**
     * Move the given manga to the category.
     *
     * @param category the selected category, or null for default category.
     */
    private fun moveMangaToCategory(category: Category?) {
        moveMangaToCategories(listOfNotNull(category))
    }

    // Manga info - end

    // Chapters list - start

    private fun updateDownloadState(download: Download) {
        val chapterId = download.chapter.id
        downloadStates.update {
            // Terminal states are derived by the queried item itself, so drop the override instead
            // of letting it outlive reality, e.g. showing a since deleted chapter as downloaded.
            if (download.status == Download.State.NOT_DOWNLOADED || download.status == Download.State.DOWNLOADED) {
                it - chapterId
            } else {
                it + (chapterId to download.status)
            }
        }
        if (download.status == Download.State.DOWNLOADING) {
            downloadProgress[chapterId] = download.progress
        } else {
            downloadProgress.remove(chapterId)
        }
    }

    fun getDownloadProgress(chapterId: Long): Int {
        return downloadProgress[chapterId] ?: 0
    }

    private fun List<Chapter>.toChapterListItems(
        manga: Manga,
        novelReadProgress: Map<Long, Float>,
    ): List<ChapterList.Item> {
        val isLocal = manga.isLocal()
        val queuedDownloads = if (isLocal) emptyMap() else downloadManager.getQueuedDownloadsByChapterId()
        val downloadedChapterIds = if (isLocal) emptySet() else downloadManager.getDownloadedChapterIds(this, manga)
        return map { chapter ->
            ChapterList.Item(
                chapter = chapter,
                downloadState = chapterDownloadState(
                    queuedDownloadState = queuedDownloads[chapter.id]?.status,
                    // The download index is the whole answer for both kinds, which is what lets the badge mean the
                    // downloads folder. A novel chapter that was only ever read is absent from it, and says so.
                    isDownloaded = isLocal || chapter.id in downloadedChapterIds,
                ),
                novelReadProgress = novelReadProgress[chapter.id],
            )
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    fun chapterSwipe(chapterItem: ChapterList.Item, swipeAction: LibraryPreferences.ChapterSwipeAction) {
        viewModelScope.launch {
            executeChapterSwipeAction(chapterItem, swipeAction)
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    private fun executeChapterSwipeAction(
        chapterItem: ChapterList.Item,
        swipeAction: LibraryPreferences.ChapterSwipeAction,
    ) {
        val chapter = chapterItem.chapter
        when (swipeAction) {
            LibraryPreferences.ChapterSwipeAction.ToggleRead -> {
                markChaptersRead(listOf(chapter), !chapter.read)
            }
            LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> {
                bookmarkChapters(listOf(chapter), !chapter.bookmark)
            }
            LibraryPreferences.ChapterSwipeAction.Download -> {
                val downloadAction: ChapterDownloadAction = when (chapterItem.downloadState) {
                    Download.State.ERROR,
                    Download.State.NOT_DOWNLOADED,
                    -> ChapterDownloadAction.START_NOW
                    Download.State.QUEUE,
                    Download.State.DOWNLOADING,
                    -> ChapterDownloadAction.CANCEL
                    Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
                }
                runChapterDownloadActions(
                    items = listOf(chapterItem),
                    action = downloadAction,
                )
            }
            LibraryPreferences.ChapterSwipeAction.Disabled -> throw IllegalStateException()
        }
    }

    /**
     * Returns the next unread chapter or null if everything is read.
     */
    fun getNextUnreadChapter(): Chapter? {
        val successState = successState ?: return null
        if (successState.source.isNovelSource()) {
            return novelNextUnread(successState.chapters, successState.manga)
        }
        return successState.chapters.getNextUnread(successState.manga)
    }

    private fun getUnreadChapters(): List<Chapter> {
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filter { (chapter, dlStatus) -> !chapter.read && dlStatus == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    private fun getUnreadChaptersSorted(): List<Chapter> {
        val manga = successState?.manga ?: return emptyList()
        val chaptersSorted = getUnreadChapters().sortedWith(getChapterSort(manga))
        return if (manga.sortDescending()) chaptersSorted.reversed() else chaptersSorted
    }

    private fun getBookmarkedChapters(): List<Chapter> {
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filter { (chapter, dlStatus) -> chapter.bookmark && dlStatus == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    private fun startDownload(
        chapters: List<Chapter>,
        startNow: Boolean,
    ) {
        if (successState == null) return
        val startNowId = if (startNow) chapters.singleOrNull()?.id ?: return else null

        viewModelScope.launch {
            // The queueing outlives the screen, the add to library prompt doesn't
            appScope.launch {
                if (startNowId != null) {
                    downloadManager.startDownloadNow(startNowId)
                } else {
                    downloadChapters(chapters)
                }
            }.join()
            if (startNowId == null) toggleAllSelection(false)

            if (!isFavorited && !hasPromptedToAddBefore) {
                hasPromptedToAddBefore = true
                val result = snackbarHostState.showSnackbar(
                    message = context.stringResource(MR.strings.snack_add_to_library),
                    actionLabel = context.stringResource(MR.strings.action_add),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed && !isFavorited) {
                    toggleFavorite()
                }
            }
        }
    }

    fun runChapterDownloadActions(
        items: List<ChapterList.Item>,
        action: ChapterDownloadAction,
    ) {
        when (val plan = planChapterDownloadAction(items, action)) {
            ChapterDownloadPlan.Nothing -> Unit
            is ChapterDownloadPlan.QueueImagePages -> {
                startDownload(plan.chapters, startNow = false)
                if (plan.restartFailedDownloads) {
                    downloadManager.startDownloads()
                }
            }
            is ChapterDownloadPlan.StartImagePagesNow -> startDownload(plan.chapters, startNow = true)
            is ChapterDownloadPlan.CancelImageDownload -> cancelDownload(plan.chapterId)
            is ChapterDownloadPlan.DeleteImagePages -> deleteChapters(plan.chapters)
        }
    }

    fun runDownloadAction(action: DownloadAction) {
        val chaptersToDownload = when (action) {
            DownloadAction.NEXT_1_CHAPTER -> getUnreadChaptersSorted().take(1)
            DownloadAction.NEXT_5_CHAPTERS -> getUnreadChaptersSorted().take(5)
            DownloadAction.NEXT_10_CHAPTERS -> getUnreadChaptersSorted().take(10)
            DownloadAction.NEXT_25_CHAPTERS -> getUnreadChaptersSorted().take(25)
            DownloadAction.UNREAD_CHAPTERS -> getUnreadChapters()
            DownloadAction.BOOKMARKED_CHAPTERS -> getBookmarkedChapters()
        }
        if (chaptersToDownload.isNotEmpty()) {
            startDownload(chaptersToDownload, false)
        }
    }

    private fun cancelDownload(chapterId: Long) {
        val activeDownload = downloadManager.getQueuedDownloadOrNull(chapterId) ?: return
        viewModelScope.launch {
            downloadManager.cancelQueuedDownloads(listOf(activeDownload))
            updateDownloadState(activeDownload.apply { status = Download.State.NOT_DOWNLOADED })
        }
    }

    fun markPreviousChapterRead(pointer: Chapter) {
        val manga = successState?.manga ?: return
        val chapters = filteredChapters.orEmpty().map { it.chapter }
        val prevChapters = if (manga.sortDescending()) chapters.asReversed() else chapters
        val pointerPos = prevChapters.indexOf(pointer)
        if (pointerPos != -1) markChaptersRead(prevChapters.take(pointerPos), true)
    }

    /**
     * Mark the selected chapter list as read/unread.
     * @param chapters the list of selected chapters.
     * @param read whether to mark chapters as read or unread.
     */
    fun markChaptersRead(chapters: List<Chapter>, read: Boolean) {
        toggleAllSelection(false)
        if (chapters.isEmpty()) return
        viewModelScope.launch {
            // The read state is written even if the screen closes, the tracking prompt isn't
            appScope.launch {
                setReadStatus.await(
                    read = read,
                    chapters = chapters.toTypedArray(),
                )
            }.join()

            if (!read || successState?.hasLoggedInTrackers == false || autoTrackState == AutoTrackState.NEVER) {
                return@launch
            }

            refreshTrackers()

            val tracks = getTracks.await(mangaId)
            val maxChapterNumber = chapters.maxOf { it.chapterNumber }
            val shouldPromptTrackingUpdate = tracks.any { track -> maxChapterNumber > track.lastChapterRead }

            if (!shouldPromptTrackingUpdate) return@launch
            if (autoTrackState == AutoTrackState.ALWAYS) {
                trackChapter.await(context, mangaId, maxChapterNumber)
                context.toast(context.stringResource(MR.strings.trackers_updated_summary, maxChapterNumber.toInt()))
                return@launch
            }

            val result = snackbarHostState.showSnackbar(
                message = context.stringResource(MR.strings.confirm_tracker_update, maxChapterNumber.toInt()),
                actionLabel = context.stringResource(MR.strings.action_ok),
                duration = SnackbarDuration.Short,
                withDismissAction = true,
            )

            if (result == SnackbarResult.ActionPerformed) {
                trackChapter.await(context, mangaId, maxChapterNumber)
            }
        }
    }

    private suspend fun refreshTrackers() {
        refreshTracks.await(mangaId)
            .filter { it.first != null }
            .forEach { (track, e) ->
                logcat(LogPriority.ERROR, e) {
                    "Failed to refresh track data mangaId=$mangaId for service ${track!!.id}"
                }
                withContext(Dispatchers.Main) {
                    context.toast(
                        context.stringResource(
                            MR.strings.track_error,
                            track!!.name,
                            e.message ?: "",
                        ),
                    )
                }
            }
    }

    /**
     * Downloads the given list of chapters with the manager.
     *
     * Every chapter goes to the same queue, novel ones included: a novel chapter is prose, so `Downloader` writes it
     * as one text file instead of asking a source for pages, but it is queued, reported, cancelled, indexed and
     * deleted by exactly the same code. Splitting them here is what made this screen's behaviour differ from the
     * library's, the updates tab's and auto-download's, all of which always went through the manager.
     *
     * Every bulk entry point lands here — "download the next 5", "download unread", "download bookmarked", and the
     * auto-download that follows a manual refresh.
     *
     * @param chapters the list of chapters to download.
     */
    private suspend fun downloadChapters(chapters: List<Chapter>) {
        val manga = successState?.manga ?: return
        if (chapters.isEmpty()) return
        downloadManager.downloadChapters(manga, chapters)
    }

    /**
     * Bookmarks the given list of chapters.
     * @param chapters the list of chapters to bookmark.
     */
    fun bookmarkChapters(chapters: List<Chapter>, bookmarked: Boolean) {
        viewModelScope.launch {
            chapters
                .filterNot { it.bookmark == bookmarked }
                .map { ChapterUpdate(it.id) { bookmark = bookmarked } }
                .let { updateChapter.awaitAll(it) }
        }
        toggleAllSelection(false)
    }

    /**
     * Deletes the given list of chapter.
     *
     * @param chapters the list of chapters to delete.
     */
    fun deleteChapters(chapters: List<Chapter>) {
        val state = successState ?: return
        // Outlives the screen, which the delete dialog usually closes along with
        appScope.launch {
            try {
                downloadManager.deleteChapters(chapters, state.manga, state.source)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    private fun downloadNewChapters(chapters: List<Chapter>) {
        appScope.launch {
            val manga = successState?.manga ?: return@launch
            val chaptersToDownload = filterChaptersForDownload.await(manga, chapters)

            if (chaptersToDownload.isNotEmpty()) {
                downloadChapters(chaptersToDownload)
            }
        }
    }

    /**
     * Sets the read filter and requests an UI update.
     * @param state whether to display only unread chapters or all chapters.
     */
    fun setUnreadFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_UNREAD
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_READ
        }
        appScope.launch {
            setMangaChapterFlags.awaitSetUnreadFilter(manga, flag)
        }
    }

    /**
     * Sets the download filter and requests an UI update.
     * @param state whether to display only downloaded chapters or all chapters.
     */
    fun setDownloadedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_DOWNLOADED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
        }

        appScope.launch {
            setMangaChapterFlags.awaitSetDownloadedFilter(manga, flag)
        }
    }

    /**
     * Sets the bookmark filter and requests an UI update.
     * @param state whether to display only bookmarked chapters or all chapters.
     */
    fun setBookmarkedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_BOOKMARKED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
        }

        appScope.launch {
            setMangaChapterFlags.awaitSetBookmarkFilter(manga, flag)
        }
    }

    /**
     * Sets the active display mode.
     * @param mode the mode to set.
     */
    fun setDisplayMode(mode: Long) {
        val manga = successState?.manga ?: return

        appScope.launch {
            setMangaChapterFlags.awaitSetDisplayMode(manga, mode)
        }
    }

    /**
     * Sets the sorting method and requests an UI update.
     * @param sort the sorting mode.
     */
    fun setSorting(sort: Long) {
        val manga = successState?.manga ?: return

        appScope.launch {
            setMangaChapterFlags.awaitSetSortingModeOrFlipOrder(manga, sort)
        }
    }

    fun setCurrentSettingsAsDefault(applyToExisting: Boolean) {
        val manga = successState?.manga ?: return
        viewModelScope.launch {
            appScope.launch {
                libraryPreferences.setChapterSettingsDefault(manga)
                if (applyToExisting) {
                    setMangaDefaultChapterFlags.awaitAll()
                }
            }.join()
            snackbarHostState.showSnackbar(message = context.stringResource(MR.strings.chapter_settings_updated))
        }
    }

    fun resetToDefaultSettings() {
        val manga = successState?.manga ?: return
        viewModelScope.launch {
            setMangaDefaultChapterFlags.await(manga)
        }
    }

    fun toggleSelection(
        item: ChapterList.Item,
        selected: Boolean,
        fromLongPress: Boolean = false,
    ) {
        val chapters = successState?.processedChapters ?: return
        val selectedIndex = chapters.indexOfFirst { it.id == item.chapter.id }
        if (selectedIndex < 0) return

        // Read from the ids, as the state catches up with a selection only after it's been made
        val selectedIds = HashSet(selectedChapterIds.value)
        if ((item.id in selectedIds) == selected) return

        val firstSelection = chapters.none { it.id in selectedIds }
        selectedIds.addOrRemove(item.id, selected)

        if (selected && fromLongPress) {
            if (firstSelection) {
                selectedPositions[0] = selectedIndex
                selectedPositions[1] = selectedIndex
            } else {
                // Try to select the items in-between when possible
                val range: IntRange
                if (selectedIndex < selectedPositions[0]) {
                    range = selectedIndex + 1..<selectedPositions[0]
                    selectedPositions[0] = selectedIndex
                } else if (selectedIndex > selectedPositions[1]) {
                    range = (selectedPositions[1] + 1)..<selectedIndex
                    selectedPositions[1] = selectedIndex
                } else {
                    // Just select itself
                    range = IntRange.EMPTY
                }

                range.forEach { selectedIds.add(chapters[it].id) }
            }
        } else if (!fromLongPress) {
            if (!selected) {
                if (selectedIndex == selectedPositions[0]) {
                    selectedPositions[0] = chapters.indexOfFirst { it.id in selectedIds }
                } else if (selectedIndex == selectedPositions[1]) {
                    selectedPositions[1] = chapters.indexOfLast { it.id in selectedIds }
                }
            } else {
                if (selectedIndex < selectedPositions[0]) {
                    selectedPositions[0] = selectedIndex
                } else if (selectedIndex > selectedPositions[1]) {
                    selectedPositions[1] = selectedIndex
                }
            }
        }
        selectedChapterIds.value = selectedIds
    }

    fun toggleAllSelection(selected: Boolean) {
        selectedChapterIds.value = if (selected) allChapters.orEmpty().mapTo(HashSet()) { it.id } else emptySet()
        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    fun invertSelection() {
        val selectedIds = selectedChapterIds.value
        selectedChapterIds.value = allChapters.orEmpty().filterNot { it.id in selectedIds }.mapTo(HashSet()) { it.id }
        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    // Chapters list - end

    sealed interface Dialog {
        data class ChangeCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState<Category>>,
        ) : Dialog
        data class DeleteChapters(val chapters: List<Chapter>) : Dialog
        data class DuplicateManga(val manga: Manga, val duplicates: List<MangaWithChapterCount>) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
        data class SetFetchInterval(val manga: Manga) : Dialog
        data object RemoveFromLibrary : Dialog
        data object SettingsSheet : Dialog
        data object TrackSheet : Dialog
        data object FullCover : Dialog
    }

    fun dismissDialog() {
        dialog.value = null
    }

    fun showDeleteChapterDialog(chapters: List<Chapter>) {
        dialog.value = Dialog.DeleteChapters(chapters)
    }

    fun showSettingsDialog() {
        dialog.value = Dialog.SettingsSheet
    }

    fun showTrackDialog() {
        dialog.value = Dialog.TrackSheet
    }

    fun showCoverDialog() {
        dialog.value = Dialog.FullCover
    }

    fun showMigrateDialog(duplicate: Manga) {
        val manga = successState?.manga ?: return
        dialog.value = Dialog.Migrate(target = manga, current = duplicate)
    }

    fun setExcludedScanlators(excludedScanlators: Set<String>) {
        viewModelScope.launch {
            setExcludedScanlators.await(mangaId, excludedScanlators)
        }
    }

    private data class MangaAndChapters(val manga: Manga, val source: Source, val chapters: List<ChapterList.Item>)

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Success(
            val manga: Manga,
            val source: Source,
            val isFromSource: Boolean,
            val chapters: List<ChapterList.Item>,
            val availableScanlators: Set<String>,
            val excludedScanlators: Set<String>,
            val trackingCount: Int = 0,
            val hasLoggedInTrackers: Boolean = false,
            val isRefreshingData: Boolean = false,
            val dialog: Dialog? = null,
            val hideMissingChapters: Boolean = false,
        ) : State {
            val processedChapters by lazy {
                chapters.applyFilters(manga).toList()
            }

            val isAnySelected by lazy {
                chapters.fastAny { it.selected }
            }

            val selectedChapters by lazy {
                processedChapters.fastFilter { it.selected }
            }

            val hasUnreadChapters by lazy {
                processedChapters.fastAny { !it.chapter.read }
            }

            val hasReadChapters by lazy {
                chapters.fastAny { it.chapter.read }
            }

            val missingChapterCount by lazy {
                if (hideMissingChapters) return@lazy 0
                processedChapters.fastMap { it.chapter.chapterNumber }.missingChaptersCount()
            }

            val chapterListItems by lazy {
                if (hideMissingChapters) {
                    return@lazy processedChapters
                }

                processedChapters.insertSeparators { before, after ->
                    val (lowerChapter, higherChapter) = if (manga.sortDescending()) {
                        after to before
                    } else {
                        before to after
                    }
                    if (higherChapter == null) return@insertSeparators null

                    if (lowerChapter == null) {
                        floor(higherChapter.chapter.chapterNumber)
                            .toInt()
                            .minus(1)
                            .coerceAtLeast(0)
                    } else {
                        calculateChapterGap(higherChapter.chapter, lowerChapter.chapter)
                    }
                        .takeIf { it > 0 }
                        ?.let { missingCount ->
                            ChapterList.MissingCount(
                                id = "${lowerChapter?.id}-${higherChapter.id}",
                                count = missingCount,
                            )
                        }
                }
            }

            val scanlatorFilterActive: Boolean
                get() = excludedScanlators.intersect(availableScanlators).isNotEmpty()

            val filterActive: Boolean
                get() = scanlatorFilterActive || manga.chaptersFiltered()

            /**
             * Applies the view filters to the list of chapters obtained from the database.
             * @return an observable of the list of chapters filtered and sorted.
             */
            private fun List<ChapterList.Item>.applyFilters(manga: Manga): Sequence<ChapterList.Item> {
                val isLocalManga = manga.isLocal()
                val unreadFilter = manga.unreadFilter
                val downloadedFilter = manga.downloadedFilter
                val bookmarkedFilter = manga.bookmarkedFilter
                val chapterSort = getChapterSort(manga)
                return asSequence()
                    .filter { (chapter) -> applyFilter(unreadFilter) { !chapter.read } }
                    .filter { (chapter) -> applyFilter(bookmarkedFilter) { chapter.bookmark } }
                    .filter { applyFilter(downloadedFilter) { it.isDownloaded || isLocalManga } }
                    .sortedWith { (chapter1), (chapter2) -> chapterSort(chapter1, chapter2) }
            }
        }
    }
}

@Immutable
sealed class ChapterList {
    @Immutable
    data class MissingCount(
        val id: String,
        val count: Int,
    ) : ChapterList()

    @Immutable
    data class Item(
        val chapter: Chapter,
        val downloadState: Download.State,
        val selected: Boolean = false,
        /**
         * How far through the chapter the novel reader got, from 0 to 1, or null when it has not been opened.
         *
         * Kept apart from [Chapter.lastPageRead], which is the image reader's page index and stays 0 for a novel:
         * overloading it would make "page 3 of 10" and "30% of the way through" indistinguishable to every reader
         * of that field.
         */
        val novelReadProgress: Float? = null,
    ) : ChapterList() {
        val id = chapter.id
        val isDownloaded = downloadState == Download.State.DOWNLOADED
    }
}

/**
 * The page number an image chapter stopped at, for the chapter list's progress slot.
 *
 * Null means there is nothing worth showing: never opened, or already read, in which case the slot stays empty
 * rather than repeating the page count of a finished chapter.
 */
internal fun imageChapterProgressPage(read: Boolean, lastPageRead: Int): Int? {
    return lastPageRead.takeIf { !read && it > 0 }
}

/**
 * A novel chapter's saved fraction as the whole percentage the list shows, or null when there is nothing to show.
 *
 * Null for a chapter that was never opened, and null for one already read, exactly like [imageChapterProgressPage]:
 * a finished chapter's title is greyed out on its own, so repeating where it stopped is noise. The rounding is to
 * nearest so 45.6% reads as 46%, and the clamp keeps a corrupt row from reaching the UI as more than 100%.
 */
internal fun novelChapterProgressPercent(read: Boolean, percent: Float?): Int? {
    if (read) return null
    return percent?.let { (it * 100).roundToInt().coerceIn(0, 100) }
}

/**
 * The download state a chapter row shows.
 *
 * Every chapter is downloaded by the same queue now, so the queue's own state is the first answer for both kinds
 * and there is no second mechanism whose state has to be consulted instead. What is left is the queue's transient
 * state, then whether the chapter is in the download index, which is where a novel's text file is as much as a
 * manga's page folder is.
 *
 * The badge describes the downloads folder, so nothing else may answer for it. In particular the reader's own
 * content cache may not: opening a chapter writes its text there, and treating that as a download is what marked
 * every chapter the user had only read as downloaded with nothing to delete. Reading such a chapter with no network
 * is still true afterwards, and still true through [tachiyomi.domain.novel.interactor.GetChapterContent], which
 * reads that cache before it asks the source — what was lost is only the icon.
 */
internal fun chapterDownloadState(
    queuedDownloadState: Download.State?,
    isDownloaded: Boolean,
): Download.State = when {
    queuedDownloadState != null -> queuedDownloadState
    isDownloaded -> Download.State.DOWNLOADED
    else -> Download.State.NOT_DOWNLOADED
}

/**
 * The image queue's own state, narrowed to the chapters the queue still holds.
 *
 * Two things depend on that narrowing. A finished chapter leaves the queue and its last status change can be
 * lost with it, so it is left to the queried item. And a chapter that was queued and has since left it has no
 * state left to report, which is a null this drops rather than a `NOT_DOWNLOADED` the row would inherit.
 */
internal fun queuedDownloadStatesFor(
    states: Map<Long, Download.State>,
    queue: List<Download>,
): Map<Long, Download.State> {
    val queuedChapterIds = queue.mapTo(HashSet()) { it.chapter.id }
    return states.filterKeys { it in queuedChapterIds }
}

/**
 * Applies the download queue's transient state and the selection to one chapter row.
 *
 * [queuedDownloadState] is null for every chapter the queue does not hold, which means "the queue has nothing to
 * say" and never "not downloaded".
 */
internal fun withQueuedDownloadState(
    item: ChapterList.Item,
    queuedDownloadState: Download.State?,
    selected: Boolean,
): ChapterList.Item {
    if (queuedDownloadState == null && !selected) return item
    return item.copy(downloadState = queuedDownloadState ?: item.downloadState, selected = selected)
}

/**
 * What deleting a manga's downloads has to clear.
 *
 * One case, because there is one mechanism: the download folder for this manga, whatever shape the files in it
 * have. A novel's chapters are indexed in the same place a manga's are and are removed by the same call.
 */
internal sealed interface MangaDownloadPlan {
    /** Nothing is downloaded, so there is nothing to offer and nothing to delete. */
    data object Nothing : MangaDownloadPlan

    /** Delete every download for the manga, exactly as it was done before novels existed. */
    data object DeleteImagePages : MangaDownloadPlan
}

/**
 * Resolves what a manga's downloads are.
 *
 * [imageDownloadCount] is the download manager's own count for this manga, read by the caller. Zero is
 * [MangaDownloadPlan.Nothing], which is the only answer that means there is nothing to prompt about.
 */
internal fun planMangaDownloads(imageDownloadCount: Int): MangaDownloadPlan = when {
    imageDownloadCount > 0 -> MangaDownloadPlan.DeleteImagePages
    else -> MangaDownloadPlan.Nothing
}

/**
 * What a chapter download action actually has to do.
 *
 * Every action the download button and the swipe action can raise resolves to exactly one of these before anything
 * is started, and every one of them is an action on the download queue. A novel chapter resolves to the same plans
 * as an image one: the plan carries the chapters, and the downloader is what knows a chapter of prose is written
 * as one text file rather than as pages.
 */
internal sealed interface ChapterDownloadPlan {
    /** Nothing to do: an empty selection, or an action that means nothing for this many chapters. */
    data object Nothing : ChapterDownloadPlan

    /** Queue these chapters. [restartFailedDownloads] is the retry of ERROR rows. */
    data class QueueImagePages(
        val chapters: List<Chapter>,
        val restartFailedDownloads: Boolean,
    ) : ChapterDownloadPlan

    /** Put one chapter at the front of the queue and start now. */
    data class StartImagePagesNow(val chapters: List<Chapter>) : ChapterDownloadPlan

    /** Take one chapter back out of the queue. */
    data class CancelImageDownload(val chapterId: Long) : ChapterDownloadPlan

    /** Delete the downloads of these chapters. */
    data class DeleteImagePages(val chapters: List<Chapter>) : ChapterDownloadPlan
}

/**
 * Resolves a download action into the single thing it means for these chapters.
 *
 * The guards the image path already had are kept as they were: START_NOW and CANCEL act on one chapter only, and
 * a selection of the wrong size resolves to [ChapterDownloadPlan.Nothing] rather than quietly taking the first
 * row. There is nothing to measure a novel chapter against a rule written for an image one any more, because there
 * is one set of rules and it is the queue's.
 */
internal fun planChapterDownloadAction(
    items: List<ChapterList.Item>,
    action: ChapterDownloadAction,
): ChapterDownloadPlan {
    val chapters = items.map { it.chapter }
    return when (action) {
        ChapterDownloadAction.START -> when {
            chapters.isNotEmpty() -> ChapterDownloadPlan.QueueImagePages(
                chapters = chapters,
                restartFailedDownloads = items.any { it.downloadState == Download.State.ERROR },
            )
            else -> ChapterDownloadPlan.Nothing
        }
        ChapterDownloadAction.START_NOW -> when {
            chapters.size == 1 -> ChapterDownloadPlan.StartImagePagesNow(chapters)
            else -> ChapterDownloadPlan.Nothing
        }
        ChapterDownloadAction.CANCEL -> when {
            chapters.size == 1 -> ChapterDownloadPlan.CancelImageDownload(chapters.single().id)
            else -> ChapterDownloadPlan.Nothing
        }
        ChapterDownloadAction.DELETE -> when {
            chapters.isNotEmpty() -> ChapterDownloadPlan.DeleteImagePages(chapters)
            else -> ChapterDownloadPlan.Nothing
        }
    }
}
