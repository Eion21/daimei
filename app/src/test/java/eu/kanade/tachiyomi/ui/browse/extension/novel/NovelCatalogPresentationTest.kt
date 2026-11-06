package eu.kanade.tachiyomi.ui.browse.extension.novel

import eu.kanade.tachiyomi.extension.novel.NovelCatalog
import eu.kanade.tachiyomi.extension.novel.NovelCatalogCodec
import eu.kanade.tachiyomi.extension.novel.NovelCatalogError
import eu.kanade.tachiyomi.extension.novel.NovelCatalogException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelCatalogPresentationTest {
    private fun catalog(): NovelCatalog = requireNotNull(javaClass.getResourceAsStream("/s38/local-test.json"))
        .use(NovelCatalogCodec::decode)

    @Test
    fun `metadata reports actual package and safe origin with blocked compatibility`() = runBlocking {
        val presentation = NovelCatalogPresentation { catalog() }
        presentation.query("https://catalog.invalid/path/catalog.json?secret=value")
        assertTrue(presentation.state.value is NovelCatalogState.Metadata)
        val state = presentation.state.value as NovelCatalogState.Metadata
        assertEquals("https://catalog.invalid/path/catalog.json", state.origin)
        assertEquals(3, state.entries.size)
        assertEquals("app.novel.extension.jabrascan", state.entries.first().packageName)
        assertEquals(4L, state.entries.first().versionCode)
        assertEquals("1.3", state.entries.first().novelApi)
        assertTrue(state.entries.all { !it.hostCompatible })
    }

    @Test
    fun `typed failures remain distinct and clear earlier metadata`() = runBlocking {
        val reasons = listOf(
            NovelCatalogError.URL,
            NovelCatalogError.NETWORK,
            NovelCatalogError.SCHEMA,
            NovelCatalogError.JSON,
        )
        for (reason in reasons) {
            val presentation = NovelCatalogPresentation { url ->
                if (url.endsWith("ok")) catalog() else throw NovelCatalogException(reason)
            }
            presentation.query("https://catalog.invalid/ok")
            assertTrue(presentation.state.value is NovelCatalogState.Metadata)
            presentation.query("https://catalog.invalid/error")
            assertEquals(NovelCatalogState.Error(reason), presentation.state.value)
        }
    }

    @Test
    fun `loading clears prior metadata and cancellation propagates`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val presentation = NovelCatalogPresentation { url ->
            if (url.endsWith("ok")) {
                catalog()
            } else {
                started.complete(Unit)
                throw CancellationException("cancelled")
            }
        }
        presentation.query("https://catalog.invalid/ok")
        assertThrows(CancellationException::class.java) {
            runBlocking { presentation.query("https://catalog.invalid/cancel") }
        }
        assertTrue(started.isCompleted)
        assertFalse(presentation.state.value is NovelCatalogState.Metadata)
    }

    @Test
    fun `invalid URL does not invoke fetch`() = runBlocking {
        var calls = 0
        val presentation = NovelCatalogPresentation {
            calls++
            catalog()
        }
        presentation.query("https://user:password@catalog.invalid/catalog.json")
        assertEquals(NovelCatalogState.Error(NovelCatalogError.URL), presentation.state.value)
        assertEquals(0, calls)
    }

    @Test
    fun `loading removes old metadata and stale error cannot replace newer result`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val presentation = NovelCatalogPresentation { url ->
            if (url.endsWith("old")) {
                started.complete(Unit)
                withContext(NonCancellable) { finish.await() }
                throw NovelCatalogException(NovelCatalogError.NETWORK)
            }
            catalog()
        }
        presentation.query("https://first.invalid/first")
        val old = async { presentation.query("https://old.invalid/old") }
        started.await()
        assertEquals(NovelCatalogState.Loading, presentation.state.value)
        presentation.query("https://new.invalid/new")
        finish.complete(Unit)
        old.await()
        assertTrue(presentation.state.value is NovelCatalogState.Metadata)
        assertEquals("https://new.invalid/new", (presentation.state.value as NovelCatalogState.Metadata).origin)
    }

    @Test
    fun `late superseded query cannot replace newer metadata`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val presentation = NovelCatalogPresentation { url ->
            if (url.endsWith("old")) {
                started.complete(Unit)
                withContext(NonCancellable) { finish.await() }
            }
            catalog()
        }
        val old = async { presentation.query("https://old.invalid/old") }
        started.await()
        presentation.query("https://new.invalid/new")
        finish.complete(Unit)
        old.await()
        assertTrue(presentation.state.value is NovelCatalogState.Metadata)
        val state = presentation.state.value as NovelCatalogState.Metadata
        assertEquals("https://new.invalid/new", state.origin)
    }
}
