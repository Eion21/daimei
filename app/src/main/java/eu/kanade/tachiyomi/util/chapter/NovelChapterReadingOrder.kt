package eu.kanade.tachiyomi.util.chapter

import eu.kanade.domain.chapter.model.applyFilters
import eu.kanade.tachiyomi.ui.manga.ChapterList
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.model.Manga

/** Source indices follow the novel source's list; other modes retain the existing reader comparator. */
internal fun novelChapterReadingOrder(manga: Manga): (Chapter, Chapter) -> Int =
    if (manga.sorting == Manga.CHAPTER_SORTING_SOURCE) {
        { first, second -> first.sourceOrder.compareTo(second.sourceOrder) }
    } else {
        getChapterSort(manga, sortDescending = false)
    }

/** Apply the existing view filters before picking the first unread novel chapter. */
internal fun novelNextUnread(chapters: List<ChapterList.Item>, manga: Manga): Chapter? {
    // Preserve the exact existing selection and tie behavior for every non-SOURCE sort mode.
    if (manga.sorting != Manga.CHAPTER_SORTING_SOURCE) return chapters.getNextUnread(manga)
    val order = novelChapterReadingOrder(manga)
    return chapters.applyFilters(manga)
        .sortedWith { first, second -> order(first.chapter, second.chapter) }
        .firstOrNull { !it.chapter.read }
        ?.chapter
}
