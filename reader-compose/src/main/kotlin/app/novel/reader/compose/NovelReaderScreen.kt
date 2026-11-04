package app.novel.reader.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.novel.reader.core.BlockLayout
import app.novel.reader.core.ReaderSettings
import app.novel.reader.core.ReaderTheme
import app.novel.reader.core.ReadingPosition
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
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

/** The error state's breathing room, matching what the app's own empty screens use. */
private val ERROR_HORIZONTAL_PADDING = 24.dp

/** Gap between the message, the reason that may follow it and the retry action. */
private val ERROR_SPACING = 16.dp

/**
 * How much of the reader's own text colour the reason keeps.
 *
 * Dimmed because it is diagnostic detail under the message rather than the message itself, and a reason in full
 * strength reads as a second, competing error.
 */
private const val ERROR_REASON_ALPHA = 0.7f

/**
 * The reader's three states, as one decided answer.
 *
 * Split out from the composable because this is the distinction that was missing: a chapter that failed and a chapter
 * still being opened used to arrive as the same null content, and the reader drew a spinner for either. Carrying the
 * content rather than a bare flag is what lets the composable use it without asserting that a null is not there.
 */
internal sealed interface ReaderSurfaceState {

    /** A chapter to read, and the chapter it is. Never one with no blocks: there is nothing there to read. */
    data class Content(val content: ChapterContent) : ReaderSurfaceState

    /** A chapter that could not be opened. */
    data object Error : ReaderSurfaceState

    /** A chapter the caller has not published anything for yet. */
    data object Loading : ReaderSurfaceState
}

/**
 * Which state a composition with [content] and a failure is in.
 *
 * Content outranks the failure: content is what the reader can actually use, so an error that outlived it must never
 * cover the chapter.
 *
 * A chapter with no blocks is not content, though it is not null either. `ChapterBody` draws one item per block, so
 * publishing one renders an empty `LazyColumn` — the black page a reader cannot tell apart from a crash, and one
 * that never resolves because the store that answered with it keeps answering with it. It belongs in the error
 * state, which is what a chapter nobody can read is; deciding it here rather than upstream is what makes the
 * guarantee hold for every caller instead of depending on each one to have checked.
 */
internal fun readerSurfaceStateFor(content: ChapterContent?, hasError: Boolean): ReaderSurfaceState = when {
    content == null && hasError -> ReaderSurfaceState.Error
    content != null && content.blocks.isEmpty() -> ReaderSurfaceState.Error
    content != null -> ReaderSurfaceState.Content(content)
    else -> ReaderSurfaceState.Loading
}

/** Localized presentation only; navigation policy and chapter identities belong to the host. */
data class ReaderChapterFooter(
    val endLabel: String,
    val message: String? = null,
    val nextLabel: String? = null,
    val errorMessage: String? = null,
    val errorReason: String? = null,
    val returnLabel: String = "",
    val isPending: Boolean = false,
    val previousLabel: String? = null,
    val errorOnPrevious: Boolean = false,
)

/**
 * The reader surface for a single chapter.
 *
 * A `LazyListState` only speaks pixels, but a reading position has to survive a font-size change, so the
 * position is restored and reported through [BlockLayout] instead of being stored as an offset. That conversion
 * needs the measured height of a block, which only layout knows, hence the two-pass restore.
 *
 * The error copy is passed in rather than read here because this module resolves no resources of its own: the caller
 * hands over the message, the reason when there is one and the label for the action.
 *
 * Upper-half taps toggle an overlay toolbar without resizing the document; scrolling and native text selection
 * keep their own gestures. Labels and actions come from the resource-owning host. Controls remain hidden until
 * an upper-half tap; no floating invoker covers the document. The overlay temporarily covers upper prose while
 * visible; hiding it reveals the same text without permanent padding. Sharing uses only the native selected text.
 *
 * @param workTitle the work's title, shown above the chapter title in the top overlay.
 * @param chapterTitle the loaded chapter's title. Both titles ellipsize rather than widening the toolbar.
 * @param initialPosition where to start reading, already expressed as a logical position.
 * @param errorMessage what to tell the reader when the chapter could not be opened. Non-null is what makes a null
 *   content the error state rather than the loading one; a chapter with no blocks is the error state either way.
 * @param errorReason why it failed, when the caller knows. Null shows the message alone.
 * @param retryLabel the action's label.
 * @param onRetry asked for when the reader taps retry. Expected to load the same chapter again, which is what
 *   turns a reader who was offline back into one who is reading.
 * @param chapterFooter optional scrolling end matter, excluded from selection and content progress.
 * @param onNextChapter explicit next/retry action with a synchronous, un-debounced content position sample.
 * @param onReturnToChapter dismisses a next-chapter error without reloading the current document.
 * @param onProgress called with the logical position as the reader moves. When the reader reaches the bottom of
 *   the final block the reported position is the end of the content, which is how the caller learns the chapter
 *   was finished.
 */
