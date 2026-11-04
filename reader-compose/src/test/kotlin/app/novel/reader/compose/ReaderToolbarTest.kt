package app.novel.reader.compose

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import app.novel.reader.core.ReaderTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pure presentation checks, not a substitute for rendering or real-device scroll verification. */
class ReaderToolbarTest {
    @Test
    fun `toolbar palette is distinct translucent and readable over light and dark prose`() {
        for (theme in ReaderTheme.entries) {
            val chapter = ReaderColorScheme.colorsFor(theme)
            val toolbar = readerToolbarColors(theme)
            assertEquals(0.8f, toolbar.background.alpha, 0.001f)
            assertNotEquals(chapter.background, toolbar.background.copy(alpha = 1f))
            assertEquals(chapter.text, toolbar.text)
            assertEquals(1f, toolbar.text.alpha)
            assertEquals(1f, chapter.background.alpha)
            for (backdrop in listOf(Color.White, Color.Black)) {
                val surface = toolbar.background.compositeOver(backdrop).luminance()
                val text = toolbar.text.luminance()
                val contrast = (maxOf(surface, text) + 0.05f) / (minOf(surface, text) + 0.05f)
                assertTrue(contrast >= 4.5f, "$theme contrast over $backdrop: $contrast")
            }
        }
    }

    @Test
    fun `toolbar visual animation fades and translates over 180 milliseconds`() {
        for ((start, end) in listOf(0f to 1f, 1f to 0f)) {
            val animation = TargetBasedAnimation(
                animationSpec = readerToolbarAnimation,
                typeConverter = Float.VectorConverter,
                initialValue = start,
                targetValue = end,
            )
            assertEquals(180_000_000L, animation.durationNanos)
            val samples = (0..12).map { animation.getValueFromNanos(animation.durationNanos * it / 12) }
            assertEquals(start, samples.first())
            assertEquals(end, samples.last())
            assertTrue(samples.all { it in 0f..1f })
            assertTrue(samples.any { it > 0f && it < 1f })
            assertTrue(samples.zipWithNext().all { (a, b) -> if (start < end) a <= b else a >= b })
        }
    }
}
