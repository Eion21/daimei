package app.novel.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NovelSourceIdTest {

    @Test
    fun `derives the id every already stored novel is keyed on`() {
        // Pinned as literals rather than recomputed, because this value is not internal bookkeeping: it is the
        // `manga.source_id` column of every novel row this app has written, and external libraries are keyed on
        // it too. A change here would orphan user data without a single error being raised.
        assertEquals(7_017_121_899_777_107_854L, NovelSourceId.generate("demo"))
        assertEquals(7_045_834_738_819_061_263L, NovelSourceId.generate("demo2"))
    }

    @Test
    fun `the same info id always derives the same id`() {
        assertEquals(NovelSourceId.generate("demo"), NovelSourceId.generate("demo"))
    }

    @Test
    fun `two sources never share an id`() {
        assertNotEquals(NovelSourceId.generate("demo"), NovelSourceId.generate("demo2"))
        assertNotEquals(NovelSourceId.generate("demo"), NovelSourceId.generate("otra"))
    }

    @Test
    fun `an id is always positive so it cannot collide with the no-source marker`() {
        // Mihon reads -1 as "no source", so a derived id that can be negative would be indistinguishable from a
        // row that never came from a source at all.
        listOf("demo", "demo2", "otra", "", "a-very-long-source-id-that-does-not-exist-yet").forEach { infoId ->
            assertTrue(NovelSourceId.generate(infoId) >= 0, "negative id for '$infoId'")
        }
    }
}
