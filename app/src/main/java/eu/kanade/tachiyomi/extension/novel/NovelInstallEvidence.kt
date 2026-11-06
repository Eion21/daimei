package eu.kanade.tachiyomi.extension.novel

import java.util.Collections

private fun snapshot(values: Set<String>?): Set<String>? = values?.let { Collections.unmodifiableSet(it.toSet()) }

/** No production issuer exists: a future verifier must bind all observations to the same artifact. */
internal class NovelVerifiedCandidate private constructor(
    val packageName: String?,
    val versionCode: Long?,
    val versionName: String?,
    val novelApi: String?,
    entryClasses: Set<String>?,
    val minSdk: Int?,
    val artifactSize: Long?,
    val artifactSha256: String?,
    currentSigners: Set<String>?,
    signerHistory: Set<String>?,
    val signerCompleteness: NovelSignerCompleteness,
    val rotation: NovelRotation,
    val layout: NovelApkLayout,
    val deviceSdk: Int?,
    apkAbis: Set<String>?,
    deviceAbis: Set<String>?,
    features: Set<String>?,
    sourceIds: Set<String>?,
) {
    val entryClasses = snapshot(entryClasses)
    val currentSigners = snapshot(currentSigners)
    val signerHistory = snapshot(signerHistory)
    val apkAbis = snapshot(apkAbis)
    val deviceAbis = snapshot(deviceAbis)
    val features = snapshot(features)

    /** Separately attested source identity, never derived from manifest or catalog strings. */
    val sourceIds = snapshot(sourceIds)
}

internal class NovelInstalledSnapshot private constructor(
    val packageName: String,
    val versionCode: Long?,
    currentSigners: Set<String>?,
    signerHistory: Set<String>?,
    sourceIds: Set<String>?,
    val signerCompleteness: NovelSignerCompleteness,
    val origin: NovelInstallOrigin,
    val freshness: NovelStateFreshness,
) {
    val currentSigners = snapshot(currentSigners)
    val signerHistory = snapshot(signerHistory)
    val sourceIds = snapshot(sourceIds)
}

internal enum class NovelInstallOrigin { ABSENT, SHARED, PRIVATE, UNKNOWN }
internal enum class NovelStateFreshness { CURRENT, STALE, UNKNOWN }
internal enum class NovelRotation { NONE, DECLARED, UNKNOWN }
internal enum class NovelApkLayout { STANDALONE, SPLIT, UNKNOWN }
internal enum class NovelSignerCompleteness { COMPLETE, INCOMPLETE, UNKNOWN }
internal enum class NovelInstallOperation { NEW, UPDATE }

/** An explicit scoped grant is not created from catalog signers. */
internal class NovelVersionTrust private constructor(
    val packageName: String,
    val versionCode: Long,
    currentSigners: Set<String>,
) {
    val currentSigners: Set<String> = snapshot(currentSigners)!!
}
