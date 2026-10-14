package app.novel.reader.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReaderSettingsTest {

    @Test
    fun `defaults are readable typography`() {
        val settings = ReaderSettings()

        assertEquals(18f, settings.fontSizeSp)
        assertEquals(1.6f, settings.lineHeightMultiplier)
        assertEquals(24, settings.horizontalMarginDp)
        assertEquals(720, settings.maxTextWidthDp)
        assertEquals(FontFamily.SERIF, settings.fontFamily)
        assertEquals(ReaderTheme.LIGHT, settings.theme)
        assertTrue(settings.followSystemTheme)
    }

    @Test
    fun `every default sits inside its own range`() {
        val settings = ReaderSettings()

        assertTrue(settings.fontSizeSp in ReaderSettings.MIN_FONT_SIZE_SP..ReaderSettings.MAX_FONT_SIZE_SP)
        assertTrue(
            settings.lineHeightMultiplier in ReaderSettings.MIN_LINE_HEIGHT..ReaderSettings.MAX_LINE_HEIGHT,
        )
        assertTrue(
            settings.horizontalMarginDp in ReaderSettings.MIN_MARGIN_DP..ReaderSettings.MAX_MARGIN_DP,
        )
        assertTrue(
            settings.maxTextWidthDp in ReaderSettings.MIN_TEXT_WIDTH_DP..ReaderSettings.MAX_TEXT_WIDTH_DP,
        )
    }

    @Test
    fun `normalized leaves the defaults untouched`() {
        assertEquals(ReaderSettings(), ReaderSettings().normalized())
    }

    @Test
    fun `normalized raises a four sp font to the minimum`() {
        val normalized = ReaderSettings(fontSizeSp = 4f).normalized()
        assertEquals(ReaderSettings.MIN_FONT_SIZE_SP, normalized.fontSizeSp)
    }

    @Test
    fun `normalized lowers a hundred sp font to the maximum`() {
        val normalized = ReaderSettings(fontSizeSp = 100f).normalized()
        assertEquals(ReaderSettings.MAX_FONT_SIZE_SP, normalized.fontSizeSp)
    }

    @Test
    fun `normalized clamps the line height to its range`() {
        assertEquals(
            ReaderSettings.MIN_LINE_HEIGHT,
            ReaderSettings(lineHeightMultiplier = 0.1f).normalized().lineHeightMultiplier,
        )
        assertEquals(
            ReaderSettings.MAX_LINE_HEIGHT,
            ReaderSettings(lineHeightMultiplier = 9f).normalized().lineHeightMultiplier,
        )
    }

    @Test
    fun `normalized clamps the horizontal margin to its range`() {
        assertEquals(
            ReaderSettings.MIN_MARGIN_DP,
            ReaderSettings(horizontalMarginDp = -40).normalized().horizontalMarginDp,
        )
        assertEquals(
            ReaderSettings.MAX_MARGIN_DP,
            ReaderSettings(horizontalMarginDp = 900).normalized().horizontalMarginDp,
        )
    }

    @Test
    fun `normalized clamps the maximum text width to its range`() {
        assertEquals(
            ReaderSettings.MIN_TEXT_WIDTH_DP,
            ReaderSettings(maxTextWidthDp = 10).normalized().maxTextWidthDp,
        )
        assertEquals(
            ReaderSettings.MAX_TEXT_WIDTH_DP,
            ReaderSettings(maxTextWidthDp = 5000).normalized().maxTextWidthDp,
        )
    }

    @Test
    fun `normalized keeps a valid font size exactly as it was`() {
        assertEquals(21f, ReaderSettings(fontSizeSp = 21f).normalized().fontSizeSp)
    }

    @Test
    fun `normalization is idempotent`() {
        val wild = ReaderSettings(
            fontSizeSp = 100f,
            lineHeightMultiplier = 9f,
            horizontalMarginDp = 900,
            maxTextWidthDp = 5000,
            fontFamily = FontFamily.MONOSPACE,
            theme = ReaderTheme.SEPIA,
            followSystemTheme = false,
        )

        val once = wild.normalized()
        assertEquals(once, once.normalized())
        assertEquals(once, wild.normalized().normalized())
    }

    @Test
    fun `normalized leaves the font family and theme alone`() {
        val chosen = ReaderSettings(
            fontFamily = FontFamily.MONOSPACE,
            theme = ReaderTheme.DARK,
            followSystemTheme = false,
        )
        assertEquals(chosen, chosen.normalized())
    }

    @Test
    fun `the reader offers three font families and three themes`() {
        assertEquals(3, FontFamily.entries.size)
        assertEquals(3, ReaderTheme.entries.size)
    }
}
