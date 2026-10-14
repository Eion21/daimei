package eu.kanade.tachiyomi.ui.reader.novel

import app.novel.reader.core.FontFamily
import app.novel.reader.core.ReaderSettings
import app.novel.reader.core.ReaderTheme
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

/**
 * Typography and layout preferences for the novel reader.
 *
 * Separate from [eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences] on purpose: the image reader keeps
 * its settings as page-level values, while the novel reader works in typographic units that come straight from
 * [ReaderSettings].
 */
@Inject
@SingleIn(AppScope::class)
class NovelReaderPreferences(
    preferenceStore: PreferenceStore,
) {

    // region Typography

    val fontSizeSp: Preference<Int> = preferenceStore.getInt("pref_novel_font_size", 18)

    /**
     * Line height as a whole percent, not a float multiplier.
     *
     * `lineHeightMultiplier` is a float, and round-tripping a float through string preferences loses the exact
     * value the user picked. Storing an integer percent keeps the choice exact and still converts to the
     * multiplier the reader needs.
     */
    val lineHeightPercent: Preference<Int> = preferenceStore.getInt("pref_novel_line_height", 160)

    val fontFamily: Preference<FontFamily> = preferenceStore.getEnum(
        "pref_novel_font_family",
        FontFamily.SERIF,
    )

    // endregion

    // region Layout

    val horizontalMarginDp: Preference<Int> = preferenceStore.getInt("pref_novel_margin", 24)

    val maxTextWidthDp: Preference<Int> = preferenceStore.getInt("pref_novel_text_width", 720)

    // endregion

    // region Theme

    val theme: Preference<ReaderTheme> = preferenceStore.getEnum("pref_novel_theme", ReaderTheme.LIGHT)

    val followSystemTheme: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_follow_system_theme",
        true,
    )

    // endregion

    /**
     * Reads the stored values into the settings the reader renders with.
     *
     * Always normalized, so a preference written by an older or newer build — or a hand-edited backup — cannot
     * make the reader render unreadable text.
     */
    fun settings(): ReaderSettings = ReaderSettings(
        fontFamily = fontFamily.get(),
        fontSizeSp = fontSizeSp.get().toFloat(),
        lineHeightMultiplier = lineHeightPercent.get() / PERCENT,
        horizontalMarginDp = horizontalMarginDp.get(),
        maxTextWidthDp = maxTextWidthDp.get(),
        theme = theme.get(),
        followSystemTheme = followSystemTheme.get(),
    ).normalized()

    companion object {
        private const val PERCENT = 100f
    }
}
