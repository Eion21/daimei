package app.novel.runtime

import app.novel.source.api.NovelSource
import app.novel.source.api.SourceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceRegistryTest {
    private val registry = SourceRegistry()

    private val source1 = object : NovelSource {
        override val info = SourceInfo("demo", "Demo", "1", "es")
        override suspend fun popular(page: Int) = throw NotImplementedError()
        override suspend fun search(query: String, page: Int) = throw NotImplementedError()
        override suspend fun getNovelDetails(novel: app.novel.source.api.Novel) = throw NotImplementedError()
        override suspend fun getChapters(novel: app.novel.source.api.Novel) = throw NotImplementedError()
        override suspend fun getChapterContent(chapter: app.novel.source.api.Chapter) = throw NotImplementedError()
    }

    private val source2 = object : NovelSource {
        override val info = SourceInfo("demo2", "Demo2", "1", "en")
        override suspend fun popular(page: Int) = throw NotImplementedError()
        override suspend fun search(query: String, page: Int) = throw NotImplementedError()
        override suspend fun getNovelDetails(novel: app.novel.source.api.Novel) = throw NotImplementedError()
        override suspend fun getChapters(novel: app.novel.source.api.Novel) = throw NotImplementedError()
        override suspend fun getChapterContent(chapter: app.novel.source.api.Chapter) = throw NotImplementedError()
    }

    @Test
    fun `register and retrieve source by id`() {
        registry.register(source1)
        registry.register(source2)

        assertEquals(source1, registry.get("demo"))
        assertEquals(source2, registry.get("demo2"))
        assertNull(registry.get("nonexistent"))
    }

    @Test
    fun `all returns sources sorted by name`() {
        registry.register(source2)
        registry.register(source1)

        val all = registry.all()
        assertEquals(2, all.size)
        assertEquals("Demo", all[0].info.name)
        assertEquals("Demo2", all[1].info.name)
    }
}
