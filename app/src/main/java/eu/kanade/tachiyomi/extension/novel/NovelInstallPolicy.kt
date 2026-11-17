package eu.kanade.tachiyomi.extension.novel

import java.util.Collections

internal enum class NovelInstallReason {
    INCOMPLETE,
    EXPECTATION_MISMATCH,
    PACKAGE,
    SIGNATURE,
    API,
    COMPATIBILITY,
    COLLISION,
    TRUST,
    VERSION,
    SIGNER_CONTINUITY,
    SOURCE_CONTINUITY,
    STALE_STATE,
    ROTATION,
}
internal sealed interface NovelInstallDecision {
    data object Eligible : NovelInstallDecision
    class Rejected(reasons: Set<NovelInstallReason>) : NovelInstallDecision {
        val reasons: Set<NovelInstallReason> = Collections.unmodifiableSet(reasons.toSet())
    }
}

/** Policy eligibility is not installation authorization, signature verification, or Android consent. */
internal object NovelInstallPolicy {
    private val digest = Regex("[0-9a-f]{64}")
    private val knownSources = mapOf(
        "app.novel.extension.matchanovels" to "matchanovels",
        "app.novel.extension.skynovels" to "skynovels",
        "app.novel.extension.jabrascan" to "jabrascan",
    )

    fun evaluate(
        operation: NovelInstallOperation,
        expected: NovelDistributionEntry,
        candidate: NovelVerifiedCandidate?,
        installed: NovelInstalledSnapshot?,
        trust: NovelVersionTrust?,
    ): NovelInstallDecision {
        val reasons = linkedSetOf<NovelInstallReason>()
        fun check(ok: Boolean, reason: NovelInstallReason) {
            if (!ok) reasons.add(reason)
        }
        if (!NovelDistributionCatalogCodec.isValidExpectation(expected)) {
            return NovelInstallDecision.Rejected(setOf(NovelInstallReason.EXPECTATION_MISMATCH))
        }
        if (candidate == null || installed == null) {
            return NovelInstallDecision.Rejected(setOf(NovelInstallReason.INCOMPLETE))
        }

        check(candidate.packageName == expected.packageName, NovelInstallReason.PACKAGE)
        check(
            candidate.versionCode == expected.longVersionCode && candidate.versionName == expected.versionName &&
                candidate.novelApi == expected.novelApi && candidate.entryClasses == expected.entryClasses.toSet() &&
                candidate.minSdk == expected.minSdk && candidate.artifactSize == expected.size &&
                candidate.artifactSha256 == expected.sha256 &&
                (expected.abis == null || expected.abis == candidate.apkAbis) &&
                (expected.sourceIds == null || expected.sourceIds == candidate.sourceIds),
            NovelInstallReason.EXPECTATION_MISMATCH,
        )
        check(NovelSupportedApiVersions.isSupported(candidate.novelApi), NovelInstallReason.API)
        val signers = candidate.currentSigners
        check(
            signers != null && signers.isNotEmpty() && signers.all(digest::matches) &&
                candidate.signerCompleteness == NovelSignerCompleteness.COMPLETE && signers == expected.currentSigners,
            NovelInstallReason.SIGNATURE,
        )
        check(
            candidate.signerHistory != null && candidate.signerHistory.all(digest::matches),
            NovelInstallReason.INCOMPLETE,
        )
        check(candidate.rotation != NovelRotation.UNKNOWN, NovelInstallReason.INCOMPLETE)
        check(candidate.rotation != NovelRotation.DECLARED, NovelInstallReason.ROTATION)
        val sdk = candidate.deviceSdk
        val minSdk = candidate.minSdk
        val apkAbis = candidate.apkAbis
        val deviceAbis = candidate.deviceAbis
        check(
            candidate.layout == NovelApkLayout.STANDALONE &&
                sdk != null && minSdk != null && minSdk in 1..sdk && apkAbis != null && deviceAbis != null &&
                deviceAbis.isNotEmpty() && deviceAbis.none(String::isBlank) && apkAbis.none(String::isBlank) &&
                (apkAbis.isEmpty() || apkAbis.any { it in deviceAbis }) &&
                candidate.features != null && "app.novel.extension" in candidate.features &&
                "tachiyomi.extension" !in candidate.features,
            NovelInstallReason.COMPATIBILITY,
        )
        check(
            trust != null && trust.packageName == candidate.packageName && trust.versionCode == candidate.versionCode &&
                signers != null && signers.isNotEmpty() && trust.currentSigners == signers,
            NovelInstallReason.TRUST,
        )
        check(installed.freshness == NovelStateFreshness.CURRENT, NovelInstallReason.STALE_STATE)
        check(installed.packageName == candidate.packageName, NovelInstallReason.PACKAGE)
        when (operation) {
            NovelInstallOperation.NEW -> {
                check(installed.origin == NovelInstallOrigin.ABSENT, NovelInstallReason.COLLISION)
                check(
                    installed.versionCode == null && installed.currentSigners == null &&
                        installed.signerHistory == null && installed.sourceIds == null &&
                        installed.signerCompleteness == NovelSignerCompleteness.UNKNOWN,
                    NovelInstallReason.INCOMPLETE,
                )
            }
            NovelInstallOperation.UPDATE -> {
                check(installed.origin == NovelInstallOrigin.SHARED, NovelInstallReason.COLLISION)
                check(
                    installed.versionCode != null && installed.versionCode > 0 && candidate.versionCode != null &&
                        candidate.versionCode > installed.versionCode,
                    NovelInstallReason.VERSION,
                )
                check(
                    installed.signerHistory != null && installed.signerHistory.all(digest::matches),
                    NovelInstallReason.INCOMPLETE,
                )
                val oldSigners = installed.currentSigners
                check(
                    oldSigners != null && oldSigners.isNotEmpty() && oldSigners.all(digest::matches) &&
                        installed.signerCompleteness == NovelSignerCompleteness.COMPLETE && oldSigners == signers,
                    NovelInstallReason.SIGNER_CONTINUITY,
                )
                val oldSources = installed.sourceIds
                val newSources = candidate.sourceIds
                val required = knownSources[expected.packageName]
                check(
                    oldSources != null && oldSources.isNotEmpty() && oldSources.none(String::isBlank) &&
                        newSources == oldSources &&
                        (required == null || oldSources == setOf(required)),
                    NovelInstallReason.SOURCE_CONTINUITY,
                )
            }
        }
        return if (reasons.isEmpty()) NovelInstallDecision.Eligible else NovelInstallDecision.Rejected(reasons)
    }
}
