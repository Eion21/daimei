package eu.kanade.tachiyomi.extension.novel

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Untrusted PackageManager declarations, not authenticated APK signer evidence. */
internal data class NovelArchiveMetadata(
    val packageName: String,
    val longVersionCode: Long,
    val versionName: String,
    val novelApi: String,
    val entryClasses: List<String>,
    val minSdk: Int,
    val features: Set<String>,
    val split: Boolean,
    val signers: NovelPackageSigners,
)
internal fun interface NovelArchiveMetadataPort {
    fun read(path: Path): NovelArchiveMetadata?
}
internal enum class NovelSignatureVerification { PENDING }
internal enum class NovelInspectionError {
    INVALID_EXPECTATION,
    UNOWNED,
    INTEGRITY,
    INVALID_ZIP,
    METADATA,
    MISMATCH,
    UNSUPPORTED,
    SPLIT,
    MALFORMED_MANIFEST,
    UNKNOWN_LAYOUT,
    IDENTITY_CONFLICT,
}
internal sealed interface NovelInspectionResult {
    data class Observed(
        val metadata: NovelArchiveMetadata,
        val layout: NovelStructuralLayout.Base,
        val signatureVerification: NovelSignatureVerification = NovelSignatureVerification.PENDING,
    ) : NovelInspectionResult
    data class Rejected(val reason: NovelInspectionError) : NovelInspectionResult
}

