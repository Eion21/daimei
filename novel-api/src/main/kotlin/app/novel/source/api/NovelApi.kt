package app.novel.source.api

import kotlinx.serialization.Serializable

data class SourceInfo(val id: String, val name: String, val version: String, val language: String)

data class Novel(
    val sourceId: String,
    val id: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val coverUrl: String? = null,
)

data class Chapter(
    val sourceId: String,
    val novelId: String,
    val id: String,
    val title: String,
    val number: Float? = null,
    val url: String? = null,
)

data class NovelPage(val novels: List<Novel>, val hasNextPage: Boolean)

@Serializable
sealed interface ContentBlock {
    @Serializable data class Paragraph(val text: String) : ContentBlock

    @Serializable data class Heading(val text: String, val level: Int = 2) : ContentBlock

    @Serializable data class Image(val url: String, val alt: String? = null) : ContentBlock

    @Serializable data object Divider : ContentBlock
}

@Serializable
data class ChapterContent(val blocks: List<ContentBlock>)

interface NovelSource {
    val info: SourceInfo
    suspend fun popular(page: Int): NovelPage
    suspend fun search(query: String, page: Int): NovelPage
    suspend fun getNovelDetails(novel: Novel): Novel
    suspend fun getChapters(novel: Novel): List<Chapter>
    suspend fun getChapterContent(chapter: Chapter): ChapterContent
}
