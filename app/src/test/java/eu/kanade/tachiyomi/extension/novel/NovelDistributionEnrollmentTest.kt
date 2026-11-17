package eu.kanade.tachiyomi.extension.novel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

internal class NovelDistributionEnrollmentTest {
    private val digest = "a".repeat(64)
    private val catalog = NovelDistributionCatalogCodec.decode(
        ("""{"protocol":"daimei-novel-distribution","schemaVersion":2,"repository":"example","extensions":[""" +
            """{"packageName":"app.novel.extension.fixtures","longVersionCode":1,"versionName":"1",""" +
            """"novelApi":"1.1","entryClasses":["app.novel.extension.fixtures.Source"],"minSdk":26,""" +
            """"artifact":{"url":"https://assets.example.org/fixture.apk","size":1,"sha256":"$digest"},""" +
            """"currentSigners":["$digest"]}]}""").byteInputStream(),
    )
    private val entry = catalog.entries.single()

    private inner class Backend : NovelDistributionEnrollment.Backend {
        val repositories = mutableListOf<String>()
        var requests = 0
        var installs = 0
        var selected: NovelDistributionEntry? = null
        var origin: String? = null
        override fun enrollAndFetch(repositoryUrl: String): NovelDistributionCatalog {
            repositories.add(repositoryUrl)
            requests++
            return catalog
        }
        override fun downloadAndStage(entry: NovelDistributionEntry, approvedOrigin: String) {
            requests++
            installs++
            selected = entry
            origin = approvedOrigin
        }
    }

    @Test fun `repository without explicit consent causes no enrollment or network or install`() {
        val backend = Backend()
        val gate = NovelDistributionEnrollment(backend)
        assertEquals(NovelDistributionEnrollment.Result.Denied, gate.enrollRepository("https://repo.example.org/catalog.json", false))
        assertEquals(emptyList<String>(), backend.repositories)
        assertEquals(0, backend.requests)
        assertEquals(0, backend.installs)
    }

    @Test fun `consented HTTPS repository is enrolled and its actual schema 2 catalog returned`() {
        val backend = Backend()
        val gate = NovelDistributionEnrollment(backend)
        val result = gate.enrollRepository("https://repo.example.org/catalog.json", true) as NovelDistributionEnrollment.Result.Catalog
        assertSame(catalog, result.value)
        assertEquals(listOf("https://repo.example.org/catalog.json"), backend.repositories)
        assertEquals(1, backend.requests)
        assertEquals(0, backend.installs)
    }

    @Test fun `invalid repository is denied even with consent and has no side effects`() {
        val backend = Backend()
        val gate = NovelDistributionEnrollment(backend)
        listOf("http://repo.example.org/catalog.json", "https://user:pass@repo.example.org/catalog.json", "https://repo.example.org/#fragment")
            .forEach { assertEquals(NovelDistributionEnrollment.Result.Denied, gate.enrollRepository(it, true)) }
        assertEquals(emptyList<String>(), backend.repositories)
        assertEquals(0, backend.requests)
        assertEquals(0, backend.installs)
    }

    @Test fun `repository consent is not artifact origin consent`() {
        val backend = Backend()
        val gate = NovelDistributionEnrollment(backend)
        gate.enrollRepository("https://repo.example.org/catalog.json", true)
        val repositories = backend.repositories.toList()
        val requests = backend.requests
        assertEquals(NovelDistributionEnrollment.Result.Denied, gate.install(entry, null))
        assertEquals(repositories, backend.repositories)
        assertEquals(requests, backend.requests)
        assertEquals(0, backend.installs)
    }

    @Test fun `different artifact origin consent cannot authorize selected entry`() {
        val backend = Backend()
        val gate = NovelDistributionEnrollment(backend)
        assertEquals(NovelDistributionEnrollment.Result.Denied, gate.install(entry, "https://other.example.org"))
        assertEquals(emptyList<String>(), backend.repositories)
        assertEquals(0, backend.requests)
        assertEquals(0, backend.installs)
    }

    @Test fun `selected artifact is staged only after consent to its own HTTPS origin`() {
        val backend = Backend()
        val gate = NovelDistributionEnrollment(backend)
        assertEquals(NovelDistributionEnrollment.Result.Staged, gate.install(entry, "https://assets.example.org"))
        assertSame(entry, backend.selected)
        assertEquals("https://assets.example.org", backend.origin)
        assertEquals(emptyList<String>(), backend.repositories)
        assertEquals(1, backend.requests)
        assertEquals(1, backend.installs)
    }
}
