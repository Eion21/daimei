package app.novel.reader.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import app.novel.reader.core.ReaderTheme

/**
 * The colours the chapter surface paints with.
 *
 * This is deliberately not a Material3 `ColorScheme`: a chapter is a reading surface, and its colours must not
 * change because the rest of the app switched theme underneath it.
 */
@Immutable
data class ReaderColors(
    val background: Color,
    val text: Color,
)

/** Maps the theme owned by `reader-core` onto the real colours used to paint a chapter. */
object ReaderColorScheme {

    /**
     * Colours for [theme]. [ReaderTheme.SEPIA] is a warm, low-contrast paper tone rather than a tinted dark
     * mode, because it is meant to be read for hours.
     */
    fun colorsFor(theme: ReaderTheme): ReaderColors = when (theme) {
        ReaderTheme.LIGHT -> ReaderColors(background = Color(0xFFFFFBFF), text = Color(0xFF1B1B1F))
        ReaderTheme.DARK -> ReaderColors(background = Color(0xFF121316), text = Color(0xFFE3E1DD))
        ReaderTheme.SEPIA -> ReaderColors(background = Color(0xFFF4ECD8), text = Color(0xFF4B3A26))
    }
}
