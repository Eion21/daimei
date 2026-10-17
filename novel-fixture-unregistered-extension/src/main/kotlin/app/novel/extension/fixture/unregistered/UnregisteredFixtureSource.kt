package app.novel.extension.fixture.unregistered

import android.content.Context
import app.novel.source.api.Chapter
import app.novel.source.api.ChapterContent
import app.novel.source.api.NetworkedNovelSource
import app.novel.source.api.Novel
import app.novel.source.api.NovelNetwork
import app.novel.source.api.NovelPage
import app.novel.source.api.SourceInfo
import app.novel.source.api.android.ContextAwareNovelSource

/**
 * The minimal source the unregistered-fixture apk ships.
 *
 * It declares both host capabilities a contract-1.3 extension can ask for — the host network and the Android
 * context — and does nothing else. Its body is deliberately trivial: it exists so the real loader can be driven
 * with an apk that demands a context, proving the private `.ext` path is refused with ContextUnavailable before
 * the source is ever registered.
 */
class UnregisteredFixtureSource : NetworkedNovelSource, ContextAwareNovelSource {
    private lateinit var network: NovelNetwork
    private lateinit var context: Context

    override val info = SourceInfo(
        id = "fixture-unregistered",
        name = "Unregistered fixture",
        version = "1.0.0",
        language = "en",
    )

    override fun bindNetwork(network: NovelNetwork) {
        this.network = network
    }

    override fun bindContext(context: Context) {
        this.context = context
    }

    override suspend fun popular(page: Int): NovelPage = NovelPage(emptyList(), hasNextPage = false)

    override suspend fun search(query: String, page: Int): NovelPage = NovelPage(emptyList(), hasNextPage = false)

    override suspend fun getNovelDetails(novel: Novel): Novel = novel

    override suspend fun getChapters(novel: Novel): List<Chapter> = emptyList()

    override suspend fun getChapterContent(chapter: Chapter): ChapterContent = ChapterContent(emptyList())
}
