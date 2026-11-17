package eu.kanade.tachiyomi.extension.novel

import android.content.Context
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.novel.NovelSourceAdapter
import java.nio.file.Path

/** Only a pinned s39Qa build can activate its isolated installer experiment. */
internal object NovelInteractiveInstallRuntime {
    private var coordinator: NovelInteractiveInstallCoordinator? = null
    @Volatile private var recognized = emptySet<Pair<String, Long>>()

    // Normalize the trusted framework directory, never a potentially replaced quarantine/journal leaf.
    fun quarantineRoot(context: Context): Path = context.filesDir.canonicalFile.resolve("s39-quarantine").toPath()
    private fun journal(context: Context) = NovelFileInstallJournal(context.filesDir.canonicalFile.resolve("s39-install-journal").toPath())

    /** Expected request metadata only; callers must still obtain a fresh coordinator grant. */
    fun recordedRequest(context: Context, operationId: String): NovelInstallRecord? =
        (journal(context).read(operationId) as? NovelInstallJournalRead.Found)?.record

    /** Snapshot only; this must never load or instantiate extension code. */
    fun observeLoaded(extensions: List<Extension.Loaded>) {
        recognized = extensions.filter { it.sources.any { source -> source is NovelSourceAdapter } }
            .map { it.pkgName to it.versionCode }.toSet()
    }

    fun status(context: Context, operationId: String): NovelInstallOutcome {
        val record = (journal(context).read(operationId) as? NovelInstallJournalRead.Found)?.record
            ?: return NovelInstallOutcome(operationId, NovelInstallPhase.RECOVERY_REQUIRED, NovelInstallFailure.JOURNAL)
        return if (record.androidResult == NovelInstallCallbackStatus.SUCCESS) {
            get(context).reconcile(operationId)
        } else {
            NovelInstallOutcome(operationId, record.phase, record.failure)
        }
    }

    fun authorizeExecution(
        context: Context,
        operationId: String,
        packageName: String,
        versionCode: Long,
    ): NovelExecutionConsentOutcome {
        val record = (journal(context).read(operationId) as? NovelInstallJournalRead.Found)?.record
            ?: return NovelExecutionConsentOutcome.DENIED
        if (record.packageName != packageName || record.versionCode != versionCode) return NovelExecutionConsentOutcome.DENIED
        return get(context).authorizeExecution(operationId, true)
    }

    @Synchronized
    fun get(context: Context): NovelInteractiveInstallCoordinator {
        coordinator?.let { return it }
        val app = context.applicationContext ?: context
        val inspector = NovelApkInspector(app, quarantineRoot(app))
        return NovelInteractiveInstallCoordinator(
            inspector::inspect,
            NovelInstalledPackageReader(app),
            NovelExecutionBarrier.store(app),
            journal(app),
            NovelAndroidInstallBridge.forHost(app),
            NovelInstallRecognition { packageName, versionCode -> packageName to versionCode in recognized },
        ).also { coordinator = it }
    }
}
