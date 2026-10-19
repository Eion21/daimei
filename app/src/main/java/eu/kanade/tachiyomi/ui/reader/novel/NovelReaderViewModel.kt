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
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.download.DownloadProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.novel.interactor.GetChapterContent
import tachiyomi.domain.novel.interactor.GetReadingProgress
import tachiyomi.domain.novel.interactor.SaveReadingProgress
import tachiyomi.domain.novel.repository.ReadingProgress
import tachiyomi.domain.source.service.SourceManager
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
@Inject
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
) : ViewModel() {

    private val _content = MutableStateFlow<ChapterContent?>(null)

    /** Null until the chapter resolves, and null again if loading fails — [error] is what tells the two apart. */
    val content: StateFlow<ChapterContent?> = _content.asStateFlow()

    private val _error = MutableStateFlow<NovelChapterError?>(null)

    /** The failure to show instead of the reader, or null while a chapter is still being resolved. */
    val error: StateFlow<NovelChapterError?> = _error.asStateFlow()

    private val _position = MutableStateFlow(ReadingPosition.START)

    /** Where to open the chapter. Not updated while reading — that is what [saveProgress] persists. */
    val position: StateFlow<ReadingPosition> = _position.asStateFlow()

    private val _isLoading = MutableStateFlow(false)

    /**
     * True only while [load] is in flight.
     *
     * Neither a chapter that failed nor a chapter nobody has read yet publishes content, so this is what tells
     * those two apart; [error] is in turn what tells a failure apart from a load that has not started.
     */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Block count of the loaded chapter, kept so [saveProgress] can compute a percentage. */
    private var blockCount = 0

    /** When the current chapter was opened, so finishing it can report how long that took. */
    private var chapterReadStartTime: Instant? = null

    /**
     * The chapter whose history entry has already been written, or null.
     *
     * `history.upsert` adds to `read_duration` instead of replacing it, so the same session writing it twice would
     * double the time. The host reports reaching the end of a chapter on every debounced progress emission, which
     * makes a repeat the normal case rather than an edge one.
     */
    private var historyWrittenFor: Long? = null

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
    fun load(chapterId: Long) {
        _error.value = null
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val loaded = resolveContent(chapterId)
                if (loaded == null || loaded.blocks.isEmpty()) {
                    logcat(LogPriority.WARN) {
                        val blocks = loaded?.blocks?.size
                        if (blocks == null) {
                            "No novel content available for chapter $chapterId"
                        } else {
                            "Novel chapter $chapterId resolved to an empty chapter ($blocks blocks)"
                        }
                    }
                    // Published as a failure rather than left as a null content, which is the same value a load
                    // still in flight publishes — the reader would spin over a chapter it can never show. The return
                    // is what keeps a chapter nobody could read out of History; `finally` still ends the load, so
                    // this is the error state rather than a spinner over it.
                    //
                    // Zero blocks lands here too, and deliberately: `ChapterBody` draws one item per block, so a
                    // chapter with none is an empty LazyColumn — a black page that survives every later attempt
                    // because the content cache answers with it. Neither the source nor the cache can produce one
                    // any more, but a store written before either of them was fixed still can, and a reader stuck
                    // on a blank page has no way back. It returns before the assignment below, so an empty chapter
                    // is never published as something to read.
                    _error.value = NovelChapterError(reason = null)
                    return@launch
                }

                blockCount = loaded.blocks.size
                chapterReadStartTime = Clock.System.now()
                historyWrittenFor = null

                _position.value = getReadingProgress.await(chapterId).toReadingPosition()
                logcat(LogPriority.INFO) {
                    "[novel-progress] LOAD chapter=$chapterId blocks=${loaded.blocks.size} " +
                        "restored=$_position.value"
                }
                _content.value = loaded

                // Mihon writes history when the chapter opens, not when it is finished: `loadNewChapter` there calls
                // `updateHistory()` before it even starts the chapter, so a chapter the reader opened and backed out
                // of is still "recently read". Writing only at the end left anything short of the last paragraph
                // invisible on the History screen, which is what an opened-and-partly-read chapter is.
                recordHistory(chapterId)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to load novel chapter $chapterId" }
                blockCount = 0
                _position.value = ReadingPosition.START
                _content.value = null
                // The one failure that arrives with something to say, which is the same message the manga reader
                // puts under its own retry button.
                _error.value = NovelChapterError(reason = e.message)
            } finally {
                _isLoading.value = false
            }
        }
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
    private suspend fun resolveContent(chapterId: Long): ChapterContent? =
        downloadedContent(chapterId) ?: getChapterContent.await(chapterId)

    /**
     * The chapter's text out of its downloaded file, or null when there is none to read.
     *
     * Every miss here is silent on purpose. Nothing about a chapter that was never downloaded is wrong, and a
     * chapter whose file vanished is a chapter that has to be answered by the cache or the source — so this only
     * ever *adds* an answer, and never turns one into a failure.
     */
    private suspend fun downloadedContent(chapterId: Long): ChapterContent? {
        val chapter = getChapter.await(chapterId) ?: return null
        val manga = getManga.await(chapter.mangaId) ?: return null
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
        viewModelScope.launch {
            val safe = position.normalized()
            logcat(LogPriority.INFO) { "[novel-progress] SAVE chapter=$chapterId $safe" }
            saveReadingProgress.await(
                chapterId,
                ReadingProgress(
                    blockIndex = safe.blockIndex,
                    blockOffsetPx = 0,
                    blockFraction = safe.blockFraction,
                    percent = percentOf(safe),
                ),
            )
        }
    }

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
        viewModelScope.launch {
            updateChapter.await(ChapterUpdate(chapterId) { read = true })
            recordHistory(chapterId)

            // Outlives the reader on purpose, and off the main thread: reading the last page and leaving
            // right away is the common case. Same as the manga reader.
            appScope.launch { updateTrackChapterRead(chapterId) }
        }
    }

    /**
     * Reports the finished chapter to the trackers, if any of them should hear about it.
     *
     * The guards are the manga reader's, in its order: the auto-update preference can turn this off; a chapter whose
     * number the source did not recognize has no number to report; and an incognito source was never meant to be
     * reported. Telling a tracker twice is not decided here — [TrackChapter] skips a chapter number the tracker
     * already has, so a chapter finished again goes through the same call and is skipped there. A tracker failure is
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
     * Writes the History row for [chapterId], at most once per load.
     *
     * Separate from [markRead] because the flag and the history row have different triggers: the flag belongs to
     * reaching the end, while the row belongs to the chapter having been opened. Folding the two together would make
     * the read flag depend on history timing, and [markRead] would then have to return early on the write guard and
     * skip the flag with it.
     */
    private suspend fun recordHistory(chapterId: Long) {
        if (historyWrittenFor == chapterId) return

        val endTime = Clock.System.now()
        val sessionReadDuration = chapterReadStartTime?.let { (endTime - it).inWholeMilliseconds } ?: 0
        chapterReadStartTime = null
        historyWrittenFor = chapterId

        upsertHistory.await(HistoryUpdate(chapterId, endTime, sessionReadDuration))
    }

    /**
     * How far through the chapter [position] is, from 0 to 1.
     *
     * The block fraction counts too, so the end position the reader reports — the last block at full scroll —
     * lands on exactly 1. A percentage derived from the block index alone could never reach 100%.
     */
    private fun percentOf(position: ReadingPosition): Float {
        if (blockCount <= 0) return 0f
        return ((position.blockIndex + position.blockFraction) / blockCount).coerceIn(0f, 1f)
    }

    /** A chapter with no saved progress starts at the beginning rather than at an arbitrary offset. */
    private fun ReadingProgress?.toReadingPosition(): ReadingPosition =
        this?.let { ReadingPosition(blockIndex = it.blockIndex, blockFraction = it.blockFraction) }?.normalized()
            ?: ReadingPosition.START
}