/** Inert byte and metadata observation; never issues Stage1 evidence. */
internal class NovelApkInspector(
    private val privateRoot: Path,
    private val deviceSdk: Int,
    private val metadata: NovelArchiveMetadataPort,
) {
    constructor(context: Context, privateRoot: Path, deviceSdk: Int = Build.VERSION.SDK_INT) : this(
        privateRoot,
        deviceSdk,
        NovelArchiveMetadataPort { androidArchive(context, deviceSdk, it) },
    )

    fun inspect(artifact: NovelQuarantinedArtifact, expected: NovelDistributionEntry): NovelInspectionResult {
        fun reject(reason: NovelInspectionError) = NovelInspectionResult.Rejected(reason)
        try {
            if (!NovelDistributionCatalogCodec.isValidExpectation(
                    expected,
                )
            ) {
                return reject(NovelInspectionError.INVALID_EXPECTATION)
            }
            if (!owned(artifact.path)) return reject(NovelInspectionError.UNOWNED)
            if (!matches(artifact, expected)) return reject(NovelInspectionError.INTEGRITY)
            if (!validZip(artifact.path)) return reject(NovelInspectionError.INVALID_ZIP)
            val layout = NovelApkLayoutInspector.inspect(artifact.path)
            if (layout == NovelStructuralLayout.Malformed) return reject(NovelInspectionError.MALFORMED_MANIFEST)
            if (layout == NovelStructuralLayout.Unknown) return reject(NovelInspectionError.UNKNOWN_LAYOUT)
            val observed = try {
                metadata.read(artifact.path)
            } catch (_: Exception) {
                null
            }
            if (!owned(artifact.path) || !matches(artifact, expected)) return reject(NovelInspectionError.INTEGRITY)
            if (observed == null) return reject(NovelInspectionError.METADATA)
            val manifestPackage = when (layout) {
                is NovelStructuralLayout.Base -> layout.packageName
                is NovelStructuralLayout.Split -> layout.packageName
                else -> return reject(NovelInspectionError.UNKNOWN_LAYOUT)
            }
            if (manifestPackage != expected.packageName || manifestPackage != observed.packageName) {
                return reject(NovelInspectionError.IDENTITY_CONFLICT)
            }
            if (observed.split || layout is NovelStructuralLayout.Split) return reject(NovelInspectionError.SPLIT)
            if (!NovelSupportedApiVersions.isSupported(observed.novelApi) || observed.minSdk > deviceSdk ||
                deviceSdk < 26
            ) {
                return reject(NovelInspectionError.UNSUPPORTED)
            }
            if (observed.packageName != expected.packageName || observed.longVersionCode != expected.longVersionCode ||
                observed.versionName != expected.versionName || observed.novelApi != expected.novelApi ||
                observed.entryClasses != expected.entryClasses || observed.minSdk != expected.minSdk ||
                "app.novel.extension" !in observed.features || "tachiyomi.extension" in observed.features
            ) {
                return reject(NovelInspectionError.MISMATCH)
            }
            return NovelInspectionResult.Observed(observed, layout as NovelStructuralLayout.Base)
        } catch (_: Exception) {
            return reject(NovelInspectionError.UNOWNED)
        }
    }

    private fun owned(path: Path): Boolean {
        val root = privateRoot.toAbsolutePath().normalize()
        val file = path.toAbsolutePath().normalize()
        val parent = file.parent ?: return false
        val forbidden =
            setOf(
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE,
            )
        return path.isAbsolute && path == file && parent.parent == root && parent.fileName.toString().startsWith(
            "s39-",
        ) &&
            file.fileName.toString().startsWith("artifact-") && file.fileName.toString().endsWith(".quarantine") &&
            Files.isDirectory(root, NOFOLLOW_LINKS) && root.toRealPath() == root &&
            Files.getPosixFilePermissions(root, NOFOLLOW_LINKS).none { it in forbidden } &&
            Files.isDirectory(parent, NOFOLLOW_LINKS) && parent.toRealPath() == parent &&
            Files.getPosixFilePermissions(parent, NOFOLLOW_LINKS).none { it in forbidden } &&
            Files.isRegularFile(file, NOFOLLOW_LINKS) && file.toRealPath() == file
    }

    private fun matches(artifact: NovelQuarantinedArtifact, expected: NovelDistributionEntry): Boolean {
        val integrity = artifact.integrity
        if (integrity.expectedSize != expected.size || integrity.observedSize != expected.size ||
            integrity.expectedSha256 != expected.sha256 || integrity.observedSha256 != expected.sha256
        ) {
            return false
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        Files.newInputStream(artifact.path, StandardOpenOption.READ, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                size += n
                if (size > expected.size || size > NovelDistributionCatalogCodec.MAX_APK_BYTES) return false
                digest.update(buffer, 0, n)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return size == expected.size && hash == expected.sha256
    }

    private fun validZip(path: Path): Boolean = try {
        ZipFile(path.toFile()).use { zip ->
            val entries = zip.entries()
            var manifests = 0
            var inflated = 0L
            val buffer = ByteArray(8192)
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name == "AndroidManifest.xml" && !entry.isDirectory) manifests++
                zip.getInputStream(entry).use { stream ->
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        inflated += n
                        if (inflated > NovelDistributionCatalogCodec.MAX_APK_BYTES) return false
                    }
                }
            }
            manifests == 1
        }
    } catch (_: Exception) {
        false
    }
}

@Suppress("DEPRECATION")
private fun androidArchive(context: Context, sdk: Int, path: Path): NovelArchiveMetadata? {
    val flags = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS or
        (if (sdk >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
    val info = context.packageManager.getPackageArchiveInfo(path.toString(), flags) ?: return null
    val app = info.applicationInfo ?: return null
    val bundle = app.metaData ?: return null
    val pkg = info.packageName ?: return null
    val rawApi = bundle.getFloat("app.novel.extensionApi")
    if (rawApi == 0f || !rawApi.isFinite()) return null
    val classes = bundle.getString("app.novel.extension.class")?.split(';')?.map {
        val name = it.trim()
        if (name.startsWith('.')) pkg + name else name
    } ?: return null
    return NovelArchiveMetadata(
        pkg, if (sdk >= 28) info.longVersionCode else info.versionCode.toLong() and 0xffffffffL,
        info.versionName ?: return null, rawApi.toString(), classes, app.minSdkVersion,
        info.reqFeatures.orEmpty().mapNotNull { it.name }.toSet(),
        !info.splitNames.isNullOrEmpty() || !app.splitSourceDirs.isNullOrEmpty(),
        NovelPackageSigningFacts.read(info, sdk),
    )
}
