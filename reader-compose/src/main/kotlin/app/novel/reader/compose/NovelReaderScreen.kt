package app.novel.reader.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.novel.reader.core.BlockLayout
import app.novel.reader.core.ReaderSettings
import app.novel.reader.core.ReaderTheme
import app.novel.reader.core.ReadingPosition
import app.novel.source.api.ChapterContent
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/** How long the reader has to settle before the position is reported, so a fling does not flood the caller. */
private const val PROGRESS_DEBOUNCE_MS = 200L

/** The screen takes no insets of its own; the caller wraps it in whatever chrome the host provides. */
private val NO_CONTENT_PADDING = PaddingValues(0.dp)

/**
 * How long to wait for a block to be measured before giving up on the exact offset.
 *
 * Without a bound, a block that never lays out — because a saved index pointed past a shorter chapter, say —
 * would suspend forever. A short wait that falls back to the block start is better than a reader that hangs.
 */
private const val ITEM_MEASURE_TIMEOUT_MS = 1_000L

/** How close to the bottom of the last block counts as reaching it, in pixels. */
private const val END_OF_CONTENT_TOLERANCE_PX = 8

/**
 * The reader surface for a single chapter.
 *
 * A `LazyListState` only speaks pixels, but a reading position has to survive a font-size change, so the
 * position is restored and reported through [BlockLayout] instead of being stored as an offset. That conversion
 * needs the measured height of a block, which only layout knows, hence the two-pass restore.
 *
 * @param initialPosition where to start reading, already expressed as a logical position.
 * @param onProgress called with the logical position as the reader moves. When the reader reaches the bottom of
 *   the final block the reported position is the end of the content, which is how the caller learns the chapter
 *   was finished.
 */
@Composable
fun NovelReaderScreen(
    initialContent: ChapterContent?,
    initialPosition: ReadingPosition,
    settings: ReaderSettings,
    onProgress: (ReadingPosition) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val latestOnProgress by rememberUpdatedState(onProgress)
    val effectiveSettings = settings.normalized()

    // The logical position currently on screen. Tracked so a font or theme change can re-assert it: after a
    // reflow the list keeps its pixel offset, which now points at different text.
    var currentPosition by remember { mutableStateOf(initialPosition.normalized()) }

    // Nothing may be reported to the caller until the initial position has been applied, otherwise opening a
    // chapter at block 5 would immediately overwrite the saved position with block 0.
    var positionRestored by remember { mutableStateOf(false) }

    LaunchedEffect(listState, initialContent) {
        val content = initialContent ?: return@LaunchedEffect
        val target = initialPosition.normalized()
        listState.restoreReadingPosition(target, content.blocks.lastIndex)
        currentPosition = target
        positionRestored = true
    }

    // A font size or theme change reflows every block, so the pixel offset the list is holding now refers to
    // different text. Re-applying the logical position is what keeps the reader on the same paragraph.
    var settingsChangeCount = remember { 0 }
    LaunchedEffect(listState, effectiveSettings) {
        val content = initialContent ?: return@LaunchedEffect
        // The first emission is the initial composition, which the restore above already handled.
        if (settingsChangeCount++ == 0) return@LaunchedEffect
        listState.restoreReadingPosition(currentPosition, content.blocks.lastIndex)
    }

    LaunchedEffect(listState, initialContent, positionRestored) {
        val content = initialContent ?: return@LaunchedEffect
        if (!positionRestored) return@LaunchedEffect
        val lastIndex = content.blocks.lastIndex
        val endOfContent = if (lastIndex < 0) ReadingPosition.START else ReadingPosition(lastIndex, 1f)

        snapshotFlow { listState.progressSample(lastIndex) }
            .distinctUntilChanged()
            .debounce(PROGRESS_DEBOUNCE_MS)
            .collect { sample ->
                currentPosition = sample.position
                latestOnProgress(if (sample.atEnd) endOfContent else sample.position)
            }
    }

    val content = initialContent
    if (content != null) {
        ChapterBody(
            content = content,
            settings = effectiveSettings,
            listState = listState,
            contentPadding = NO_CONTENT_PADDING,
            modifier = modifier,
        )
    } else {
        LoadingPlaceholder(theme = effectiveSettings.theme, modifier = modifier)
    }
}

/**
 * Moves the list to [position], which is expressed as a block index plus a fraction of that block.
 *
 * `LazyListState` cannot apply a fractional offset, so this is necessarily two passes: put the block at the top
 * of the viewport, wait for layout to measure it, then scroll by the pixel offset that fraction works out to.
 */
private suspend fun LazyListState.restoreReadingPosition(position: ReadingPosition, lastIndex: Int) {
    val target = position.normalized()
    // A position saved against a longer chapter must not scroll past the end of this one.
    val index = target.blockIndex.coerceIn(0, lastIndex.coerceAtLeast(0))

    scrollToItem(index, 0)

    // Until this returns there is no measured height to work with, so the second pass cannot happen yet.
    val measuredHeight = awaitItemHeight(index)
    scrollToItem(index, BlockLayout.toScrollOffset(target, measuredHeight))
}

/**
 * Waits for [index] to be laid out and returns its measured height in pixels.
 *
 * Returns 0 if the block is not measured in time, which makes [BlockLayout] fall back to the block start
 * instead of leaving the reader stuck.
 */
private suspend fun LazyListState.awaitItemHeight(index: Int): Int = withTimeoutOrNull(ITEM_MEASURE_TIMEOUT_MS) {
    snapshotFlow { layoutInfo.visibleItemsInfo }
        .map { visible -> visible.firstOrNull { it.index == index }?.size }
        .filterNotNull()
        .first()
} ?: 0

/** A reading position sampled from the current scroll state. */
private data class ProgressSample(
    val position: ReadingPosition,
    val atEnd: Boolean,
)

/**
 * Reads the current position as a logical one. `LazyListItemInfo.size` is the main-axis size, so on a vertical
 * list it is the block's measured height, which is exactly what [BlockLayout.toReadingPosition] needs.
 *
 * [atEnd] requires the viewport to have reached the bottom of the last block, not merely to have made it
 * visible. Treating "the final block is on screen" as "the chapter is finished" would mark a chapter read the
 * moment the reader scrolled to the top of a long closing paragraph.
 */
private fun LazyListState.progressSample(lastIndex: Int): ProgressSample {
    val visible = layoutInfo.visibleItemsInfo
    val index = firstVisibleItemIndex
    val measuredHeight = visible.firstOrNull { it.index == index }?.size ?: 0
    val position = BlockLayout.toReadingPosition(index, firstVisibleItemScrollOffset, measuredHeight)
    val lastItem = visible.lastOrNull()
    val atEnd = lastItem != null &&
        lastItem.index == lastIndex &&
        lastItem.hasReachedEndOf(layoutInfo.viewportEndOffset)
    return ProgressSample(position = position, atEnd = atEnd)
}

/** True when this item's trailing edge has reached (or passed) the bottom of the viewport. */
private fun LazyListItemInfo.hasReachedEndOf(viewportEndOffset: Int): Boolean =
    offset + size >= viewportEndOffset - END_OF_CONTENT_TOLERANCE_PX

@Composable
private fun LoadingPlaceholder(theme: ReaderTheme, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ReaderColorScheme.colorsFor(theme).background),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}
