package eu.kanade.tachiyomi.ui.browse.extension.novel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.extension.novel.NovelDistributionEntry
import eu.kanade.tachiyomi.extension.novel.NovelInstallPhase
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

class NovelDistributionScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = metroViewModel<NovelDistributionViewModel>()
        val state by model.state.collectAsStateWithLifecycle()
        var url by remember { mutableStateOf("") }
        var repositoryConsent by remember { mutableStateOf<String?>(null) }
        var artifactConsent by remember { mutableStateOf<NovelDistributionEntry?>(null) }
        var installConsent by remember { mutableStateOf(false) }
        var executionConsent by remember { mutableStateOf<String?>(null) }
        val pending = state.phase in setOf(NovelInstallPhase.PREPARING, NovelInstallPhase.PERMISSION_REQUIRED,
            NovelInstallPhase.STAGED, NovelInstallPhase.COMMITTING, NovelInstallPhase.PENDING_USER_ACTION)

        Scaffold(topBar = { behavior ->
            AppBar(title = stringResource(MR.strings.s39_title), navigateUp = navigator::pop, scrollBehavior = behavior)
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Text(stringResource(MR.strings.s39_explanation))
                    if (state.publicActive) {
                        Text(stringResource(MR.strings.s39_public_active), color = MaterialTheme.colorScheme.primary)
                    } else if (state.installerActive) {
                        Text(stringResource(MR.strings.s39_qa_active), color = MaterialTheme.colorScheme.tertiary)
                    } else {
                        Text(stringResource(MR.strings.s39_public_blocked), color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedTextField(value = url, onValueChange = { if (it.length <= 2048) url = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(MR.strings.s39_repository_url)) }, singleLine = true)
                    Button(onClick = { repositoryConsent = url }, enabled = !state.busy && url.isNotBlank()) {
                        Text(stringResource(MR.strings.s39_add_repository))
                    }
                }
                items(state.repositories.toList().sorted(), key = { "repository:$it" }) { repository ->
                    TextButton(onClick = { repositoryConsent = repository }, enabled = !state.busy) { Text(repository) }
                }
                item {
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.progress?.let { Text(stringResource(MR.strings.s39_progress, it)) }
                    if (state.progress != null) TextButton(onClick = model::cancelDownload) { Text(stringResource(MR.strings.s39_cancel_download)) }
                    state.error?.let { Text(stringResource(MR.strings.s39_error, it), color = MaterialTheme.colorScheme.error) }
                    if (state.entries.isEmpty()) Text(stringResource(MR.strings.s39_empty))
                }
                items(state.entries, key = { "entry:${it.packageName}" }) { entry ->
                    val installed = state.installed.firstOrNull { it.packageName == entry.packageName }
                    val isUpdate = state.publicActive && installed != null && installed.version < entry.longVersionCode
                    val isInstalled = installed != null && installed.version == entry.longVersionCode
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.packageName, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(MR.strings.s39_version, entry.versionName, entry.longVersionCode.toString()))
                        Text(stringResource(MR.strings.s39_origin, artifactOrigin(entry)))
                        Text(stringResource(MR.strings.s39_api, entry.novelApi))
                        if (isInstalled) {
                            Text(stringResource(MR.strings.s39_installed_same_version))
                        } else if (isUpdate) {
                            Text(stringResource(MR.strings.s39_update_available))
                            Button(onClick = { artifactConsent = entry }, enabled = !state.busy && !pending) {
                                Text(stringResource(MR.strings.s39_update))
                            }
                        } else if (state.publicActive || state.installerActive) {
                            Button(onClick = { artifactConsent = entry }, enabled = !state.busy && !pending) {
                                Text(stringResource(MR.strings.s39_download))
                            }
                        }
                        if (!state.publicActive) {
                            Text(stringResource(MR.strings.s39_update_blocked))
                        }
                    }
                }
                item {
                    state.preparedPackage?.let { packageName ->
                        Text(stringResource(MR.strings.s39_downloaded, packageName))
                        Button(onClick = { installConsent = true }, enabled = !state.busy && !pending && state.installEligible && state.installed.none { it.packageName == packageName }) {
                            Text(stringResource(MR.strings.s39_install))
                        }
                    }
                    state.operationPackage?.let { pkg ->
                        Text(stringResource(MR.strings.s39_operation, pkg, state.operationVersion.toString()))
                        Text(stringResource(MR.strings.s39_android_status, state.phase?.name.orEmpty()))
                        Text(stringResource(MR.strings.s39_execution_status, state.execution?.name ?: "DENIED"))
                        TextButton(onClick = model::refresh, enabled = !state.busy) { Text(stringResource(MR.strings.s39_refresh)) }
                        if (state.phase == NovelInstallPhase.PERMISSION_REQUIRED) {
                            TextButton(onClick = model::openPermissionSettings) { Text(stringResource(MR.strings.s39_permission)) }
                            TextButton(onClick = model::resumePermission, enabled = !state.busy) { Text(stringResource(MR.strings.s39_resume)) }
                        }
                        if (state.phase == NovelInstallPhase.PENDING_USER_ACTION) {
                            Button(onClick = model::confirmAndroid, enabled = !state.busy) { Text(stringResource(MR.strings.s39_android_confirm)) }
                        }
                        if (state.phase in setOf(NovelInstallPhase.INSTALLED_HELD, NovelInstallPhase.INSTALLED_UNRECOGNIZED)) {
                            Button(onClick = { executionConsent = state.operationId }, enabled = !state.busy) {
                                Text(stringResource(MR.strings.s39_allow_execution))
                            }
                        }
                    }
                    Text(stringResource(MR.strings.s39_installed), style = MaterialTheme.typography.titleMedium)
                }
                items(state.installed, key = { "installed:${it.packageName}" }) { installed ->
                    Text("${installed.packageName} · ${installed.version}")
                    Text(stringResource(if (installed.loaded) MR.strings.s39_recognized else MR.strings.s39_not_recognized))
                    if (state.publicActive && installed.loaded && installed.packageName !in state.endedSessions) {
                        TextButton(onClick = { model.endSession(installed.packageName, installed.version) }, enabled = !state.busy) {
                            Text(stringResource(MR.strings.s39_end_session))
                        }
                    }
                }
            }
        }
        repositoryConsent?.let { selected ->
            ConsentDialog(stringResource(MR.strings.s39_add_repository), stringResource(MR.strings.s39_repository_consent, selected),
                dismiss = { repositoryConsent = null }, confirm = { repositoryConsent = null; model.enroll(selected, true) })
        }
        artifactConsent?.let { entry ->
            val origin = artifactOrigin(entry)
            ConsentDialog(stringResource(MR.strings.s39_download), stringResource(MR.strings.s39_artifact_consent, entry.packageName, origin),
                dismiss = { artifactConsent = null }, confirm = { artifactConsent = null; model.download(entry, origin) })
        }
        if (installConsent) ConsentDialog(stringResource(MR.strings.s39_install), stringResource(MR.strings.s39_install_consent),
            dismiss = { installConsent = false }, confirm = { installConsent = false; model.installPrepared(true) })
        executionConsent?.let { operation ->
            ConsentDialog(stringResource(MR.strings.s39_allow_execution), stringResource(MR.strings.s39_execution_consent,
                state.operationPackage.orEmpty(), state.operationVersion.toString(), state.operationSigners.orEmpty()),
                dismiss = { executionConsent = null }, confirm = { executionConsent = null; model.authorizeExecution(operation, true) })
        }
    }
}

private fun artifactOrigin(entry: NovelDistributionEntry): String = entry.url.toHttpUrlOrNull()?.newBuilder()
    ?.encodedPath("/")?.query(null)?.fragment(null)?.build()?.toString()?.removeSuffix("/").orEmpty()

@Composable
private fun ConsentDialog(title: String, message: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(message) } },
        confirmButton = { TextButton(onClick = confirm) { Text(stringResource(MR.strings.s39_continue)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(MR.strings.s39_cancel)) } })
}
