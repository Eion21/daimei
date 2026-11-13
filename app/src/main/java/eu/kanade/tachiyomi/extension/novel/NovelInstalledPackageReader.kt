package eu.kanade.tachiyomi.extension.novel

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

internal sealed interface NovelPackageLookup {
    data object NotFound : NovelPackageLookup
    data object Unavailable : NovelPackageLookup
    data class Found(val facts: NovelInstalledPackageFacts) : NovelPackageLookup
}

internal enum class NovelPrivatePresence { PRESENT, ABSENT, UNAVAILABLE }

/** Observations only: this does not grant trust or attest source identities. */
internal class NovelInstalledPackageReader(
    private val lookup: (String) -> NovelPackageLookup,
    private val privatePresence: (String) -> NovelPrivatePresence,
) : NovelInstalledPackages {
    constructor(context: Context, sdk: Int = Build.VERSION.SDK_INT) : this(
        lookup = { name -> androidLookup(context, sdk, name) },
        privatePresence = { name -> androidPrivatePresence(context, name) },
    )

    override fun read(packageName: String): NovelInstalledRead {
        if (!packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+"))) {
            return NovelInstalledRead.Unavailable
        }
        return try {
            when (privatePresence(packageName)) {
                NovelPrivatePresence.PRESENT -> NovelInstalledRead.Private(packageName)
                NovelPrivatePresence.UNAVAILABLE -> NovelInstalledRead.Unavailable
                NovelPrivatePresence.ABSENT -> when (val found = lookup(packageName)) {
                    NovelPackageLookup.NotFound -> NovelInstalledRead.Absent
                    NovelPackageLookup.Unavailable -> NovelInstalledRead.Unavailable
                    is NovelPackageLookup.Found -> if (
                        found.facts.packageName == packageName && found.facts.longVersionCode > 0 &&
                        found.facts.versionName.isNotBlank() && found.facts.origin == NovelInstallOrigin.SHARED &&
                        found.facts.signers.observation != NovelSignerObservation.UNAVAILABLE
                    ) {
                        NovelInstalledRead.Present(found.facts)
                    } else {
                        NovelInstalledRead.Unavailable
                    }
                }
            }
        } catch (_: Exception) {
            NovelInstalledRead.Unavailable
        }
    }
}

@Suppress("DEPRECATION")
private fun androidLookup(context: Context, sdk: Int, packageName: String): NovelPackageLookup = try {
    val flags = if (sdk >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val info = context.packageManager.getPackageInfo(packageName, flags)
    val signers = NovelPackageSigningFacts.read(info, sdk)
    val version = if (sdk >= 28) info.longVersionCode else info.versionCode.toLong() and 0xffffffffL
    NovelPackageLookup.Found(
        NovelInstalledPackageFacts(
            info.packageName,
            version,
            info.versionName ?: "",
            signers,
            split = !info.splitNames.isNullOrEmpty(),
        ),
    )
} catch (_: PackageManager.NameNotFoundException) {
    // Android 11+ may hide packages unless broad package visibility is granted.
    if (sdk < 30 ||
        context.checkSelfPermission(android.Manifest.permission.QUERY_ALL_PACKAGES) == PackageManager.PERMISSION_GRANTED
    ) {
        NovelPackageLookup.NotFound
    } else {
        NovelPackageLookup.Unavailable
    }
} catch (_: Exception) {
    NovelPackageLookup.Unavailable
}

private fun androidPrivatePresence(context: Context, packageName: String): NovelPrivatePresence = try {
    // Mirrors ExtensionLoader's exact private pathname without parsing or loading the file.
    val directory = File(context.filesDir, "exts").toPath()
    val path = directory.resolve("$packageName.ext")
    if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
        NovelPrivatePresence.ABSENT
    } else if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        NovelPrivatePresence.UNAVAILABLE
    } else if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        NovelPrivatePresence.PRESENT
    } else if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
        NovelPrivatePresence.ABSENT
    } else {
        NovelPrivatePresence.UNAVAILABLE
    }
} catch (_: Exception) {
    NovelPrivatePresence.UNAVAILABLE
}
