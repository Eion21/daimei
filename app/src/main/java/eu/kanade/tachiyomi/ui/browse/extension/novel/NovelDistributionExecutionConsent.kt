package eu.kanade.tachiyomi.ui.browse.extension.novel

import eu.kanade.tachiyomi.extension.novel.NovelExecutionConsentOutcome

/** Sequences explicit execution consent, package-scoped trust and an observed novel reload.
 * Callbacks must validate their own current package facts; catalog data is not authority.
 */
internal class NovelDistributionExecutionConsent(
    private val grant: suspend (operationId: String, packageName: String, versionCode: Long) -> NovelExecutionConsentOutcome,
    private val trust: suspend (packageName: String, versionCode: Long) -> Boolean,
    private val reload: suspend (packageName: String, versionCode: Long) -> Boolean,
) {
    internal enum class Result { DENIED, UNRECOGNIZED, RECOGNIZED }

    suspend fun authorize(
        operationId: String,
        packageName: String,
        versionCode: Long,
        explicitConsent: Boolean,
    ): Result {
        if (!explicitConsent || operationId.isBlank() || packageName.isBlank() || versionCode <= 0) return Result.DENIED
        if (grant(operationId, packageName, versionCode) != NovelExecutionConsentOutcome.GRANTED) return Result.DENIED
        if (!trust(packageName, versionCode)) return Result.DENIED
        return if (reload(packageName, versionCode)) Result.RECOGNIZED else Result.UNRECOGNIZED
    }
}