@Composable
fun NovelReaderScreen(
    initialContent: ChapterContent?,
    initialPosition: ReadingPosition,
    settings: ReaderSettings,
    errorMessage: String?,
    errorReason: String?,
    retryLabel: String,
    onRetry: () -> Unit,
    onProgress: (ReadingPosition) -> Unit,
    modifier: Modifier = Modifier,
    backLabel: String = "",
    settingsLabel: String = "",
    shareLabel: String = "",
    controlsLabel: String = "",
    onBack: () -> Unit = {},
    onSettings: () -> Unit = {},
    workTitle: String = "",
    chapterTitle: String = "",
    onShareSelectedText: (String) -> Unit = {},
    // Optional layout observation for instrumentation; normal callers install no listeners.
    onDiagnosticListState: ((LazyListState) -> Unit)? = null,
    onDiagnosticParagraphPositioned: ((Int, ContentBlock.Paragraph, LayoutCoordinates) -> Unit)? = null,
    chapterFooter: ReaderChapterFooter? = null,
    onNextChapter: (ReadingPosition) -> Unit = {},
    onReturnToChapter: () -> Unit = {},
    onPreviousChapter: (ReadingPosition) -> Unit = {},
) {
    var controlsVisible by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    if (onDiagnosticListState != null) {
        SideEffect { onDiagnosticListState(listState) }
    }
    val latestOnProgress by rememberUpdatedState(onProgress)
    val effectiveSettings = settings.normalized()

    // Decided once and bound, so the content branch gets the chapter itself rather than a null it has to
    // re-assert, and so the effects below can ask the same question the drawing does.
    val surface = readerSurfaceStateFor(initialContent, hasError = errorMessage != null)

    // The chapter there is to read, or null when there is nothing to read. The effects below work from this rather
    // than from `initialContent`, because an empty `LazyColumn` still reports a position: with no blocks to measure,
    // `progressSample` answers block 0, which the caller would then persist over whatever spot the reader had
    // actually reached. A chapter nobody can read must not move anyone's place.
    val readable = (surface as? ReaderSurfaceState.Content)?.content

    // The logical position currently on screen. Tracked so a font or theme change can re-assert it: after a
    // reflow the list keeps its pixel offset, which now points at different text.
    var currentPosition by remember { mutableStateOf(initialPosition.normalized()) }

    // Nothing may be reported to the caller until the initial position has been applied, otherwise opening a
    // chapter at block 5 would immediately overwrite the saved position with block 0.
    var positionRestored by remember { mutableStateOf(false) }

    LaunchedEffect(listState, initialContent) {
        val content = readable ?: return@LaunchedEffect
        val target = initialPosition.normalized()
        listState.restoreReadingPosition(target, content.blocks.lastIndex)
        currentPosition = target
        positionRestored = true
    }

    DisposableEffect(listState, readable) {
        onDispose {
            // Rotation/back can destroy the surface before the 200 ms debounce settles. Sample the
            // real list synchronously; the host stamps this callback with the OLD publication ID.
            if (positionRestored && readable != null) {
                val sample = listState.progressSample(readable.blocks.lastIndex)
                latestOnProgress(
                    if (sample.atEnd) ReadingPosition(readable.blocks.lastIndex, 1f) else sample.position,
                )
            }
        }
    }

    // A font size or theme change reflows every block, so the pixel offset the list is holding now refers to
    // different text. Re-applying the logical position is what keeps the reader on the same paragraph.
    var settingsChangeCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(listState, effectiveSettings) {
        val content = readable ?: return@LaunchedEffect
        // The first emission is the initial composition, which the restore above already handled.
        if (settingsChangeCount++ == 0) return@LaunchedEffect
        listState.restoreReadingPosition(currentPosition, content.blocks.lastIndex)
    }

    LaunchedEffect(listState, initialContent, positionRestored) {
        val content = readable ?: return@LaunchedEffect
        if (!positionRestored) return@LaunchedEffect
        val lastIndex = content.blocks.lastIndex
        val endOfContent = ReadingPosition(lastIndex, 1f)

        snapshotFlow { listState.progressSample(lastIndex) }
            .distinctUntilChanged()
            .map { sample ->
                // Reflow uses the live logical position, not the last settled persistence callback.
                currentPosition = sample.position
                sample
            }
            .debounce(PROGRESS_DEBOUNCE_MS)
            .collect { sample ->
                latestOnProgress(if (sample.atEnd) endOfContent else sample.position)
            }
    }

    val toolbarAlpha by animateFloatAsState(
        targetValue = if (controlsVisible) 1f else 0f,
        animationSpec = readerToolbarAnimation,
        label = "reader toolbar",
    )
    when (surface) {
        is ReaderSurfaceState.Content -> Box(
            modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
        ) {
            ChapterBody(
                content = surface.content,
                settings = effectiveSettings,
                listState = listState,
                contentPadding = NO_CONTENT_PADDING,
                shareLabel = shareLabel,
                onShareSelectedText = onShareSelectedText,
                onDiagnosticParagraphPositioned = onDiagnosticParagraphPositioned,
                modifier = Modifier.fillMaxSize(),
                onReaderTap = { y, height ->
                    if (isReaderControlsTap(y, height)) controlsVisible = !controlsVisible
                },
                footer = chapterFooter?.let { footer ->
                    @Composable {
                        ChapterFooter(
                            footer = footer,
                            retryLabel = retryLabel,
                            theme = effectiveSettings.theme,
                            positionRestored = positionRestored,
                            onNext = {
                                if (positionRestored && !footer.isPending) {
                                    // Sample in the click itself: a fling may not have reached the debounced
                                    // progress callback. The footer's height/offset never becomes a position.
                                    val lastIndex = surface.content.blocks.lastIndex
                                    val sample = listState.progressSample(lastIndex)
                                    onNextChapter(
                                        if (sample.atEnd) ReadingPosition(lastIndex, 1f) else sample.position,
                                    )
                                }
                            },
                            onPrevious = {
                                if (positionRestored && !footer.isPending) {
                                    val sample = listState.progressSample(surface.content.blocks.lastIndex)
                                    onPreviousChapter(
                                        if (sample.atEnd) {
                                            ReadingPosition(
                                                surface.content.blocks.lastIndex,
                                                1f,
                                            )
                                        } else {
                                            sample.position
                                        },
                                    )
                                }
                            },
                            onReturn = onReturnToChapter,
                        )
                    }
                },
            )
            if (controlsVisible || toolbarAlpha > 0f) {
                ReaderToolbar(
                    theme = effectiveSettings.theme,
                    controlsLabel = controlsLabel,
                    backLabel = backLabel,
                    settingsLabel = settingsLabel,
                    workTitle = workTitle,
                    chapterTitle = chapterTitle,
                    onBack = onBack,
                    onSettings = onSettings,
                    modifier = Modifier.align(Alignment.TopCenter).graphicsLayer {
                        alpha = toolbarAlpha
                        translationY = -size.height * (1f - toolbarAlpha)
                    },
                )
            }
        }

        ReaderSurfaceState.Error -> ReaderErrorState(
            message = errorMessage.orEmpty(),
            reason = errorReason,
            retryLabel = retryLabel,
            onRetry = onRetry,
            theme = effectiveSettings.theme,
            modifier = modifier,
        )

        ReaderSurfaceState.Loading -> LoadingPlaceholder(theme = effectiveSettings.theme, modifier = modifier)
    }
}

