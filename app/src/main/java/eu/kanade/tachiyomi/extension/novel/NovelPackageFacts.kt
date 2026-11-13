package eu.kanade.tachiyomi.extension.novel

import android.content.pm.PackageInfo
import java.security.MessageDigest
import java.util.Collections

/** Platform observations only, never Stage1 verified evidence or publisher authentication. */
internal enum class NovelSignerObservation { MODERN_COMPLETE, LEGACY_UNVERIFIED, UNAVAILABLE }

internal class NovelPackageSigners(
    val observation: NovelSignerObservation,
    current: Set<String> = emptySet(),
    history: Set<String> = emptySet(),
    legacy: Set<String> = emptySet(),
) {
    val current: Set<String> = Collections.unmodifiableSet(current.toSet())
    val history: Set<String> = Collections.unmodifiableSet(history.toSet())
    val legacy: Set<String> = Collections.unmodifiableSet(legacy.toSet())

    val hasCompleteCurrent: Boolean
        get() = observation == NovelSignerObservation.MODERN_COMPLETE && current.isNotEmpty() &&
            current.size <= 16 && current.all { it.matches(Regex("[0-9a-f]{64}")) }
}

internal data class NovelInstalledPackageFacts(
    val packageName: String,
    val longVersionCode: Long,
    val versionName: String,
    val signers: NovelPackageSigners,
    val origin: NovelInstallOrigin = NovelInstallOrigin.SHARED,
    val split: Boolean = false,
)

internal sealed interface NovelInstalledRead {
    data object Absent : NovelInstalledRead
    data class Present(val facts: NovelInstalledPackageFacts) : NovelInstalledRead
    data class Private(val packageName: String) : NovelInstalledRead
    data object Unavailable : NovelInstalledRead
}

internal fun interface NovelInstalledPackages {
    fun read(packageName: String): NovelInstalledRead
}

/** Shared by the installed reader and the pre-classloader S39 hold; never unions history with current. */
internal object NovelPackageSigningFacts {
    @Suppress("DEPRECATION")
    fun read(info: PackageInfo, sdk: Int): NovelPackageSigners = try {
        if (sdk < 28) {
            val legacy = info.signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet()
            NovelPackageSigners(NovelSignerObservation.LEGACY_UNVERIFIED, legacy = legacy)
        } else {
            val signing = info.signingInfo
            val signatures = signing?.apkContentsSigners.orEmpty()
            val current = signatures.map { signature ->
                val bytes = signature.toByteArray()
                require(bytes.isNotEmpty())
                sha256(bytes)
            }.toSet()
            require(signing != null && current.isNotEmpty() && current.size <= 16 && current.size == signatures.size)
            require(signing.hasMultipleSigners() == (current.size > 1))
            val history = signing.signingCertificateHistory.orEmpty().map { sha256(it.toByteArray()) }.toSet()
            NovelPackageSigners(NovelSignerObservation.MODERN_COMPLETE, current, history)
        }
    } catch (_: Exception) {
        NovelPackageSigners(NovelSignerObservation.UNAVAILABLE)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Additional isolation, independent of all existing trust preferences and store-key matching. */
internal interface NovelExecutionHolds {
    fun enroll(
        operationId: String,
        packageName: String,
        versionCode: Long,
        currentSigners: Set<String>,
        artifactSha256: String,
    ): Boolean

    fun reconcile(operationId: String, installed: NovelInstalledPackageFacts): Boolean

    fun grantExecution(operationId: String, installed: NovelInstalledPackageFacts, explicitConsent: Boolean): Boolean

    fun permits(packageName: String, versionCode: Long, signers: NovelPackageSigners): Boolean
}
