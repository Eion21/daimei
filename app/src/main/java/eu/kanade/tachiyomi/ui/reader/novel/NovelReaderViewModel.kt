package eu.kanade.tachiyomi.ui.reader.novel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.novel.reader.core.ReadingPosition
import app.novel.source.api.ChapterContent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.novel.interactor.GetChapterContent
import tachiyomi.domain.novel.interactor.GetReadingProgress
import tachiyomi.domain.novel.interactor.SaveReadingProgress
import tachiyomi.domain.novel.repository.ReadingProgress
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A chapter that could not be opened, with the reason when there is one to give.
 *
 * [reason] is null more often than not: [GetChapterContent] resolves every miss — a source that is down, a chapter
 * with no row, a memo naming a source this build does not ship — to a null content and names the reason in its log
 * alone. What is left to escape it is a store that cannot be read at all, and that message is the only reason the
 * reader can put on screen, so a null here means "nothing to name" rather than "nothing went wrong".
 */
data class NovelChapterError(val reason: String?)

/** Titles from the chapter and work metadata already read when resolving the document. */
data class NovelReaderTitles(val work: String = "", val chapter: String = "")

/** Non-null Metro binding boundary; the nullable compatibility constructor is not an injection entry point. */
@Inject
internal class NovelReaderNavigationServices(
    val chapters: GetChaptersByMangaId,
    val reader: ReaderPreferences,
    val base: BasePreferences,
    val downloads: DownloadManager,
)

