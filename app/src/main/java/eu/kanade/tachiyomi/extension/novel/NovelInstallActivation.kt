package eu.kanade.tachiyomi.extension.novel

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Real explicit public activation for the official Daimei host.
 *
 * This is not a QA boolean. It is active only when the running host package is the
 * official `app.daimei` application and its complete current signing identity is exactly
 * the pinned official host certificate — a singleton set observed through modern complete
 * signer facts. Unsigned builds therefore cannot claim the official host signer, and
 * multi-signer, rotated, legacy or unavailable host identities all fail closed.
 *
 * When active, installation is constrained to the three official extension
 * packages signed with the pinned extensions certificate. The bridge still relies
 * on the coordinator for schema-2 catalog validation, APK inspection and the
 * execution-hold lease.
 */
internal class NovelInstallActivation private constructor(
    private val activeProvider: () -> Boolean,
    private val officialExtensionSigner: String,
    private val supportedPackages: Set<String>,
) {
    private val hex = Regex("[0-9a-f]{64}")

    val active: Boolean get() = activeProvider()

    fun permits(record: NovelInstallRecord): Boolean {
        if (!active) return false
        if (!record.priorAbsent || !record.explicitInstallConsent) return false
        if (record.packageName !in supportedPackages) return false
        if (record.currentSigners != setOf(officialExtensionSigner)) return false
        if (record.artifactSize !in 1..NovelDistributionCatalogCodec.MAX_APK_BYTES) return false
        if (!hex.matches(record.artifactSha256)) return false
        return true
    }

    /**
     * Visible for testing only. Production code must use [official] to bind Android identity.
     */
    internal constructor(
        active: Boolean,
        officialExtensionSigner: String,
        supportedPackages: Set<String>,
    ) : this({ active }, officialExtensionSigner, supportedPackages)

    companion object {
        private const val OFFICIAL_HOST = "app.daimei"
        // SHA-256 digest of the official Daimei signing certificate.
        private const val HOST_SIGNER = "e665aa10b5ad8c1d5c3f993a3e733687ff0ef066da465706a69c7635b0652d77"
        // SHA-256 digest of the official extensions signing certificate.
        private const val EXTENSION_SIGNER = "2782d342e313fe5cb3ca81996f084661a4eb21bbf11e139d8c3913edacf75408"
        private val OFFICIAL_PACKAGES = setOf(
            "app.novel.extension.matchanovels",
            "app.novel.extension.skynovels",
            "app.novel.extension.jabrascan",
        )

        fun official(context: Context): NovelInstallActivation {
            val app = context.applicationContext ?: context
            val active = runCatching {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                } else {
                    @Suppress("DEPRECATION")
                    app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES)
                }
                isOfficialHostIdentity(info.packageName, NovelPackageSigningFacts.read(info, Build.VERSION.SDK_INT))
            }.getOrDefault(false)
            return NovelInstallActivation(active, EXTENSION_SIGNER, OFFICIAL_PACKAGES)
        }

        /**
         * The production host gate. Pure so the decision is testable without a PackageManager: [official]
         * reads the platform facts and applies exactly this rule.
         *
         * The official host is signed by exactly one current certificate: the pinned singleton. A host that
         * merely includes the pin among several signers, one whose pin survives only in its rotation history,
         * or a device that exposes no modern complete facts (Android 26/27 legacy signatures, or an
         * unavailable read) is not the official host. There is no forged-complete fallback: the gate
         * fails closed everywhere the complete current signer set cannot be observed.
         */
        internal fun isOfficialHostIdentity(packageName: String, signers: NovelPackageSigners): Boolean =
            packageName == OFFICIAL_HOST &&
                signers.observation == NovelSignerObservation.MODERN_COMPLETE &&
                signers.hasCompleteCurrent &&
                signers.current == setOf(HOST_SIGNER)
    }
}
