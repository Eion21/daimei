package eu.kanade.tachiyomi.ui.browse.extension.novel

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.novel.NovelAndroidInstallBridge
import eu.kanade.tachiyomi.extension.novel.NovelArtifactCancellation
import eu.kanade.tachiyomi.extension.novel.NovelArtifactDownloadResult
import eu.kanade.tachiyomi.extension.novel.NovelArtifactDownloader
import eu.kanade.tachiyomi.extension.novel.NovelArtifactOriginPolicy
import eu.kanade.tachiyomi.extension.novel.NovelDistributionCatalog
import eu.kanade.tachiyomi.extension.novel.NovelDistributionClient
import eu.kanade.tachiyomi.extension.novel.NovelDistributionEnrollment
import eu.kanade.tachiyomi.extension.novel.NovelDistributionEntry
import eu.kanade.tachiyomi.extension.novel.NovelInstallOutcome
import eu.kanade.tachiyomi.extension.novel.NovelInstallPhase
import eu.kanade.tachiyomi.extension.novel.NovelInteractiveInstallRuntime
import eu.kanade.tachiyomi.extension.novel.NovelQuarantinedArtifact
import eu.kanade.tachiyomi.source.novel.NovelSourceAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions

internal data class NovelDistributionInstalled(val packageName: String, val version: Long, val loaded: Boolean)

