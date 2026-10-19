package eu.kanade.tachiyomi.ui.reader.novel

import android.content.Intent
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.novel.reader.compose.NovelReaderScreen
import app.novel.reader.core.FontFamily
import app.novel.reader.core.ReaderSettings
import app.novel.reader.core.ReaderTheme
import app.novel.reader.core.ReadingPosition
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScreen
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * Binds [NovelReaderViewModel] to the novel reader surface.
 *
 * Owns loading, the saved position, live reader preferences and progress persistence. The toolbar can return
 * to the caller, but never opens another chapter.
 */
@Composable
fun NovelReaderHost(
    chapterId: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    val systemDark = isSystemInDarkTheme()
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
        systemDark,
    ) {
        ReaderSettings(
            fontFamily = fontFamily,
            fontSizeSp = fontSizeSp.toFloat(),
            lineHeightMultiplier = lineHeightPercent / PERCENT,
            horizontalMarginDp = horizontalMarginDp,
            maxTextWidthDp = maxTextWidthDp,
            theme = if (followSystemTheme) {
                if (systemDark) ReaderTheme.DARK else ReaderTheme.LIGHT
            } else {
                theme
            },
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
        backLabel = stringResource(MR.strings.action_bar_up_description),
        settingsLabel = stringResource(MR.strings.action_settings),
        controlsLabel = stringResource(MR.strings.novel_reader_controls),
        shareLabel = stringResource(MR.strings.action_share),
        onBack = { backDispatcher?.onBackPressed() },
        onSettings = { settingsVisible = true },
        onShareSelectedText = { selectedText ->
            if (selectedText.isNotBlank()) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, selectedText)
                }
                context.startActivity(Intent.createChooser(send, context.stringResource(MR.strings.action_share)))
            }
        },
    )

    if (settingsVisible) {
        ModalBottomSheet(onDismissRequest = { settingsVisible = false }) {
            TextButton(onClick = { settingsVisible = false }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(MR.strings.action_close))
            }
            NovelReaderSettings(preferences, followSystemTheme)
        }
    }
}

/** Uses the existing preference widgets and keys, including their persistence and accessibility behavior. */
@Composable
private fun NovelReaderSettings(preferences: NovelReaderPreferences, followSystemTheme: Boolean) {
    PreferenceScreen(
        modifier = Modifier.fillMaxHeight(0.85f),
        items = listOf(
            Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.novel_reader_interactions)),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.fontFamily,
                title = stringResource(MR.strings.novel_reader_font),
                entries = mapOf(
                    FontFamily.SERIF to stringResource(MR.strings.novel_reader_serif),
                    FontFamily.SANS_SERIF to stringResource(MR.strings.novel_reader_sans_serif),
                    FontFamily.MONOSPACE to stringResource(MR.strings.novel_reader_monospace),
                ),
            ),
            Preference.PreferenceItem.SliderPreference(
                preference = preferences.fontSizeSp,
                title = stringResource(MR.strings.novel_reader_font_size),
                valueRange = ReaderSettings.MIN_FONT_SIZE_SP.toInt()..ReaderSettings.MAX_FONT_SIZE_SP.toInt(),
            ),
            Preference.PreferenceItem.SliderPreference(
                preference = preferences.lineHeightPercent,
                title = stringResource(MR.strings.novel_reader_line_height),
                valueRange = (ReaderSettings.MIN_LINE_HEIGHT * PERCENT).toInt()..
                    (ReaderSettings.MAX_LINE_HEIGHT * PERCENT).toInt() step 10,
                valueText = { "$it%" },
            ),
            Preference.PreferenceItem.SliderPreference(
                preference = preferences.horizontalMarginDp,
                title = stringResource(MR.strings.novel_reader_margin),
                valueRange = ReaderSettings.MIN_MARGIN_DP..ReaderSettings.MAX_MARGIN_DP,
            ),
            Preference.PreferenceItem.SliderPreference(
                preference = preferences.maxTextWidthDp,
                title = stringResource(MR.strings.novel_reader_width),
                valueRange = ReaderSettings.MIN_TEXT_WIDTH_DP..ReaderSettings.MAX_TEXT_WIDTH_DP step 20,
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.followSystemTheme,
                title = stringResource(MR.strings.theme_system),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.theme,
                title = stringResource(MR.strings.pref_category_theme),
                visible = !followSystemTheme,
                entries = mapOf(
                    ReaderTheme.LIGHT to stringResource(MR.strings.theme_light),
                    ReaderTheme.DARK to stringResource(MR.strings.theme_dark),
                    ReaderTheme.SEPIA to stringResource(MR.strings.novel_reader_sepia),
                ),
            ),
        ),
    )
}

private const val PERCENT = 100f

/** The reader reports the end of the content as the last block scrolled to its full height. */
private const val END_OF_CONTENT_FRACTION = 1f
