package eu.kanade.tachiyomi.extension.novel

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Consent gates, not evidence of APK identity or permission to execute code. */
internal class NovelDistributionEnrollment(private val backend: Backend) {
    internal interface Backend {
        fun enrollAndFetch(repositoryUrl: String): NovelDistributionCatalog
        fun downloadAndStage(entry: NovelDistributionEntry, approvedOrigin: String)
    }

    internal sealed interface Result {
        data object Denied : Result
        data class Catalog(val value: NovelDistributionCatalog) : Result
        data object Staged : Result
    }

    fun enrollRepository(repositoryUrl: String, explicitConsent: Boolean): Result {
        if (!explicitConsent || repositoryUrl.length > 2048) return Result.Denied
        val url = repositoryUrl.toHttpUrlOrNull() ?: return Result.Denied
        if (!url.safeHttps()) return Result.Denied
        return Result.Catalog(backend.enrollAndFetch(url.toString()))
    }

    fun install(entry: NovelDistributionEntry, approvedOrigin: String?): Result {
        if (!NovelDistributionCatalogCodec.isValidExpectation(entry)) return Result.Denied
        val artifact = entry.url.toHttpUrlOrNull() ?: return Result.Denied
        val approved = approvedOrigin?.toHttpUrlOrNull() ?: return Result.Denied
        if (!artifact.safeHttps() || !approved.safeHttps() || approved.encodedPath != "/" ||
            approved.query != null || approved.host != artifact.host || approved.port != artifact.port
        ) return Result.Denied
        backend.downloadAndStage(entry, approved.toString().removeSuffix("/"))
        return Result.Staged
    }

    private fun HttpUrl.safeHttps() = isHttps && username.isEmpty() && password.isEmpty() &&
        fragment == null && !host.endsWith('.')
}