internal data class NovelDistributionUiState(
    val busy: Boolean = true,
    val repositories: Set<String> = emptySet(),
    val entries: List<NovelDistributionEntry> = emptyList(),
    val installed: List<NovelDistributionInstalled> = emptyList(),
    val error: String? = null,
    val progress: String? = null,
    val preparedPackage: String? = null,
    val installEligible: Boolean = false,
    val operationId: String? = null,
    val operationPackage: String? = null,
    val operationVersion: Long? = null,
    val operationSigners: String? = null,
    val phase: NovelInstallPhase? = null,
    val execution: NovelDistributionExecutionConsent.Result? = null,
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
internal class NovelDistributionViewModel(
    context: Context,
    private val manager: ExtensionManager,
) : ViewModel() {
    private val app = context.applicationContext ?: context
    private val preferences = app.getSharedPreferences("s39-distribution", Context.MODE_PRIVATE)
    private val metadata = NovelDistributionClient()
    val state = MutableStateFlow(NovelDistributionUiState())
    private var prepared: Pair<NovelDistributionEntry, NovelQuarantinedArtifact>? = null
    @Volatile private var cancellation: NovelArtifactCancellation? = null
    @Volatile private var downloader: NovelArtifactDownloader? = null
    val experimental: Boolean = NovelAndroidInstallBridge.forQaExperiment(app).controlledTestActivation

    private val enrollment = NovelDistributionEnrollment(object : NovelDistributionEnrollment.Backend {
        override fun enrollAndFetch(repositoryUrl: String): NovelDistributionCatalog {
            val saved = preferences.getStringSet("repositories", emptySet()).orEmpty().toSet() + repositoryUrl
            preferences.edit().putStringSet("repositories", saved).apply()
            state.update { it.copy(repositories = saved, entries = emptyList()) }
            return metadata.fetch(repositoryUrl)
        }

        override fun downloadAndStage(entry: NovelDistributionEntry, approvedOrigin: String) {
            val token = requireNotNull(cancellation)
            val root = NovelInteractiveInstallRuntime.quarantineRoot(app)
            if (!Files.exists(root, NOFOLLOW_LINKS)) {
                Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            }
            NovelArtifactDownloader(root, NovelArtifactOriginPolicy.publicHttps(setOf(approvedOrigin))).use { transfer ->
                downloader = transfer
                when (val result = transfer.download(entry, token) { progress ->
                    state.update { it.copy(progress = "${progress.receivedBytes} / ${progress.expectedBytes}") }
                }) {
                    is NovelArtifactDownloadResult.Success -> {
                        prepared = entry to result.artifact
                        state.update { it.copy(preparedPackage = entry.packageName, installEligible = approvedFixture(entry)) }
                    }
                    is NovelArtifactDownloadResult.Failure -> state.update { it.copy(error = result.reason.name) }
                }
            }
            downloader = null
        }
    })

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                state.update { it.copy(repositories = preferences.getStringSet("repositories", emptySet()).orEmpty().toSet()) }
                preferences.getString("last_operation", null)?.let { restore ->
                    updateOutcome(NovelInteractiveInstallRuntime.status(app, restore))
                }
            } catch (_: Exception) {
                state.update { it.copy(error = "RECOVERY_REQUIRED") }
            } finally {
                state.update { it.copy(busy = false) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            combine(manager.loadedExtensionsFlow, manager.notLoadedExtensionsFlow) { _, _ -> Unit }
                .collect {
                    val loaded = manager.getLoadedExtensions()
                    val held = manager.getNotLoadedExtensions()
                    NovelInteractiveInstallRuntime.observeLoaded(loaded)
                    val rows = loaded.filter { it.sources.any { source -> source is NovelSourceAdapter } }
                        .map { NovelDistributionInstalled(it.pkgName, it.versionCode, true) } +
                        held.filter { isNovel(it.pkgName) }.map { NovelDistributionInstalled(it.pkgName, it.versionCode, false) }
                    state.update { it.copy(installed = rows.distinctBy { row -> row.packageName }.sortedBy { row -> row.packageName }) }
                }
        }
    }

    @Suppress("DEPRECATION")
    private fun isNovel(packageName: String): Boolean = try {
        val features = app.packageManager.getPackageInfo(packageName, PackageManager.GET_CONFIGURATIONS)
            .reqFeatures?.mapNotNull { it.name }.orEmpty()
        "app.novel.extension" in features && "tachiyomi.extension" !in features
    } catch (_: Exception) {
        false
    }

    private fun runAction(action: suspend () -> Unit) {
        if (state.value.busy) return
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Never expose URLs, provider paths, headers or exception bodies in the UI.
                state.update { it.copy(error = error.javaClass.simpleName) }
            } finally {
                state.update { it.copy(busy = false) }
            }
        }
    }

    fun enroll(url: String, explicitConsent: Boolean) = runAction {
        when (val result = enrollment.enrollRepository(url, explicitConsent)) {
            is NovelDistributionEnrollment.Result.Catalog -> state.update { it.copy(entries = result.value.entries) }
            else -> state.update { it.copy(error = "CONSENT_OR_URL_REJECTED") }
        }
    }

    private fun pendingOperation(): Boolean = state.value.phase in setOf(
        NovelInstallPhase.PREPARING, NovelInstallPhase.PERMISSION_REQUIRED, NovelInstallPhase.STAGED,
        NovelInstallPhase.COMMITTING, NovelInstallPhase.PENDING_USER_ACTION,
    )

    private fun approvedFixture(entry: NovelDistributionEntry): Boolean = experimental &&
        entry.packageName == "app.novel.extension.fixtures" && entry.sha256 == BuildConfig.S39_FIXTURE_APK_SHA256 &&
        entry.currentSigners == BuildConfig.S39_FIXTURE_SIGNERS.split(',').filter(String::isNotEmpty).toSet()

    fun download(entry: NovelDistributionEntry, approvedOrigin: String?) {
        if (state.value.busy || pendingOperation()) return
        cancellation = NovelArtifactCancellation()
        runAction {
            try {
                if (enrollment.install(entry, approvedOrigin) == NovelDistributionEnrollment.Result.Denied) {
                    state.update { it.copy(error = "ORIGIN_CONSENT_REQUIRED") }
                }
            } finally {
                cancellation = null
                state.update { it.copy(progress = null) }
            }
        }
    }

    fun cancelDownload() { cancellation?.cancel() }

    /** Download consent never submits a session. Installation requires this separate user action. */
    fun installPrepared(explicitConsent: Boolean) = runAction {
        if (!explicitConsent || pendingOperation()) return@runAction
        val (entry, artifact) = prepared ?: return@runAction
        if (!approvedFixture(entry)) {
            state.update { it.copy(error = "PRODUCTION_DISABLED") }
            return@runAction
        }
        val coordinator = NovelInteractiveInstallRuntime.get(app)
        var outcome = coordinator.begin(artifact, entry, true)
        if (outcome.phase == NovelInstallPhase.STAGED) outcome = coordinator.submit(requireNotNull(outcome.operationId))
        updateOutcome(outcome)
    }

    fun refresh() = runAction {
        state.value.operationId?.let { updateOutcome(NovelInteractiveInstallRuntime.status(app, it)) }
    }

    fun resumePermission() = runAction {
        state.value.operationId?.let { updateOutcome(NovelInteractiveInstallRuntime.get(app).resumePermission(it, true)) }
        if (state.value.phase == NovelInstallPhase.STAGED) {
            state.value.operationId?.let { updateOutcome(NovelInteractiveInstallRuntime.get(app).submit(it)) }
        }
    }

    /** System UI is launched only in direct response to this click, never by a receiver or refresh. */
    fun confirmAndroid() {
        if (state.value.busy) return
        val operation = state.value.operationId ?: return
        updateOutcome(NovelInteractiveInstallRuntime.get(app).confirmUserAction(operation, true))
    }

    fun openPermissionSettings() {
        if (!experimental || state.value.phase != NovelInstallPhase.PERMISSION_REQUIRED) return
        app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun authorizeExecution(operation: String, explicitConsent: Boolean) = runAction {
        if (operation != state.value.operationId) return@runAction
        val record = NovelInteractiveInstallRuntime.recordedRequest(app, operation) ?: return@runAction
        val consent = NovelDistributionExecutionConsent(
            grant = { op, pkg, version -> NovelInteractiveInstallRuntime.authorizeExecution(app, op, pkg, version) },
            trust = { pkg, version ->
                val extension = manager.getNotLoadedExtensions().firstOrNull { it.pkgName == pkg && it.versionCode == version }
                if (extension?.reason is Extension.NotLoaded.Reason.Untrusted) {
                    manager.trust(extension)
                    true
                } else {
                    manager.getLoadedExtensions().any { it.pkgName == pkg && it.versionCode == version &&
                        it.sources.any { source -> source is NovelSourceAdapter } }
                }
            },
            reload = { pkg, version ->
                manager.reloadNovelAfterExecutionConsent(pkg, version).also {
                    NovelInteractiveInstallRuntime.observeLoaded(manager.getLoadedExtensions())
                }
            },
        )
        val result = consent.authorize(operation, record.packageName, record.versionCode, explicitConsent)
        state.update { it.copy(execution = result) }
        updateOutcome(NovelInteractiveInstallRuntime.status(app, operation))
    }

    private fun updateOutcome(outcome: NovelInstallOutcome) {
        if (outcome.operationId == null) {
            state.update { it.copy(error = outcome.failure?.name,
                phase = if (it.operationId == null) outcome.phase else it.phase) }
            return
        }
        val record = outcome.operationId?.let { NovelInteractiveInstallRuntime.recordedRequest(app, it) }
        outcome.operationId?.let { preferences.edit().putString("last_operation", it).apply() }
        state.update { it.copy(operationId = outcome.operationId, operationPackage = record?.packageName,
            operationVersion = record?.versionCode, operationSigners = record?.currentSigners?.sorted()?.joinToString("\n"),
            phase = outcome.phase, error = outcome.failure?.name,
            execution = when {
                it.operationId != outcome.operationId || outcome.phase !in setOf(
                    NovelInstallPhase.INSTALLED_HELD, NovelInstallPhase.INSTALLED_UNRECOGNIZED,
                ) -> null
                outcome.phase == NovelInstallPhase.INSTALLED_UNRECOGNIZED &&
                    it.execution == NovelDistributionExecutionConsent.Result.RECOGNIZED -> NovelDistributionExecutionConsent.Result.UNRECOGNIZED
                else -> it.execution
            }) }
    }

    override fun onCleared() {
        cancellation?.cancel()
        downloader?.close()
        super.onCleared()
    }
}