/**
 * State for one novel chapter.
 *
 * Owns no Android view: the host composable collects this and hands it to
 * [app.novel.reader.compose.NovelReaderScreen].
 *
 * It is also where the order a novel chapter is resolved in is decided, because that order spans three stores and
 * only one class is allowed to know all of them: the downloaded file ([DownloadProvider]), the content cache and the
 * source ([GetChapterContent], in `:domain`). See [resolveContent].
 */
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class NovelReaderViewModel(
    private val getChapterContent: GetChapterContent,
    private val downloadProvider: DownloadProvider,
    private val getChapter: GetChapter,
    private val getManga: GetManga,
    private val sourceManager: SourceManager,
    private val getReadingProgress: GetReadingProgress,
    private val saveReadingProgress: SaveReadingProgress,
    private val updateChapter: UpdateChapter,
    private val upsertHistory: UpsertHistory,
    private val trackChapter: TrackChapter,
    private val trackPreferences: TrackPreferences,
    private val getIncognitoState: GetIncognitoState,
    private val context: Context,
    @AppCoroutineScope private val appScope: CoroutineScope,
    // Nullable compatibility API for existing callers, deliberately not constructor-injected.
    // Production injection delegates here through the non-null service wrapper below.
    getChaptersByMangaId: GetChaptersByMangaId?,
    readerPreferences: ReaderPreferences?,
    basePreferences: BasePreferences?,
    downloadManager: DownloadManager?,
) : ViewModel() {

    @Inject
    internal constructor(
        getChapterContent: GetChapterContent,
        downloadProvider: DownloadProvider,
        getChapter: GetChapter,
        getManga: GetManga,
        sourceManager: SourceManager,
        getReadingProgress: GetReadingProgress,
        saveReadingProgress: SaveReadingProgress,
        updateChapter: UpdateChapter,
        upsertHistory: UpsertHistory,
        trackChapter: TrackChapter,
        trackPreferences: TrackPreferences,
        getIncognitoState: GetIncognitoState,
        context: Context,
        @AppCoroutineScope appScope: CoroutineScope,
        services: NovelReaderNavigationServices,
    ) : this(
        getChapterContent, downloadProvider, getChapter, getManga, sourceManager,
        getReadingProgress, saveReadingProgress, updateChapter, upsertHistory,
        trackChapter, trackPreferences, getIncognitoState, context, appScope,
        services.chapters, services.reader, services.base, services.downloads,
    )

    /**
     * Legacy single-chapter API. It can read downloaded/cache content without a chapter-list service.
     * It deliberately cannot navigate: [navigationAvailable] advertises that capability and NEXT fails explicitly.
     * Production injection uses the full constructor above; no fabricated preferences or download membership.
     */
    constructor(
        getChapterContent: GetChapterContent,
        downloadProvider: DownloadProvider,
        getChapter: GetChapter,
        getManga: GetManga,
        sourceManager: SourceManager,
        getReadingProgress: GetReadingProgress,
        saveReadingProgress: SaveReadingProgress,
        updateChapter: UpdateChapter,
        upsertHistory: UpsertHistory,
        trackChapter: TrackChapter,
        trackPreferences: TrackPreferences,
        getIncognitoState: GetIncognitoState,
        context: Context,
        appScope: CoroutineScope,
    ) : this(
        getChapterContent, downloadProvider, getChapter, getManga, sourceManager,
        getReadingProgress, saveReadingProgress, updateChapter, upsertHistory,
        trackChapter, trackPreferences, getIncognitoState, context, appScope,
        null, null, null, null,
    )

    private class NavigationServices(
        val chapters: GetChaptersByMangaId,
        val reader: ReaderPreferences,
        val base: BasePreferences,
        val downloads: DownloadManager,
    )

    private val navigationServices = run {
        val supplied = listOf(getChaptersByMangaId, readerPreferences, basePreferences, downloadManager)
        require(supplied.all { it == null } || supplied.all { it != null }) {
            "Novel navigation requires all four services; partial wiring is not supported"
        }
        if (getChaptersByMangaId == null) {
            null // Explicit single-chapter capability, never a substitute navigation policy.
        } else {
            NavigationServices(
                getChaptersByMangaId, requireNotNull(readerPreferences),
                requireNotNull(basePreferences), requireNotNull(downloadManager),
            )
        }
    }

    internal val navigationAvailable: Boolean = navigationServices != null

    private val _activeChapterId = MutableStateFlow<Long?>(null)
    internal val activeChapterId: StateFlow<Long?> = _activeChapterId.asStateFlow()
    private val _navigation = MutableStateFlow<NovelChapterNavigation?>(null)
    internal val navigation: StateFlow<NovelChapterNavigation?> = _navigation.asStateFlow()
    private val _isTransitioning = MutableStateFlow(false)
    internal val isTransitioning: StateFlow<Boolean> = _isTransitioning.asStateFlow()
    private val _nextChapterError = MutableStateFlow<NovelChapterError?>(null)
    internal val nextChapterError: StateFlow<NovelChapterError?> = _nextChapterError.asStateFlow()

    private val _previousChapterError = MutableStateFlow<NovelChapterError?>(null)
    internal val previousChapterError: StateFlow<NovelChapterError?> = _previousChapterError.asStateFlow()

    /** Both directions share C5's save-before-load and stale-publication protection. */
    fun openNextChapter(position: ReadingPosition) = openAdjacentChapter(position, previous = false)

    fun openPreviousChapter(position: ReadingPosition) = openAdjacentChapter(position, previous = true)

    private fun openAdjacentChapter(position: ReadingPosition, previous: Boolean) {
        check(navigationAvailable) { "Novel navigation is unavailable in the single-chapter constructor" }
        if (_isTransitioning.value || _isLoading.value) return
        val old = session ?: return
        val destination = (if (previous) _navigation.value?.previous else _navigation.value?.next) ?: return
        val transitionError = if (previous) _previousChapterError else _nextChapterError
        val request = ++loadRequest
        // Claim synchronously, before launch: two taps in the same frame still save/load only once.
        _isTransitioning.value = true
        _nextChapterError.value = null
        _previousChapterError.value = null
        viewModelScope.launch {
            try {
                persistProgress(old.id, position, old.blocks)
                val resolved = resolveChapter(destination.id, startAtTop = !previous && destination.read)
                if (request != loadRequest) return@launch
                if (resolved == null) {
                    transitionError.value = NovelChapterError(null)
                    return@launch
                }
                publish(resolved, request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to open adjacent novel chapter ${destination.id}" }
                if (request == loadRequest) transitionError.value = NovelChapterError(e.message)
            } finally {
                if (request == loadRequest) _isTransitioning.value = false
            }
        }
    }

    private val _titles = MutableStateFlow(NovelReaderTitles())

    /** Metadata for the published content; resolving it never adds a chapter or work lookup. */
    val titles: StateFlow<NovelReaderTitles> = _titles.asStateFlow()

    private val _content = MutableStateFlow<ChapterContent?>(null)

    /** Null until the chapter resolves, and null again if loading fails — [error] is what tells the two apart. */
    val content: StateFlow<ChapterContent?> = _content.asStateFlow()

    private val _error = MutableStateFlow<NovelChapterError?>(null)

    /** The failure to show instead of the reader, or null while a chapter is still being resolved. */
    val error: StateFlow<NovelChapterError?> = _error.asStateFlow()

    private val _position = MutableStateFlow(ReadingPosition.START)

    /** Latest accepted logical position, also used when the retained session reattaches after rotation. */
    val position: StateFlow<ReadingPosition> = _position.asStateFlow()

    private val _isLoading = MutableStateFlow(false)

    /**
     * True only while [load] is in flight.
     *
     * Neither a chapter that failed nor a chapter nobody has read yet publishes content, so this is what tells
     * those two apart; [error] is in turn what tells a failure apart from a load that has not started.
     */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Per-open lifecycle: a suspended OLD completion must never reset NEXT's history or completion guard. */
    private class ChapterSession(val id: Long, val blocks: Int) {
        var readStart: Instant? = Clock.System.now()
        var historyWritten = false
        var completionClaimed = false
    }

    private var session: ChapterSession? = null
    private var loadRequest = 0L
    private var routeChapterId: Long? = null
    private val progressWrites = Mutex()

    private data class ResolvedChapter(
        val id: Long,
        val content: ChapterContent,
        val titles: NovelReaderTitles,
        val position: ReadingPosition,
        val navigation: NovelChapterNavigation?,
    )

    /**
     * Resolves the content and the saved position for [chapterId].
     *
     * The position is resolved *before* the content is published, and that order is the contract the reader is built
     * on: `NovelReaderScreen` restores as soon as it is handed content and cannot restore again until the content
     * identity changes. Publishing content first therefore opens the chapter at the top even when a position was
     * saved — and the position the reader then reports from that top gets persisted straight back over the saved
     * one, so the chapter opens at the top from then on.
     *
     * A failure publishes [error] and is logged rather than thrown: the reader shows it and offers to try again,
     * while a throw here would take the whole screen down for a recoverable miss. Clearing the error first is what
     * makes this one entry point serve as the retry too — the reader goes back to its loading state on the same tap.
     *
     * A chapter with no content — no answer at all, or an answer with nothing in it — returns before any of the
     * state below is touched, because all of it describes a chapter that is in hand. History in particular has to
     * mean something the reader reached, and a chapter whose text never loaded was not reached; writing it would put
     * an unread chapter on the History screen, which is the mirror image of the bug this same rule fixed earlier —
     * history that claims a read nobody did.
     */
    /** Attach an Activity route once; navigation does not turn a reattachment into a fresh open. */
    fun attach(chapterId: Long) {
        if (routeChapterId == chapterId) return
        routeChapterId = chapterId
        load(chapterId)
    }

    fun load(chapterId: Long) {
        val request = ++loadRequest
        _error.value = null
        _nextChapterError.value = null
        _previousChapterError.value = null
        _isTransitioning.value = false
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val resolved = resolveChapter(chapterId)
                if (request != loadRequest) return@launch
                if (resolved == null) {
                    _error.value = NovelChapterError(null)
                    return@launch
                }
                publish(resolved, request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to load novel chapter $chapterId" }
                if (request == loadRequest) {
                    session = null
                    _position.value = ReadingPosition.START
                    _content.value = null
                    _titles.value = NovelReaderTitles()
                    _activeChapterId.value = null
                    _navigation.value = null
                    _error.value = NovelChapterError(e.message)
                }
            } finally {
                if (request == loadRequest) _isLoading.value = false
            }
        }
    }

    /** All suspending reads finish in local state, before any published chapter field changes. */
    private suspend fun resolveChapter(chapterId: Long, startAtTop: Boolean = false): ResolvedChapter? {
        var metadata: Pair<Chapter, Manga>? = null
        val loaded = resolveContent(chapterId) { chapter, manga -> metadata = chapter to manga }
        if (loaded == null || loaded.blocks.isEmpty()) return null
        val restored = if (startAtTop) ReadingPosition.START else getReadingProgress.await(chapterId).toReadingPosition()
        val titles = metadata?.let { (chapter, manga) -> NovelReaderTitles(manga.title, chapter.name) }
            ?: NovelReaderTitles()
        val navigation = navigationServices?.let { services ->
            val (chapter, manga) = checkNotNull(metadata) { "Novel navigation requires chapter and work metadata" }
            val chapters = services.chapters.await(manga.id, applyScanlatorFilter = true)
            // The policy owns anchor restoration; membership here describes only possible destinations.
            val downloaded = if (manga.source == LocalSource.ID) {
                chapters.map { it.id }.toSet()
            } else {
                services.downloads.getDownloadedChapterIds(chapters, manga)
            }
            resolveNovelChapterNavigation(
                manga, chapters, chapter,
                NovelNavigationPreferences(
                    skipRead = services.reader.skipRead.get(),
                    skipFiltered = services.reader.skipFiltered.get(),
                    skipDuplicates = services.reader.skipDupe.get(),
                    downloadedOnly = services.base.downloadedOnly.get(),
                ),
                downloaded,
            )
        }
        return ResolvedChapter(chapterId, loaded, titles, restored, navigation)
    }

    /** Content is the commit signal: its observers already see NEXT's position, ID, metadata and navigation. */
    private suspend fun publish(resolved: ResolvedChapter, request: Long) {
        val opened = ChapterSession(resolved.id, resolved.content.blocks.size)
        // Same lifecycle: opening writes History, never the read flag or tracker. Finish even this write before
        // committing NEXT, so a storage exception leaves every published OLD field and its lifecycle intact.
        recordHistory(opened)
        if (request != loadRequest) return
        session = opened
        _position.value = resolved.position
        _titles.value = resolved.titles
        _navigation.value = resolved.navigation
        _activeChapterId.value = resolved.id
        _content.value = resolved.content
    }

    /**
     * The content of [chapterId], asked of the downloaded file first, then the cache, then the source.
     *
     * The order is the whole of this fix, so it is written out rather than left to the shape of two collaborators:
     *
     * 1. **The downloaded file.** The user downloaded it. It is a file on disk that the downloader wrote and the
     *    index counts as a finished chapter, and the badge next to the chapter has been promising it ever since.
     *    Before this existed it was never opened — `Downloader` wrote it, `DownloadCache` indexed it, and
     *    `GetChapterContent` read `cache/` and then the network, so the `.txt` took part in nothing. A downloaded
     *    chapter was unreadable with the server down while its whole text sat next to it, and because the read
     *    chain fails honestly the only thing the user saw was what looked like an outage.
     * 2. **The content cache.** Still needed, and still first among what is left: a chapter read on a train and
     *    never downloaded has no file, and the cache is what answers for it. It also keeps a downloaded chapter
     *    cheap to open, because the file is already there and the cache is only consulted when it is not.
     * 3. **The source.** Last, as it always was: the only store that can answer for a chapter nobody has read and
     *    nobody has downloaded.
     *
     * **The file beats the cache, and that is deliberate.** Where they disagree, the file is what the user has on
     * disk and what the badge promised; the cache is a copy under `cache/`, which clearing the app cache removes
     * without deleting a single download. A cache that disagrees is the stale one whichever was written more
     * recently, because the downloader renders the file from the cache at download time and so the two start out
     * identical — the file only becomes the fresh one when something changed after that.
     *
     * `GetChapterContent` is deliberately not told about any of this. It stays the cache and the source, in
     * `:domain`, and `:domain` stays free of what a downloads folder is; the step in front of it is here, in `:app`,
     * where [DownloadProvider] and that concept already live.
     */
    private suspend fun resolveContent(
        chapterId: Long,
        onMetadata: (Chapter, Manga) -> Unit,
    ): ChapterContent? = downloadedContent(chapterId, onMetadata) ?: getChapterContent.await(chapterId)

    /**
     * The chapter's text out of its downloaded file, or null when there is none to read.
     *
     * Every miss here is silent on purpose. Nothing about a chapter that was never downloaded is wrong, and a
     * chapter whose file vanished is a chapter that has to be answered by the cache or the source — so this only
     * ever *adds* an answer, and never turns one into a failure.
     */
    private suspend fun downloadedContent(chapterId: Long, onMetadata: (Chapter, Manga) -> Unit): ChapterContent? {
        val chapter = getChapter.await(chapterId) ?: return null
        val manga = getManga.await(chapter.mangaId) ?: return null
        onMetadata(chapter, manga)
        val source = sourceManager.get(manga.source) ?: return null
        return downloadProvider.findDownloadedNovelContent(chapter, manga, source)
    }

    /**
     * Persists [position] without touching the chapter's read flag.
     *
     * Finishing a chapter and remembering where in it the reader stopped are different decisions, and the reader
     * emits both from the same scroll event, so they are kept apart here.
     *
     * The logical position is stored as given, fraction included, so reopening resumes on the same spot inside
     * the same paragraph. [ReadingProgress.blockOffsetPx] stays 0: a logical position has no pixels, and only the
     * Compose layer ever knows a block's measured height.
     */
    fun saveProgress(chapterId: Long, position: ReadingPosition) {
        if (!acceptsCallback(chapterId) || _isTransitioning.value) return
        val safe = position.normalized()
        val blocks = session?.blocks ?: 0
        _position.value = safe
        // Start in callback order and outlive Activity/ViewModel disposal. The final non-debounced
        // sample must not be cancelled by onCleared, nor overtaken by a later storage write.
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            persistProgress(chapterId, safe, blocks)
        }
    }

    private suspend fun persistProgress(chapterId: Long, position: ReadingPosition, blocks: Int) = progressWrites.withLock {
        val safe = position.normalized()
        logcat(LogPriority.INFO) { "[novel-progress] SAVE chapter=$chapterId $safe" }
        saveReadingProgress.await(
            chapterId,
            ReadingProgress(
                blockIndex = safe.blockIndex,
                blockOffsetPx = 0,
                blockFraction = safe.blockFraction,
                percent = percentOf(safe, blocks),
            ),
        )
    }

    // Only the explicit legacy capability retains standalone save/complete before its first publication.
    // Navigation callbacks belong to published content, never an unresolved destination or the original route.
    private fun acceptsCallback(chapterId: Long): Boolean =
        _activeChapterId.value?.let { it == chapterId } ?: !navigationAvailable

    /**
     * Marks the chapter read. Separate from [saveProgress] on purpose.
     *
     * In Mihon these are two different tables. `chapter.user_read` is what greys the chapter out in the library;
     * History is a separate screen backed by the `history` table, which only ever learns about a chapter through
     * [UpsertHistory]. Flipping the flag alone leaves a finished chapter invisible on that screen, which is why
     * this still calls [recordHistory] — though by the time a chapter reaches its end that row normally already
     * exists, written when the chapter was opened.
     *
     * Finishing it also tells the trackers, the same way the manga reader does, so a novel read moves the progress
     * on AniList or MyAnimeList instead of leaving it behind.
     */
    fun markRead(chapterId: Long) {
        if (!acceptsCallback(chapterId)) return
        viewModelScope.launch {
            if (!acceptsCallback(chapterId)) return@launch
            val completing = session ?: ChapterSession(chapterId, 0).also { session = it }
            if (completing.id != chapterId || completing.completionClaimed) return@launch
            // Claim before the first suspension. A delayed OLD update retains only OLD's lifecycle object.
            completing.completionClaimed = true
            updateChapter.await(ChapterUpdate(chapterId) { read = true })
            recordHistory(completing)

            // Outlives the reader, using the existing tracker preference/incognito/number guards.
            appScope.launch { updateTrackChapterRead(chapterId) }
        }
    }

    /**
     * Reports the finished chapter to the trackers, if any of them should hear about it.
     *
     * The guards are the manga reader's, in its order: the auto-update preference can turn this off; a chapter whose
     * number the source did not recognize has no number to report; and an incognito source was never meant to be
     * reported. The per-open completion claim prevents duplicate calls here; [TrackChapter] additionally skips a
     * chapter number the tracker already has when a chapter is finished in another session. A tracker failure is
     * logged and swallowed: it must never keep the chapter from being marked read.
     */
    private suspend fun updateTrackChapterRead(chapterId: Long) {
        if (!trackPreferences.autoUpdateTrack.get()) return

        val chapter = getChapter.await(chapterId) ?: return
        // -1 is what a source that publishes no chapter numbers reports, and there is nothing to send.
        if (chapter.chapterNumber < 0) return

        val manga = getManga.await(chapter.mangaId) ?: return
        if (getIncognitoState.await(manga.source)) return

        runCatching { trackChapter.await(context, manga.id, chapter.chapterNumber) }
            .onFailure { logcat(LogPriority.WARN, it) { "Failed to report chapter $chapterId to the trackers" } }
    }

    /**
     * Writes the History row for [opened], at most once per load.
     *
     * Separate from [markRead] because the flag and the history row have different triggers: the flag belongs to
     * reaching the end, while the row belongs to the chapter having been opened. Folding the two together would make
     * the read flag depend on history timing, and [markRead] would then have to return early on the write guard and
     * skip the flag with it.
     */
    private suspend fun recordHistory(opened: ChapterSession) {
        if (opened.historyWritten) return

        val endTime = Clock.System.now()
        val sessionReadDuration = opened.readStart?.let { (endTime - it).inWholeMilliseconds } ?: 0
        opened.readStart = null
        opened.historyWritten = true

        upsertHistory.await(HistoryUpdate(opened.id, endTime, sessionReadDuration))
    }

    /**
     * How far through the chapter [position] is, from 0 to 1.
     *
     * The block fraction counts too, so the end position the reader reports — the last block at full scroll —
     * lands on exactly 1. A percentage derived from the block index alone could never reach 100%.
     */
    private fun percentOf(position: ReadingPosition, blocks: Int): Float {
        if (blocks <= 0) return 0f
        return ((position.blockIndex + position.blockFraction) / blocks).coerceIn(0f, 1f)
    }

    /** A chapter with no saved progress starts at the beginning rather than at an arbitrary offset. */
    private fun ReadingProgress?.toReadingPosition(): ReadingPosition =
        this?.let { ReadingPosition(blockIndex = it.blockIndex, blockFraction = it.blockFraction) }?.normalized()
            ?: ReadingPosition.START
}
