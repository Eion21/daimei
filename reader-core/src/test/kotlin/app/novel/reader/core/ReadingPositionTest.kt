package app.novel.reader.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadingPositionTest {

    @Test
    fun `normalized clamps a negative block index to zero`() {
        assertEquals(0, ReadingPosition(blockIndex = -4).normalized().blockIndex)
    }

    @Test
    fun `normalized clamps a fraction above one`() {
        assertEquals(1f, ReadingPosition(blockIndex = 2, blockFraction = 3.5f).normalized().blockFraction)
    }

    @Test
    fun `normalized clamps a negative fraction to zero`() {
        assertEquals(0f, ReadingPosition(blockIndex = 2, blockFraction = -0.25f).normalized().blockFraction)
    }

    @Test
    fun `normalized leaves an already valid position untouched`() {
        val position = ReadingPosition(blockIndex = 7, blockFraction = 0.4f)
        assertEquals(position, position.normalized())
    }

    @Test
    fun `toScrollOffset returns zero for a block with no measured height`() {
        assertEquals(0, BlockLayout.toScrollOffset(ReadingPosition(3, 0.5f), blockHeightPx = 0))
    }

    @Test
    fun `toScrollOffset is zero at the top of a block`() {
        assertEquals(0, BlockLayout.toScrollOffset(ReadingPosition(3, 0f), blockHeightPx = 400))
    }

    @Test
    fun `toScrollOffset is the block height at the bottom of a block`() {
        assertEquals(400, BlockLayout.toScrollOffset(ReadingPosition(3, 1f), blockHeightPx = 400))
    }

    @Test
    fun `toScrollOffset grows with the block height at a fixed fraction`() {
        val shortBlock = BlockLayout.toScrollOffset(ReadingPosition(0, 0.5f), blockHeightPx = 400)
        val tallBlock = BlockLayout.toScrollOffset(ReadingPosition(0, 0.5f), blockHeightPx = 900)

        assertEquals(200, shortBlock)
        assertEquals(450, tallBlock)
        assertTrue(tallBlock > shortBlock, "a taller block must produce a bigger offset at the same fraction")
    }

    @Test
    fun `a position survives a font size change that reflows the block`() {
        // Saved while the block measured 400px.
        val saved = ReadingPosition(blockIndex = 5, blockFraction = 0.5f)

        // Restored after the font grew, so the very same block now measures 900px.
        val offset = BlockLayout.toScrollOffset(saved, blockHeightPx = 900)
        assertEquals(450, offset)

        // Reading back yields the same logical spot even though the pixels differ.
        val restored = BlockLayout.toReadingPosition(blockIndex = 5, pixelOffset = offset, blockHeightPx = 900)
        assertEquals(0.5f, restored.blockFraction, 1e-5f)
    }

    @Test
    fun `toReadingPosition clamps an offset past the block height to fraction one`() {
        val restored = BlockLayout.toReadingPosition(blockIndex = 2, pixelOffset = 900, blockHeightPx = 400)
        assertEquals(2, restored.blockIndex)
        assertEquals(1f, restored.blockFraction)
    }

    @Test
    fun `toReadingPosition clamps a negative block index and offset`() {
        val restored = BlockLayout.toReadingPosition(blockIndex = -1, pixelOffset = -50, blockHeightPx = 400)
        assertEquals(0, restored.blockIndex)
        assertEquals(0f, restored.blockFraction)
    }

    @Test
    fun `toReadingPosition reports fraction zero when the block height is unknown`() {
        val restored = BlockLayout.toReadingPosition(blockIndex = 4, pixelOffset = 120, blockHeightPx = 0)
        assertEquals(4, restored.blockIndex)
        assertEquals(0f, restored.blockFraction)
    }

    @Test
    fun `a position round trips through an offset for many fractions and block heights`() {
        val fractions = listOf(0f, 0.1f, 0.25f, 1f / 3f, 0.5f, 0.75f, 0.9f, 1f)
        val heights = listOf(1, 37, 400, 613, 900, 2000)

        for (fraction in fractions) {
            for (height in heights) {
                val offset = BlockLayout.toScrollOffset(ReadingPosition(3, fraction), blockHeightPx = height)
                val restored = BlockLayout.toReadingPosition(3, offset, blockHeightPx = height)

                // Rounding to whole pixels can lose at most half a pixel of the block.
                val roundingError = 1f / height
                assertEquals(
                    fraction,
                    restored.blockFraction,
                    roundingError,
                    "round trip lost the spot for fraction=$fraction height=$height",
                )
            }
        }
    }

    @Test
    fun `isSameReadingSpot is true for the same position`() {
        val position = ReadingPosition(blockIndex = 4, blockFraction = 0.6f)
        assertTrue(BlockLayout.isSameReadingSpot(position, position))
    }

    @Test
    fun `isSameReadingSpot is false across different blocks`() {
        assertFalse(
            BlockLayout.isSameReadingSpot(
                ReadingPosition(blockIndex = 4, blockFraction = 0.6f),
                ReadingPosition(blockIndex = 5, blockFraction = 0.6f),
            ),
        )
    }

    @Test
    fun `isSameReadingSpot absorbs sub pixel reflow inside the tolerance`() {
        assertTrue(
            BlockLayout.isSameReadingSpot(
                ReadingPosition(blockIndex = 4, blockFraction = 0.5f),
                ReadingPosition(blockIndex = 4, blockFraction = 0.508f),
            ),
        )
    }

    @Test
    fun `isSameReadingSpot reports a real jump beyond the tolerance`() {
        assertFalse(
            BlockLayout.isSameReadingSpot(
                ReadingPosition(blockIndex = 4, blockFraction = 0.5f),
                ReadingPosition(blockIndex = 4, blockFraction = 0.52f),
            ),
        )
    }

    @Test
    fun `isSameReadingSpot honours a custom tolerance`() {
        val before = ReadingPosition(blockIndex = 4, blockFraction = 0.5f)
        val after = ReadingPosition(blockIndex = 4, blockFraction = 0.52f)

        assertTrue(BlockLayout.isSameReadingSpot(before, after, tolerance = 0.05f))
        assertFalse(BlockLayout.isSameReadingSpot(before, after, tolerance = 0.0f))
    }

    @Test
    fun `START is the top of the first block`() {
        assertEquals(ReadingPosition(blockIndex = 0, blockFraction = 0f), ReadingPosition.START)
    }
}
