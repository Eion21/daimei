package eu.kanade.tachiyomi.extension.novel

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import java.io.File

internal object NovelExecutionBarrier {
    fun store(
        context: Context,
    ): NovelExecutionHolds = NovelExecutionHoldStore(File(context.filesDir, "s39-execution-holds"))

    /**
     * Dynamic lease check handed to a retained [eu.kanade.tachiyomi.source.novel.NovelSourceAdapter] after the
     * loader gate. The platform identity is re-read on every call, so a revoked lease — or a restart over a
     * durable DENY — blocks references that were already held when the revocation happened. A package the
     * hold store has no record for answers permitted: those sources stay decided by the loader gate alone,
     * exactly as before.
     */
    fun retainedSourceGuard(context: Context, info: PackageInfo, isShared: Boolean): () -> Boolean = {
        permits(context, info, isShared)
    }

    @Suppress("DEPRECATION")
    fun permits(
        context: Context,
        info: PackageInfo,
        isShared: Boolean,
        sdk: Int = Build.VERSION.SDK_INT,
        epoch: NovelExecutionEpoch = NovelExecutionHoldStore.processEpoch,
    ): Boolean = try {
        val version = if (sdk >= 28) info.longVersionCode else info.versionCode.toLong()
        NovelExecutionHoldStore(File(context.filesDir, "s39-execution-holds"), epoch)
            .permits(info.packageName, version, NovelPackageSigningFacts.read(info, sdk), isShared)
    } catch (_: Exception) {
        false
    }
}
