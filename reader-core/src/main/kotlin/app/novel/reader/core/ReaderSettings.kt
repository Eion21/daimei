package app.novel.reader.core

/** Font choices offered by the reader. Mapped to real families by the Compose layer. */
enum class FontFamily { SERIF, SANS_SERIF, MONOSPACE }

/**
 * Typography and layout for the reader. Values are expressed in whatever unit the caller needs; this type
 * only validates and defaults, so it can be tested without a Compose or Android runtime.
 */
data class ReaderSettings(
    val fontFamily: FontFamily = FontFamily.SERIF,
    val fontSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.6f,
    val horizontalMarginDp: Int = 24,
    val maxTextWidthDp: Int = 720,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val followSystemTheme: Boolean = true,
) {
    /**
     * Clamped to ranges that stay readable. A zero or negative font size would render nothing and an
     * extreme multiplier would make a chapter unusable, so bad input is corrected instead of thrown.
     */
    fun normalized(): ReaderSettings = copy(
        fontSizeSp = fontSizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP),
        lineHeightMultiplier = lineHeightMultiplier.coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT),
        horizontalMarginDp = horizontalMarginDp.coerceIn(MIN_MARGIN_DP, MAX_MARGIN_DP),
        maxTextWidthDp = maxTextWidthDp.coerceIn(MIN_TEXT_WIDTH_DP, MAX_TEXT_WIDTH_DP),
    )

    companion object {
        const val MIN_FONT_SIZE_SP = 12f
        const val MAX_FONT_SIZE_SP = 32f
        const val MIN_LINE_HEIGHT = 1.0f
        const val MAX_LINE_HEIGHT = 2.5f
        const val MIN_MARGIN_DP = 0
        const val MAX_MARGIN_DP = 64
        const val MIN_TEXT_WIDTH_DP = 280
        const val MAX_TEXT_WIDTH_DP = 1200
    }
}
