package eu.kanade.tachiyomi.extension.novel

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import java.io.File

internal object NovelExecutionBarrier {
    fun store(
        context: Context,
    ): NovelExecutionHolds = NovelExecutionHoldStore(File(context.filesDir, "s39-execution-holds"))

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