/** The midpoint belongs to the lower half; coordinates outside a measured viewport are not taps. */
internal fun isReaderControlsTap(y: Float, height: Int): Boolean =
    height > 0 && y >= 0f && y < height / 2f

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

/** Measured list geometry, kept independent of Compose for the production progress calculation. */
internal data class ReaderVisibleItem(val index: Int, val offset: Int, val size: Int)

/** A reading position sampled from the current scroll state. */
internal data class ProgressSample(
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
private fun LazyListState.progressSample(lastIndex: Int): ProgressSample = sampleReaderProgress(
    firstVisibleItemIndex = firstVisibleItemIndex,
    firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
    visible = layoutInfo.visibleItemsInfo.map { ReaderVisibleItem(it.index, it.offset, it.size) },
    viewportEndOffset = layoutInfo.viewportEndOffset,
    lastIndex = lastIndex,
)

/** Content-only sampling: a footer may be visible, but is never a block or a completion oracle. */
internal fun sampleReaderProgress(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    visible: List<ReaderVisibleItem>,
    viewportEndOffset: Int,
    lastIndex: Int,
): ProgressSample {
    if (lastIndex < 0) return ProgressSample(ReadingPosition.START, atEnd = false)
    // When only end matter remains, every real block has already passed the viewport.
    // Do not measure the footer or convert its fractional scroll offset into content progress.
    if (firstVisibleItemIndex > lastIndex) return ProgressSample(ReadingPosition(lastIndex, 1f), atEnd = true)
    val index = firstVisibleItemIndex.coerceIn(0, lastIndex)
    val measuredHeight = visible.firstOrNull { it.index == index }?.size ?: 0
    val position = BlockLayout.toReadingPosition(index, firstVisibleItemScrollOffset, measuredHeight)
    val lastContent = visible.firstOrNull { it.index == lastIndex }
    val atEnd = lastContent != null && lastContent.hasReachedEndOf(viewportEndOffset)
    return ProgressSample(position = position, atEnd = atEnd)
}

/** The real trailing edge must be inside the viewport (allowing the existing eight-pixel tolerance). */
private fun ReaderVisibleItem.hasReachedEndOf(viewportEndOffset: Int): Boolean =
    offset.toLong() + size <= viewportEndOffset.toLong() + END_OF_CONTENT_TOLERANCE_PX

/** Drawn as the last LazyColumn item, never as a bottom bar or a second selection owner. */
@Composable
private fun ChapterFooter(
    footer: ReaderChapterFooter,
    retryLabel: String,
    theme: ReaderTheme,
    positionRestored: Boolean,
    onNext: () -> Unit,
    onReturn: () -> Unit,
    onPrevious: () -> Unit,
) {
    val colors = ReaderColorScheme.colorsFor(theme)
    Column(
        modifier = Modifier.fillMaxWidth().padding(ERROR_HORIZONTAL_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(footer.endLabel, color = colors.text, textAlign = TextAlign.Center)
        val message = footer.errorMessage ?: footer.message
        if (message != null) {
            Text(
                message,
                modifier = Modifier.padding(top = ERROR_SPACING),
                color = colors.text,
                textAlign = TextAlign.Center,
            )
        }
        if (footer.errorMessage != null && footer.errorReason != null) {
            Text(
                footer.errorReason,
                modifier = Modifier.padding(top = ERROR_SPACING),
                color = colors.text.copy(alpha = ERROR_REASON_ALPHA),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        if (footer.previousLabel != null) {
            Button(
                onClick = onPrevious,
                enabled = positionRestored && !footer.isPending,
                modifier = Modifier.padding(top = ERROR_SPACING),
                colors = ButtonDefaults.buttonColors(containerColor = colors.text, contentColor = colors.background),
            ) {
                Text(if (footer.errorOnPrevious) retryLabel else footer.previousLabel)
            }
        }
        if (footer.nextLabel != null) {
            Button(
                onClick = onNext,
                enabled = positionRestored && !footer.isPending,
                modifier = Modifier.padding(top = ERROR_SPACING),
                colors = ButtonDefaults.buttonColors(containerColor = colors.text, contentColor = colors.background),
            ) {
                Text(if (footer.errorMessage != null && !footer.errorOnPrevious) retryLabel else footer.nextLabel)
            }
        }
        if (footer.errorMessage != null) {
            TextButton(onClick = onReturn, enabled = !footer.isPending) {
                Text(footer.returnLabel, color = colors.text)
            }
        }
    }
}

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

/**
 * What the reader shows when a chapter could not be opened: what went wrong, and a way to try again.
 *
 * The action is the point of this state, not the message. The case it exists for is a reader who was offline, is
 * online now and wants to keep reading — a message on its own would still leave them stuck, and the spinner this
 * replaced never resolved at all.
 *
 * Painted from [ReaderColors] rather than the Material scheme, for the same reason a chapter is: the reader follows
 * its own theme, and a sepia page carrying a Material-coloured button is a page that does not belong to it. The
 * action inverts the two reader colours, which is the only contrast this surface can guarantee in both directions.
 */
@Composable
private fun ReaderErrorState(
    message: String,
    reason: String?,
    retryLabel: String,
    onRetry: () -> Unit,
    theme: ReaderTheme,
    modifier: Modifier = Modifier,
) {
    val colors = ReaderColorScheme.colorsFor(theme)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            // The spinner needs no insets because a circle in the middle of the screen never reaches them; a message
            // and a button are tall enough to meet a gesture bar.
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = ERROR_HORIZONTAL_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Drawn only when there is one. An empty line where the message should be reads as a string that
            // failed to load — the same kind of lie this state exists to stop — and this surface can
            // reach the error state on a chapter with no blocks alone, before the caller has published
            // anything to say.
            if (message.isNotBlank()) {
                Text(
                    text = message,
                    color = colors.text,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }

            // The diagnostic detail under the message, and the only thing this state says that the message does not.
            // `GetChapterContent` names most misses in its log alone, so it is usually absent by design.
            if (reason != null) {
                Text(
                    text = reason,
                    color = colors.text.copy(alpha = ERROR_REASON_ALPHA),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = ERROR_SPACING),
                )
            }

            Button(
                onClick = onRetry,
                modifier = Modifier.padding(top = ERROR_SPACING),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.text,
                    contentColor = colors.background,
                ),
            ) {
                Text(text = retryLabel)
            }
        }
    }
}
