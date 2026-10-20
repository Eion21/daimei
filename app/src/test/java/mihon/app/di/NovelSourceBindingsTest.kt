package mihon.app.di

import app.novel.source.demo.DemoSource
import app.novel.source.http.HttpNovelSource
import eu.kanade.tachiyomi.BuildConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NovelSourceBindingsTest {

    @Test
    fun `excluding development sources returns no sources`() {
        val sources = NovelSourceBindings.createNovelSources(includeDevSources = false)

        assertEquals(0, sources.size)
    }

    @Test
    fun `including development sources returns exactly demo and HTTP fixture sources`() {
        val sources = NovelSourceBindings.createNovelSources(includeDevSources = true)

        assertEquals(2, sources.size)
        assertEquals(
            setOf(DemoSource::class.java, HttpNovelSource::class.java),
            sources.map { it.javaClass }.toSet(),
        )
    }

    @Test
    fun `development sources are provided only in debug builds`() {
        val sources = NovelSourceBindings.novelSources()

        if (BuildConfig.DEBUG) {
            assertEquals(2, sources.size)
            assertEquals(
                setOf(DemoSource::class.java, HttpNovelSource::class.java),
                sources.map { it.javaClass }.toSet(),
            )
        } else {
            assertEquals(0, sources.size)
        }
    }
}
