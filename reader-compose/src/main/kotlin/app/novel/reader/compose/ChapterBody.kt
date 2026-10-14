package app.novel.reader.compose

import androidx.compose.foundation.background
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 */
@Composable
fun ChapterBody(
    content: ChapterContent,
    settings: ReaderSettings,
    listState: LazyListState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
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

    LazyColumn(
        modifier = modifier.background(colors.background),
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
        ) { _, block ->
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
                    )
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
) {
    when (block) {
        is ContentBlock.Paragraph -> Text(text = block.text, style = paragraphStyle)

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
