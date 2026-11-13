package eu.kanade.tachiyomi.extension.novel

import android.content.Context
import java.nio.file.Path

/** Production wiring is permanently cold. No catalog action, flag or installer preference enables it. */
internal object NovelInteractiveInstallRuntime {
    private var coordinator: NovelInteractiveInstallCoordinator? = null

    fun quarantineRoot(context: Context): Path = context.filesDir.resolve("s39-quarantine").toPath()

    @Synchronized
    fun get(context: Context): NovelInteractiveInstallCoordinator {
        coordinator?.let { return it }
        val app = context.applicationContext ?: context
        val inspector = NovelApkInspector(app, quarantineRoot(app))
        return NovelInteractiveInstallCoordinator(
            inspector::inspect,
            NovelInstalledPackageReader(app),
            NovelExecutionBarrier.store(app),
            NovelFileInstallJournal(app.filesDir.resolve("s39-install-journal").toPath()),
            NovelAndroidInstallBridge(app),
            // Until host recognition is actually observed, success is always explicitly unrecognized.
            NovelInstallRecognition { _, _ -> false },
        ).also { coordinator = it }
    }
}
