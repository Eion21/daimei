package eu.kanade.tachiyomi.ui.browse.extension.novel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import eu.kanade.tachiyomi.extension.novel.NovelCatalogError
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

class NovelCatalogScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<NovelCatalogViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        var url by remember { mutableStateOf("") }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.novel_catalog_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(MR.strings.novel_catalog_explanation))
                OutlinedTextField(
                    value = url,
                    onValueChange = { if (it.length <= 2048) url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(MR.strings.novel_catalog_url)) },
                    singleLine = true,
                )
                Button(onClick = { viewModel.query(url) }, enabled = url.isNotBlank()) {
                    Text(stringResource(MR.strings.novel_catalog_fetch))
                }
                when (val result = state) {
                    NovelCatalogState.Initial -> Text(stringResource(MR.strings.novel_catalog_initial))
                    NovelCatalogState.Loading -> Text(stringResource(MR.strings.novel_catalog_loading))
                    is NovelCatalogState.Error -> Text(
                        errorMessage(result.reason),
                        color = MaterialTheme.colorScheme.error,
                    )
                    is NovelCatalogState.Metadata -> {
                        Text(stringResource(MR.strings.novel_catalog_origin, result.origin))
                        Text(stringResource(MR.strings.novel_catalog_available))
                        result.entries.forEach { entry ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(MR.strings.novel_catalog_package, entry.packageName))
                                Text(
                                    stringResource(
                                        MR.strings.novel_catalog_version,
                                        entry.versionName,
                                        entry.versionCode.toString(),
                                    ),
                                )
                                Text(stringResource(MR.strings.novel_catalog_api, entry.novelApi))
                                Text(stringResource(MR.strings.novel_catalog_blocked))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun errorMessage(reason: NovelCatalogError): String = when (reason) {
    NovelCatalogError.URL -> stringResource(MR.strings.novel_catalog_error_url)
    NovelCatalogError.NETWORK -> stringResource(MR.strings.novel_catalog_error_network)
    NovelCatalogError.HTTP -> stringResource(MR.strings.novel_catalog_error_http)
    NovelCatalogError.SIZE -> stringResource(MR.strings.novel_catalog_error_size)
    NovelCatalogError.JSON -> stringResource(MR.strings.novel_catalog_error_json)
    NovelCatalogError.SCHEMA -> stringResource(MR.strings.novel_catalog_error_schema)
}
