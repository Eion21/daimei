package app.novel.extension.fixtures

import app.novel.source.api.Chapter
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import app.novel.source.api.Novel
import app.novel.source.api.NovelPage
import app.novel.source.api.NovelSource
import app.novel.source.api.SourceInfo

/**
 * Self-contained [NovelSource] shipped inside the installable extension APK.
 *
 * It deliberately performs no network I/O: every method returns fixed,
 * deterministic data so the extension loader and the reader can be exercised on
 * a device that is offline. The fully-qualified name of this class is declared
 * in the manifest under the `app.novel.extension.class` metadata key.
 */
class FixtureNovelSource : NovelSource {
    override val info = SourceInfo(
        id = "fixture-extension",
        name = "Fixture novels (extension)",
        version = "1.0.0",
        language = "en",
    )

    private val novels = listOf(
        Novel(
            sourceId = info.id,
            id = "the-lantern-keeper",
            title = "The Lantern Keeper",
            author = "A. Fixture",
            description = "A short deterministic novel bundled with the extension APK.",
        ),
        Novel(
            sourceId = info.id,
            id = "echoes-of-the-tide",
            title = "Echoes of the Tide",
            author = "B. Fixture",
            description = "A second bundled novel, used to exercise list rendering.",
        ),
    )

    override suspend fun popular(page: Int): NovelPage =
        NovelPage(novels.takeIf { page == 1 } ?: emptyList(), hasNextPage = false)

    override suspend fun search(query: String, page: Int): NovelPage {
        val matches = novels.filter { it.title.contains(query, ignoreCase = true) }
        return NovelPage(matches, hasNextPage = false)
    }

    override suspend fun getNovelDetails(novel: Novel): Novel = novel.copy(
        description = novel.description ?: "Details for ${novel.title}.",
    )

    override suspend fun getChapters(novel: Novel): List<Chapter> = listOf(
        Chapter(
            sourceId = info.id,
            novelId = novel.id,
            id = "chapter-1",
            title = "Chapter 1",
            number = 1f,
        ),
        Chapter(
            sourceId = info.id,
            novelId = novel.id,
            id = "chapter-2",
            title = "Chapter 2",
            number = 2f,
        ),
    )

    override suspend fun getChapterContent(chapter: Chapter): ChapterContent = ChapterContent(
        blocks = listOf(
            ContentBlock.Heading(chapter.title, level = 1),
            ContentBlock.Paragraph(
                "This paragraph comes from the fixture extension APK. " +
                    "It is rendered without any network access.",
            ),
            ContentBlock.Divider,
            // A non-resolvable `.invalid` host keeps the block self-contained:
            // the fixture never depends on a reachable image server.
            ContentBlock.Image(url = "https://fixture.invalid/illustration.png", alt = "Illustration"),
            ContentBlock.Paragraph("End of the fixture chapter."),
        ),
    )
}
