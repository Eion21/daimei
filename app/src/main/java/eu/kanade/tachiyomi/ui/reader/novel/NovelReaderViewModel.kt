package eu.kanade.tachiyomi.ui.reader.novel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.novel.reader.core.ReadingPosition
import app.novel.source.api.ChapterContent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.novel.interactor.GetChapterContent
import tachiyomi.domain.novel.interactor.GetReadingProgress
import tachiyomi.domain.novel.interactor.SaveReadingProgress
import tachiyomi.domain.novel.repository.ReadingProgress
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * State for one novel chapter.
 *
 * Owns no Android view: the host composable collects this and hands it to
 * [app.novel.reader.compose.NovelReaderScreen].
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class NovelReaderViewModel(
    private val getChapterContent: GetChapterContent,
    private val getReadingProgress: GetReadingProgress,
    private val saveReadingProgress: SaveReadingProgress,
    private val updateChapter: UpdateChapter,
    private val upsertHistory: UpsertHistory,
) : ViewModel() {

    private val _content = MutableStateFlow<ChapterContent?>(null)

    /** Null until the chapter resolves, and null again if loading fails. */
    val content: StateFlow<ChapterContent?> = _content.asStateFlow()

    private val _position = MutableStateFlow(ReadingPosition.START)

    /** Where to open the chapter. Not updated while reading — that is what [saveProgress] persists. */
    val position: StateFlow<ReadingPosition> = _position.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
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
     * A failure leaves [content] null and is logged rather than thrown: the host renders the reader's loading
     * placeholder in that state, and a throw here would take the whole screen down for a recoverable miss.
     */
    fun load(chapterId: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val loaded = getChapterContent.await(chapterId)
                if (loaded == null) {
                    logcat(LogPriority.WARN) { "No novel content available for chapter $chapterId" }
                }

                blockCount = loaded?.blocks?.size ?: 0
                chapterReadStartTime = Clock.System.now()
                historyWrittenFor = null

                _position.value = getReadingProgress.await(chapterId).toReadingPosition()
                logcat(LogPriority.INFO) {
                    "[novel-progress] LOAD chapter=$chapterId blocks=${loaded?.blocks?.size} " +
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
            } finally {
                _isLoading.value = false
            }
        }
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
     */
    fun markRead(chapterId: Long) {
        viewModelScope.launch {
            updateChapter.await(ChapterUpdate(chapterId) { read = true })
            recordHistory(chapterId)
        }
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
