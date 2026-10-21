package eu.kanade.presentation.more.settings.screen.about

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AboutScreenTest {

    @Test
    fun `whats new is hidden when release URL is empty`() {
        shouldShowWhatsNew(isDebug = false, releaseUrl = "") shouldBe false
    }

    @Test
    fun `whats new is hidden when stable release URL has no repository`() {
        // AppUpdateChecker derives this nonempty URL when GITHUB_REPO is empty.
        shouldShowWhatsNew(
            isDebug = false,
            releaseUrl = "https://github.com//releases/tag/v0.1.0",
        ) shouldBe false
    }

    @Test
    fun `whats new is hidden when nightly release URL has no repository`() {
        shouldShowWhatsNew(
            isDebug = false,
            releaseUrl = "https://github.com//releases/tag/r123",
        ) shouldBe false
    }

    @Test
    fun `whats new is shown when release URL is configured`() {
        shouldShowWhatsNew(
            isDebug = false,
            releaseUrl = "https://github.com/example/reader/releases/tag/v0.1.0",
        ) shouldBe true
    }

    @Test
    fun `debug builds keep whats new hidden regardless of release URL`() {
        listOf(
            "",
            "https://github.com//releases/tag/v0.1.0",
            "https://github.com//releases/tag/r123",
            "https://github.com/example/reader/releases/tag/v0.1.0",
        ).forEach { releaseUrl ->
            shouldShowWhatsNew(isDebug = true, releaseUrl = releaseUrl) shouldBe false
        }
    }
}
