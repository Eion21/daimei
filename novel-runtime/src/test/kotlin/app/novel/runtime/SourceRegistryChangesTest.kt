package app.novel.runtime

import app.novel.source.api.Chapter
import app.novel.source.api.ChapterContent
import app.novel.source.api.Novel
import app.novel.source.api.NovelPage
import app.novel.source.api.NovelSource
import app.novel.source.api.SourceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The observable surface of the registry.
 *
 * `:novel-runtime` has no coroutines dependency, so the registry signals changes through plain callbacks. The
 * Android layer bridges these to a Flow; every piece of reactivity that makes an extension-installed novel source
 * appear without a restart rests on a registration actually reaching these listeners.
 */
class SourceRegistryChangesTest {

    private val registry = SourceRegistry()

    @Test
    fun `registering a new source notifies a listener`() {
        var notifications = 0
        registry.addListener { notifications++ }

        registry.register(novelSource("late"))

        assertEquals(1, notifications)
    }

    @Test
    fun `registering the same instance again is not a change`() {
        val source = novelSource("late")
        registry.register(source)
        var notifications = 0
        registry.addListener { notifications++ }

        // A reload re-registers every source it still has; that must not look like a new one.
        registry.register(source)

        assertEquals(0, notifications)
    }

    @Test
    fun `registering a new instance for the same id is a change`() {
        registry.register(novelSource("late"))
        var notifications = 0
        registry.addListener { notifications++ }

        // An updated apk produces a new instance under the same id and must replace it.
        registry.register(novelSource("late"))

        assertEquals(1, notifications)
    }

    @Test
    fun `unregistering a source notifies and stops resolving it`() {
        registry.register(novelSource("gone"))
        var notifications = 0
        registry.addListener { notifications++ }

        registry.unregister("gone")

        assertEquals(1, notifications)
        assertNull(registry.get("gone"))
        assertNull(registry.getBySourceId(NovelSourceId.generate("gone")))
    }

    @Test
    fun `unregistering an unknown source notifies nothing`() {
        var notifications = 0
        registry.addListener { notifications++ }

        registry.unregister("never-registered")

        assertEquals(0, notifications)
    }

    @Test
    fun `a removed listener is not notified`() {
        var notifications = 0
        val listener: () -> Unit = { notifications++ }
        registry.addListener(listener)
        registry.removeListener(listener)

        registry.register(novelSource("late"))

        assertEquals(0, notifications)
    }

    private fun novelSource(id: String) = object : NovelSource {
        override val info = SourceInfo(id, id, "1", "en")
        override suspend fun popular(page: Int) = NovelPage(emptyList(), hasNextPage = false)
        override suspend fun search(query: String, page: Int) = NovelPage(emptyList(), hasNextPage = false)
        override suspend fun getNovelDetails(novel: Novel) = novel
        override suspend fun getChapters(novel: Novel) = emptyList<Chapter>()
        override suspend fun getChapterContent(chapter: Chapter) = ChapterContent(emptyList())
    }
}
