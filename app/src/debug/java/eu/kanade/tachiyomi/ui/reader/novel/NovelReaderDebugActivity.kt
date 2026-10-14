package eu.kanade.tachiyomi.ui.reader.novel

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.novel.source.demo.DemoSource
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.data.novel.NovelContentDir
import tachiyomi.data.novel.NovelMapper
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.novel.repository.ChapterContentRepository

/**
 * Debug-only harness that makes the novel reader reachable before the library is wired.
 *
 * It seeds one novel and one chapter from [DemoSource] through the real repositories, writes the chapter
 * content to disk the same way the production path does, and opens [NovelReaderHost] on that chapter. Nothing
 * is stubbed, which is the point: the reader can only be trusted once its scrolling, reflow handling and
 * progress persistence have actually run on a device.
 *
 * Launch with:
 * `adb shell am start -n app.daimei.dev/eu.kanade.tachiyomi.ui.reader.novel.NovelReaderDebugActivity`
 */
class NovelReaderDebugActivity : BaseActivity() {

    private val chapterId = mutableStateOf<Long?>(null)
    private val failure = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = appGraph
        val mangaRepository = graph.novelMangaRepository
        val chapterRepository = graph.novelChapterRepository
        val chapterContentRepository = graph.novelChapterContentRepository
        val contentDir = graph.novelContentDir

        lifecycleScope.launch {
            runCatching { seed(mangaRepository, chapterRepository, chapterContentRepository, contentDir) }
                .onSuccess { chapterId.value = it }
                .onFailure { failure.value = it.stackTraceToString() }
        }

        // The app's own extension, not AndroidX's: it is the one that provides `LocalMetroViewModelFactory`, which
        // `metroViewModel()` needs to resolve the reader's ViewModel.
        setComposeContent {
            when {
                failure.value != null -> Message("No se pudo preparar el capítulo:\n${failure.value}")
                chapterId.value != null -> NovelReaderHost(chapterId = chapterId.value!!)
                else -> Message("Preparando el capítulo de demostración…")
            }
        }
    }

    /** Persists a demo novel and its first chapter, then returns the persisted chapter id. */
    private suspend fun seed(
        mangaRepository: MangaRepository,
        chapterRepository: ChapterRepository,
        chapterContentRepository: ChapterContentRepository,
        contentDir: NovelContentDir,
    ): Long {
        val demoSource = DemoSource()
        val novel = demoSource.popular(page = 1).novels.first()

        val manga = NovelMapper.toManga(novel)
        val storedManga = mangaRepository.insertNetworkManga(listOf(manga)).first()

        val sourceChapters = demoSource.getChapters(novel)
        val chapters = sourceChapters.mapIndexed { index, chapter ->
            // mangaId is filled from the persisted parent, the rest still carries placeholder ids.
            NovelMapper.toChapter(novel, chapter, sourceOrder = index.toLong()).copy(mangaId = storedManga.id)
        }
        // `updateFromRemote` is a sync: it returns only what it actually inserted, so on a second run the
        // chapters are already there and it returns nothing. The chapter is then read back instead, which
        // makes seeding idempotent across launches.
        chapterRepository.updateFromRemote(
            removedIds = emptyList(),
            added = chapters,
            updated = emptyList(),
        )
        val storedChapter = chapterRepository.getChapterByUrlAndMangaId(
            url = chapters.first().url,
            mangaId = storedManga.id,
        ) ?: error("El capítulo ${chapters.first().url} no quedó persistido")

        chapterContentRepository.save(storedChapter.id, demoSource.getChapterContent(sourceChapters.first()))
        return storedChapter.id
    }

    @Composable
    private fun Message(text: String) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = text)
        }
    }
}
