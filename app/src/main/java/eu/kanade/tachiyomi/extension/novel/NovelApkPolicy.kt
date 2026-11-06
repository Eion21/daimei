package eu.kanade.tachiyomi.extension.novel

import eu.kanade.tachiyomi.extension.util.isSupportedNovelApiVersion
import java.security.MessageDigest

/** Observations must eventually come from independent platform APK inspection, never catalog JSON. */
data class NovelApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val novelApi: String,
    val entryClass: String,
    val minSdk: Int,
    val features: Set<String>,
    val signatureVerified: Boolean,
    val signerSha256: Set<String>,
)

data class NovelInstalledIdentity(val packageName: String, val versionCode: Long, val signerSha256: Set<String>)

enum class NovelApkFinding {
    HOST_INCOMPATIBLE,
    NO_DOWNLOAD_URL,
    PACKAGE_IDENTITY,
    HASH,
    SIZE,
    METADATA,
    NOVEL_API,
    NOVEL_KIND,
    SIGNATURE,
    TRUST,
    INSTALLED_SIGNER,
    VERSION,
    INTERACTIVE_REQUIRED,
    PACKAGE_CONTEXT_REQUIRED,
}

data class NovelApkPreflight(val findings: Set<NovelApkFinding>) {
    val canInstall: Boolean get() = false
}

object NovelApkPolicy {
    fun inspect(
        entry: NovelCatalogEntry,
        bytes: ByteArray,
        apk: NovelApkIdentity,
        deviceSdk: Int,
        explicitlyTrusted: Boolean,
        interactive: Boolean,
        packageContextAvailable: Boolean,
        installed: NovelInstalledIdentity? = null,
    ): NovelApkPreflight {
        val findings = linkedSetOf(NovelApkFinding.HOST_INCOMPATIBLE, NovelApkFinding.NO_DOWNLOAD_URL)
        fun check(condition: Boolean, finding: NovelApkFinding) {
            if (!condition) findings.add(finding)
        }
        check(apk.packageName == entry.id, NovelApkFinding.PACKAGE_IDENTITY)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(digest == entry.artifact.sha256, NovelApkFinding.HASH)
        check(
            bytes.size.toLong() == entry.artifact.size && entry.artifact.size in 1..NovelCatalogCodec.MAX_APK_BYTES,
            NovelApkFinding.SIZE,
        )
        check(
            apk.versionCode == entry.versionCode && apk.versionName == entry.versionName &&
                apk.entryClass == entry.entryClass && apk.minSdk == entry.minSdk && apk.minSdk in 26..deviceSdk,
            NovelApkFinding.METADATA,
        )
        check(
            apk.novelApi == entry.novelApi && apk.novelApi.toDoubleOrNull()?.let(::isSupportedNovelApiVersion) == true,
            NovelApkFinding.NOVEL_API,
        )
        check(
            "app.novel.extension" in apk.features && "tachiyomi.extension" !in apk.features,
            NovelApkFinding.NOVEL_KIND,
        )
        check(
            apk.signatureVerified && apk.signerSha256.isNotEmpty() &&
                apk.signerSha256.all { Regex("[0-9a-f]{64}").matches(it) } &&
                apk.signerSha256 == entry.signing.certificateSha256.toSet(),
            NovelApkFinding.SIGNATURE,
        )
        check(explicitlyTrusted, NovelApkFinding.TRUST)
        check(interactive, NovelApkFinding.INTERACTIVE_REQUIRED)
        check(packageContextAvailable, NovelApkFinding.PACKAGE_CONTEXT_REQUIRED)
        installed?.let {
            check(it.packageName == apk.packageName, NovelApkFinding.PACKAGE_IDENTITY)
            check(it.versionCode < apk.versionCode, NovelApkFinding.VERSION)
            check(it.signerSha256 == apk.signerSha256, NovelApkFinding.INSTALLED_SIGNER)
        }
        return NovelApkPreflight(findings)
    }
}

/** No downloader, installer, private loader, trust writer or success continuation is accepted here. */
object NovelRemoteActions {
    private const val MESSAGE = "Remote APK actions are disabled in S38 (hostCompatible: false)."

    fun download(packageName: String): Nothing = throw SecurityException(MESSAGE)
    fun install(packageName: String): Nothing = throw SecurityException(MESSAGE)
    fun update(packageName: String): Nothing = throw SecurityException(MESSAGE)
}
