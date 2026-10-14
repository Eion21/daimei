package app.novel.reader.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Converts a [ReadingPosition] into the `(index, pixelOffset)` pair that a Compose `LazyListState` needs,
 * and back. The block height has to be measured by the caller, because only the layout knows it.
 */
object BlockLayout {

    /**
     * Pixel offset that puts [position] at the top of the viewport, given the height of the block it names.
     * A block with no measured height falls back to the block start rather than guessing.
     */
    fun toScrollOffset(position: ReadingPosition, blockHeightPx: Int): Int {
        val safe = position.normalized()
        if (blockHeightPx <= 0) return 0
        return (safe.blockFraction * blockHeightPx).roundToInt().coerceIn(0, blockHeightPx)
    }

    /** The inverse of [toScrollOffset], used when the reader is scrolling and progress is being saved. */
    fun toReadingPosition(blockIndex: Int, pixelOffset: Int, blockHeightPx: Int): ReadingPosition {
        if (blockHeightPx <= 0) return ReadingPosition(blockIndex.coerceAtLeast(0), 0f)
        val fraction = (pixelOffset.toFloat() / blockHeightPx).coerceIn(0f, 1f)
        return ReadingPosition(blockIndex.coerceAtLeast(0), fraction)
    }

    /**
     * True when [before] and [after] point at the same place in the text even though the rendered pixels
     * differ, which is what happens when the font size changes. Used to avoid treating a reflow as a jump.
     */
    fun isSameReadingSpot(before: ReadingPosition, after: ReadingPosition, tolerance: Float = 0.01f): Boolean =
        before.blockIndex == after.blockIndex &&
            abs(before.blockFraction - after.blockFraction) <= tolerance.coerceAtLeast(0f)
}
