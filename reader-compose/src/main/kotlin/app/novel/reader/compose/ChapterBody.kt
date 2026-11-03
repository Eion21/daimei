package app.novel.reader.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.novel.reader.core.ReaderSettings
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import coil3.compose.AsyncImage
import androidx.compose.ui.text.font.FontFamily as ComposeFontFamily
import app.novel.reader.core.FontFamily as ReaderFontFamily

/** Opacity used for [ContentBlock.Divider], derived from the reader text colour. */
private const val DIVIDER_ALPHA = 0.15f

/**
 * Gap between content blocks.
 *
 * Set on the list rather than inside the blocks: it then sits between blocks only, instead of adding a second
 * gap to the edges that `contentPadding` already provides.
 */
private val BLOCK_SPACING = 16.dp

/**
 * Renders a whole chapter as one continuous scrollable document.
 *
 * [listState] is owned by the caller rather than created here, because the logical reading position has to be
 * restored and reported from outside this composable. See `NovelReaderScreen`.
 *
 * Line length is capped at [ReaderSettings.maxTextWidthDp] and centred: a novel paragraph stretched across a
 * tablet is unreadable, no matter how large the window is.
 *
 * [footer] is optional end matter in this same list, outside native text selection. Its buttons consume their
 * own clicks through the normal child clickable handling, so an upper-half footer action is not a reader tap.
 */
@Composable
fun ChapterBody(
    content: ChapterContent,
    settings: ReaderSettings,
    listState: LazyListState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    shareLabel: String = "",
    onReaderTap: (y: Float, height: Int) -> Unit = { _, _ -> },
    onShareSelectedText: (String) -> Unit = {},
    onDiagnosticParagraphPositioned: ((Int, ContentBlock.Paragraph, LayoutCoordinates) -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    // Clamping lives in reader-core, so the UI cannot render an unreadable chapter from a corrupt setting.
    val effectiveSettings = settings.normalized()
    val colors = ReaderColorScheme.colorsFor(effectiveSettings.theme)

    val paragraphStyle = TextStyle(
        fontFamily = effectiveSettings.fontFamily.toComposeFontFamily(),
        fontSize = effectiveSettings.fontSizeSp.sp,
        lineHeight = (effectiveSettings.fontSizeSp * effectiveSettings.lineHeightMultiplier).sp,
        color = colors.text,
    )
    val dividerColor = colors.text.copy(alpha = DIVIDER_ALPHA)

    // One selection owner lets a drag cross paragraph and heading boundaries without changing list state.
    val selection = rememberSelectionState()
    var selectionActiveAtDown by remember { mutableStateOf(false) }
    val latestOnReaderTap by rememberUpdatedState(onReaderTap)
    val tapModifier = modifier
        .pointerInput(selection) {
            // Observe before native selection handles DOWN; never consume or replace its gestures.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                selectionActiveAtDown = selection.selectedTexts.any { it.isNotEmpty() }
            }
        }
        .pointerInput(selection) {
            detectTapGestures { offset ->
                // Native selection may already be cleared by UP. The DOWN snapshot owns this tap.
                if (selectionActiveAtDown) selection.clear() else latestOnReaderTap(offset.y, size.height)
            }
        }
    SelectionContainer(
        state = selection,
        modifier = tapModifier.appendTextContextMenuComponents {
            if (selection.selectedTexts.any { it.isNotEmpty() } && shareLabel.isNotEmpty()) {
                item(key = ShareSelection, label = shareLabel) {
                    onShareSelectedText(selection.selectedTexts.joinToString("\n") { it.text })
                    close()
                }
            }
        },
    ) {
        LazyColumn(
            modifier = Modifier.background(colors.background),
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(BLOCK_SPACING),
        ) {
            // The key is the block's index, and it must be: Compose saves per-item state across process death in a
            // Bundle, which rejects any custom type and would throw on the first layout pass. Within one chapter the
            // block order never changes, so the index identifies a block as well as anything else could.
            itemsIndexed(
                items = content.blocks,
                key = { index, _ -> index },
            ) { index, block ->
                Box(
                    modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally),
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = effectiveSettings.maxTextWidthDp.dp)
                            .fillMaxWidth()
                            .padding(horizontal = effectiveSettings.horizontalMarginDp.dp),
                    ) {
                        ChapterBlock(
                            block = block,
                            settings = effectiveSettings,
                            paragraphStyle = paragraphStyle,
                            textColor = colors.text,
                            dividerColor = dividerColor,
                            index = index,
                            onDiagnosticParagraphPositioned = onDiagnosticParagraphPositioned,
                        )
                    }
                }
            }
            if (footer != null) {
                // String key cannot collide with the integer content keys. This item is not a content block.
                item(key = "chapter-footer") {
                    DisableSelection { footer() }
                }
            }
        }
    }
}

@Composable
private fun ChapterBlock(
    block: ContentBlock,
    settings: ReaderSettings,
    paragraphStyle: TextStyle,
    textColor: Color,
    dividerColor: Color,
    index: Int,
    onDiagnosticParagraphPositioned: ((Int, ContentBlock.Paragraph, LayoutCoordinates) -> Unit)?,
) {
    when (block) {
        is ContentBlock.Paragraph -> Text(
            text = block.text,
            style = paragraphStyle,
            modifier = if (onDiagnosticParagraphPositioned == null) Modifier else Modifier.onGloballyPositioned {
                // Observe the concrete Text, not its accessible node or a surrounding list item.
                onDiagnosticParagraphPositioned(index, block, it)
            },
        )

        is ContentBlock.Heading -> Text(
            text = block.text,
            style = settings.headingStyle(level = block.level, textColor = textColor),
        )

        is ContentBlock.Image -> AsyncImage(
            model = block.url,
            contentDescription = block.alt,
            modifier = Modifier.fillMaxWidth(),
        )

        ContentBlock.Divider -> HorizontalDivider(color = dividerColor)
    }
}

private data object ShareSelection

/**
 * Headings follow the Material3 type scale but keep the reader's font family and text colour, because heading
 * text is still chapter text. The level only picks the scale step.
 */
@Composable
private fun ReaderSettings.headingStyle(level: Int, textColor: Color): TextStyle {
    val typography = MaterialTheme.typography
    val base = when (level) {
        1 -> typography.headlineMedium
        2 -> typography.headlineSmall
        else -> typography.titleLarge
    }
    return base.copy(
        fontFamily = fontFamily.toComposeFontFamily(),
        color = textColor,
    )
}

private fun ReaderFontFamily.toComposeFontFamily(): ComposeFontFamily = when (this) {
    ReaderFontFamily.SERIF -> ComposeFontFamily.Serif
    ReaderFontFamily.SANS_SERIF -> ComposeFontFamily.SansSerif
    ReaderFontFamily.MONOSPACE -> ComposeFontFamily.Monospace
}
