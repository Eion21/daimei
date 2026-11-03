package app.novel.source.demo

import app.novel.source.api.Chapter
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import app.novel.source.api.Novel
import app.novel.source.api.NovelPage
import app.novel.source.api.NovelSource
import app.novel.source.api.SourceInfo

class DemoSource : NovelSource {
    override val info = SourceInfo("demo", "Demo", "1", "es")

    private val novels = listOf(
        Novel("demo", "n1", "La torre de cristal", author = "Autor Uno"),
        Novel("demo", "n2", "Crónicas del valle", author = "Autor Dos"),
    )

    override suspend fun popular(page: Int): NovelPage =
        NovelPage(if (page == 1) novels else emptyList(), hasNextPage = false)

    override suspend fun search(query: String, page: Int): NovelPage =
        NovelPage(if (page == 1) novels.filter { it.title.contains(query, ignoreCase = true) } else emptyList(), false)

    override suspend fun getNovelDetails(novel: Novel): Novel =
        novel.copy(description = "Novela de prueba con contenido determinista.")

    override suspend fun getChapters(novel: Novel): List<Chapter> =
        (1..5).map {
            Chapter(novel.sourceId, novel.id, "c$it", "Capítulo $it", number = it.toFloat())
        }

    override suspend fun getChapterContent(chapter: Chapter): ChapterContent =
        ChapterContent(
            listOf(ContentBlock.Heading(chapter.title)) +
                (1..40).map { ContentBlock.Paragraph("Párrafo $it de ${chapter.title}. Texto de prueba.") },
        )
}
