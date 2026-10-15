package eu.kanade.tachiyomi.ui.reader.novel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.novel.reader.compose.NovelReaderScreen
import app.novel.reader.core.ReaderSettings
import app.novel.reader.core.ReadingPosition
import dev.zacsweers.metrox.viewmodel.metroViewModel
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.util.collectAsState

/**
 * Binds [NovelReaderViewModel] to the novel reader surface.
 *
 * This is the whole reader: loading, the saved position, typography and progress persistence. Navigation is
 * deliberately not involved — the host decides which chapter to open and never pushes a new one.
 */
@Composable
fun NovelReaderHost(
    chapterId: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val preferences = remember { context.appGraph.novelReaderPreferences }
    val viewModel = metroViewModel<NovelReaderViewModel>()

    val content by viewModel.content.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle()

    // Each preference is collected on its own, the way the manga reader's settings pages do it: reading them
    // through `changes()` is what lets a font size change reflow the open chapter instead of waiting for a
    // restart.
    val fontFamily by preferences.fontFamily.collectAsState()
    val fontSizeSp by preferences.fontSizeSp.collectAsState()
    val lineHeightPercent by preferences.lineHeightPercent.collectAsState()
    val horizontalMarginDp by preferences.horizontalMarginDp.collectAsState()
    val maxTextWidthDp by preferences.maxTextWidthDp.collectAsState()
    val theme by preferences.theme.collectAsState()
    val followSystemTheme by preferences.followSystemTheme.collectAsState()

    val settings = remember(
        fontFamily,
        fontSizeSp,
        lineHeightPercent,
        horizontalMarginDp,
        maxTextWidthDp,
        theme,
        followSystemTheme,
    ) {
        ReaderSettings(
            fontFamily = fontFamily,
            fontSizeSp = fontSizeSp.toFloat(),
            lineHeightMultiplier = lineHeightPercent / PERCENT,
            horizontalMarginDp = horizontalMarginDp,
            maxTextWidthDp = maxTextWidthDp,
            theme = theme,
            followSystemTheme = followSystemTheme,
        ).normalized()
    }

    LaunchedEffect(viewModel, chapterId) {
        viewModel.load(chapterId)
    }

    // The reader reports the end of the content on its first settled frame when it opens a chapter that was
    // already finished, because restoring the saved end position puts it right there. Treating that report as
    // "the reader finished it" would mark a chapter read that nobody has read, so an end position only counts
    // once the reader has reported somewhere else first — which is what "the reader actually moved" looks like
    // from here.
    var hasMoved by remember(chapterId) { mutableStateOf(false) }
    val lastBlockIndex = content?.blocks?.lastIndex ?: -1

    val onProgress by rememberUpdatedState<(ReadingPosition) -> Unit>(
        { newPosition ->
            val atEnd = lastBlockIndex >= 0 &&
                newPosition.blockIndex == lastBlockIndex &&
                newPosition.blockFraction >= END_OF_CONTENT_FRACTION

            if (atEnd) {
                if (hasMoved) viewModel.markRead(chapterId)
            } else {
                hasMoved = true
            }

            viewModel.saveProgress(chapterId, newPosition)
        },
    )

    NovelReaderScreen(
        initialContent = content,
        initialPosition = position,
        settings = settings,
        // Resolved here because `reader-compose` holds no resources of its own. Keyed on the error so a preference
        // emission — this host recomposes on every one of them — does not re-read the resource for nothing.
        errorMessage = remember(error) {
            error?.let { context.stringResource(MR.strings.novel_chapter_load_error) }
        },
        errorReason = error?.reason,
        retryLabel = remember { context.stringResource(MR.strings.action_retry) },
        // Reloading the same chapter is the whole of the retry: the view model clears the error on the way in, so
        // the reader goes back to loading and then either shows the chapter or the failure again.
        onRetry = { viewModel.load(chapterId) },
        onProgress = onProgress,
        modifier = modifier,
    )
}

private const val PERCENT = 100f

/** The reader reports the end of the content as the last block scrolled to its full height. */
private const val END_OF_CONTENT_FRACTION = 1f
