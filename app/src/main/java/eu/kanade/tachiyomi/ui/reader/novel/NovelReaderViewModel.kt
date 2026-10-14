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
import tachiyomi.domain.novel.interactor.GetChapterContent
import tachiyomi.domain.novel.interactor.GetReadingProgress
import tachiyomi.domain.novel.interactor.SaveReadingProgress
import tachiyomi.domain.novel.repository.ReadingProgress

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

    /**
     * Resolves the content and the saved position for [chapterId].
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
                    logcat(LogPriority.WARN) { "No novel content stored for chapter $chapterId" }
                }

                blockCount = loaded?.blocks?.size ?: 0
                _content.value = loaded
                _position.value = getReadingProgress.await(chapterId).toReadingPosition()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to load novel chapter $chapterId" }
                blockCount = 0
                _content.value = null
                _position.value = ReadingPosition.START
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

    /** Marks the chapter read. Separate from [saveProgress] on purpose. */
    fun markRead(chapterId: Long) {
        viewModelScope.launch {
            updateChapter.await(ChapterUpdate(chapterId) { read = true })
        }
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
