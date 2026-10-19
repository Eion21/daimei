package app.novel.reader.compose

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderInteractionTest {
    @Test
    fun `only taps in the upper half toggle controls`() {
        assertTrue(isReaderControlsTap(0f, 800))
        assertTrue(isReaderControlsTap(399f, 800))
        assertFalse(isReaderControlsTap(400f, 800))
        assertFalse(isReaderControlsTap(799f, 800))
    }

    @Test
    fun `invalid viewport coordinates cannot toggle controls`() {
        assertFalse(isReaderControlsTap(-1f, 800))
        assertFalse(isReaderControlsTap(0f, 0))
        assertFalse(isReaderControlsTap(Float.NaN, 800))
    }
}
