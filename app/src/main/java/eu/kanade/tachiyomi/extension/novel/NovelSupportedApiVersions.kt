package eu.kanade.tachiyomi.extension.novel

/**
 * Canonical supported novel extension API versions.
 *
 * This is the single non-Android source of truth for the versions the host loader accepts.
 * It must stay aligned with [eu.kanade.tachiyomi.extension.util.SUPPORTED_NOVEL_API_VERSIONS]
 * and with the contract published by `:novel-api`. Unknown, future, or otherwise noncanonical
 * versions are rejected at every distribution boundary.
 */
object NovelSupportedApiVersions {
    /** API version of the running host (ContextAwareNovelSource / contract 1.3). */
    const val HOST_VERSION: String = "1.3"

    /** Ordered set of API version strings this host can run. */
    val SUPPORTED_VERSIONS: Set<String> = setOf("1.0", "1.1", "1.2", "1.3")

    /** Whether [version] is a known, supported novel extension API version. */
    fun isSupported(version: String?): Boolean = version in SUPPORTED_VERSIONS
}
