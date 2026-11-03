package app.novel.runtime

import app.novel.source.api.Novel
import app.novel.source.api.NovelSource
import app.novel.source.api.SourceInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SourceRegistryConcurrencyTest {
    @Test
    fun `concurrent additions publish coherent lookups and retain distinct writers`() {
        val registry = SourceRegistry()
        val sources = sources()
        val ids = sources.map { NovelSourceId.generate(it.info.id) }

        race(
            writer = { worker ->
                sources.indices.filter { it % WRITERS == worker }.forEach { registry.register(sources[it]) }
            },
            reader = {
                repeat(SCANS) {
                    sources.indices.forEach { i ->
                        if (registry.get(sources[i].info.id) === sources[i]) {
                            assertSame(sources[i], registry.getBySourceId(ids[i]), "addition lookup $i")
                        }
                    }
                }
            },
        )

        assertEquals(sources.size, registry.all().size)
        sources.indices.forEach { i ->
            assertSame(sources[i], registry.get(sources[i].info.id), "text lookup $i")
            assertSame(sources[i], registry.getBySourceId(ids[i]), "numeric lookup $i")
        }
    }

    @Test
    fun `concurrent removals publish coherent lookups and retain distinct writers`() {
        val registry = SourceRegistry()
        val sources = sources()
        val ids = sources.map { NovelSourceId.generate(it.info.id) }
        sources.forEach(registry::register)

        race(
            writer = { worker ->
                sources.indices.filter { it % WRITERS == worker }.forEach { registry.unregister(sources[it].info.id) }
            },
            reader = {
                repeat(SCANS) {
                    sources.indices.forEach { i ->
                        if (registry.get(sources[i].info.id) == null) {
                            assertNull(registry.getBySourceId(ids[i]), "removal lookup $i")
                        }
                    }
                }
            },
        )

        assertEquals(emptyList(), registry.all())
        sources.indices.forEach { i ->
            assertNull(registry.get(sources[i].info.id), "text lookup $i")
            assertNull(registry.getBySourceId(ids[i]), "numeric lookup $i")
        }
    }

    private fun race(writer: (Int) -> Unit, reader: () -> Unit) {
        val pool = Executors.newFixedThreadPool(WRITERS + READERS)
        val ready = CountDownLatch(WRITERS + READERS)
        val start = CountDownLatch(1)
        try {
            val tasks = (0 until WRITERS + READERS).map { index ->
                pool.submit {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    if (index < WRITERS) writer(index) else reader()
                }
            }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            pool.shutdownNow()
            check(pool.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    private fun sources(): List<NovelSource> = (0 until 128).map { index ->
        val id = "$index-${"x".repeat(8192)}"
        object : NovelSource {
            override val info = SourceInfo(id, "Source $index", "1", "es")
            override suspend fun popular(page: Int) = throw NotImplementedError()
            override suspend fun search(query: String, page: Int) = throw NotImplementedError()
            override suspend fun getNovelDetails(novel: Novel) = throw NotImplementedError()
            override suspend fun getChapters(novel: Novel) = throw NotImplementedError()
            override suspend fun getChapterContent(chapter: app.novel.source.api.Chapter) = throw NotImplementedError()
        }
    }

    private companion object {
        const val WRITERS = 4
        const val READERS = 4
        const val SCANS = 64
    }
}
