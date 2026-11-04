package app.novel.reader.compose

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.novel.reader.core.ReaderTheme

internal const val READER_TOOLBAR_TRANSITION_MS = 180
internal val readerToolbarAnimation = tween<Float>(durationMillis = READER_TOOLBAR_TRANSITION_MS)

internal fun readerToolbarColors(theme: ReaderTheme): ReaderColors {
    val colors = ReaderColorScheme.colorsFor(theme)
    return colors.copy(background = lerp(colors.background, colors.text, 0.08f).copy(alpha = 0.8f))
}

/** Chrome only: callbacks and the reading list remain owned by the existing screen. */
@Composable
internal fun ReaderToolbar(
    theme: ReaderTheme,
    controlsLabel: String,
    backLabel: String,
    settingsLabel: String,
    workTitle: String,
    chapterTitle: String,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = readerToolbarColors(theme)
    Surface(
        color = colors.background,
        contentColor = colors.text,
        modifier = modifier.fillMaxWidth().semantics {
            isTraversalGroup = true
            paneTitle = controlsLabel
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel)
            }
            Column(modifier = Modifier.weight(1f).padding(8.dp)) {
                Text(
                    text = workTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = chapterTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onSettings) { Text(settingsLabel, color = colors.text) }
        }
    }
}
