package eu.kanade.tachiyomi.extension.novel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelSupportedApiVersionsTest {
    @Test
    fun `supported versions are exactly 1-0 through 1-3`() {
        assertEquals(setOf("1.0", "1.1", "1.2", "1.3"), NovelSupportedApiVersions.SUPPORTED_VERSIONS)
    }

    @Test
    fun `host version is 1-3`() {
        assertEquals("1.3", NovelSupportedApiVersions.HOST_VERSION)
    }

    @Test
    fun `each supported version is accepted`() {
        for (version in NovelSupportedApiVersions.SUPPORTED_VERSIONS) {
            assertTrue(NovelSupportedApiVersions.isSupported(version))
        }
    }

    @Test
    fun `unknown future and noncanonical versions are rejected`() {
        for (version in listOf("1.4", "2.0", "0.9", "1", "1.1.1", "1,1", "")) {
            assertFalse(NovelSupportedApiVersions.isSupported(version))
        }
    }

    @Test
    fun `null is rejected`() {
        assertFalse(NovelSupportedApiVersions.isSupported(null))
    }
}
