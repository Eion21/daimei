package app.novel.reader.core

/**
 * Where the reader is in a chapter, expressed so that it survives a change of font size or screen size.
 *
 * [blockIndex] is the index of the first visible content block and [blockFraction] is how far through that
 * block the viewport has scrolled, from 0 at its top to 1 at its bottom. Storing a pixel offset instead
 * would land somewhere else on the page as soon as the text reflows.
 */
data class ReadingPosition(
    val blockIndex: Int = 0,
    val blockFraction: Float = 0f,
) {
    /** Fraction pinned to 0..1 and index never negative, so a corrupt stored value cannot break scrolling. */
    fun normalized(): ReadingPosition = copy(
        blockIndex = blockIndex.coerceAtLeast(0),
        blockFraction = blockFraction.coerceIn(0f, 1f),
    )

    companion object {
        val START = ReadingPosition(0, 0f)
    }
}
