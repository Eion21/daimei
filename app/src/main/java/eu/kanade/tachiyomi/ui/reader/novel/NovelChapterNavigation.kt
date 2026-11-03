package eu.kanade.tachiyomi.ui.reader.novel

import eu.kanade.tachiyomi.source.novel.UNRECOGNIZED_NUMBER
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.model.Manga

/** Reader preferences, sampled by the host; planning navigation performs no IO or writes. */
internal data class NovelNavigationPreferences(
    val skipRead: Boolean = false,
    val skipFiltered: Boolean = false,
    val skipDuplicates: Boolean = false,
    val downloadedOnly: Boolean = false,
)

/** Resource-independent reasons: the host owns the localized footer text. */
internal enum class NovelNavigationStatus {
    NEXT,
    LAST_CHAPTER,
    NO_MORE_DOWNLOADED,
    UNKNOWN_NUMBER,
}

internal data class NovelChapterNavigation(
    val chapters: List<Chapter>,
    val next: Chapter?,
    val status: NovelNavigationStatus,
    val downloadedOnly: Boolean,
    val previous: Chapter? = null,
)

/**
 * Novel-side policy copy, intentionally separate until phase 9 removes the image reader.
 *
 * [chapters] is the scanlator-filtered list; [current] is also supplied separately so omissions cannot lose it.
 * [downloadedChapterIds] is a host snapshot, including all local-source chapters (which are already offline).
 * The current chapter is an anchor, never a destination of its own navigation.
 */
internal fun resolveNovelChapterNavigation(
    manga: Manga,
    chapters: List<Chapter>,
    current: Chapter,
    preferences: NovelNavigationPreferences = NovelNavigationPreferences(),
    downloadedChapterIds: Set<Long> = emptySet(),
): NovelChapterNavigation {
    // ReaderViewModel.kt:224–234 fetches scanlator-filtered chapters and restores the active row.
    // The host supplies that filtered snapshot; restore its missing anchor without IO here.
    val anchored = if (chapters.any { it.id == current.id }) chapters else chapters + current

    // ReaderViewModel.kt:236–277: skipRead, read/download/bookmark filters, then restore current.
    // Download membership is the host's snapshot of the same isChapterDownloaded decision.
    val eligible = anchored.filter { chapter ->
        chapter.id == current.id || !(
            (preferences.skipRead && chapter.read) ||
                (preferences.skipFiltered && (
                    (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_READ && !chapter.read) ||
                        (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_UNREAD && chapter.read) ||
                        (manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_DOWNLOADED &&
                            chapter.id !in downloadedChapterIds) ||
                        (manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_NOT_DOWNLOADED &&
                            chapter.id in downloadedChapterIds) ||
                        (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_BOOKMARKED && !chapter.bookmark) ||
                        (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_NOT_BOOKMARKED && chapter.bookmark)
                    ))
            )
    }

    // ReaderViewModel.kt:283–287 / ChapterRemoveDuplicates.kt:8–14: current, then its scanlator,
    // then first input. S31/S32: apply ONLY to recognized numbers (including zero), never unknowns.
    // With this opt-in, 12-A may hide translation 12-B, or a DISTINCT PART sharing number 12.
    // The approved default remains false; rows are omitted from navigation, not deleted.
    val deduplicated = if (preferences.skipDuplicates) {
        val recognizedIds = eligible.filterNot { it.hasUnknownNumber() }
            .removeDuplicates(current).map { it.id }.toSet()
        eligible.filter { it.hasUnknownNumber() || it.id in recognizedIds }
    } else {
        eligible
    }

    // ReaderViewModel.kt:281: use the work's comparator, always sortDescending=false.
    // Approved S29 stage difference: novel dedup is BEFORE sort; image sorts before dedup.
    // This preserves the first INPUT representative when neither current nor scanlator matches.
    val ordered = deduplicated.sortedWith(getChapterSort(manga, sortDescending = false))

    // S31 novel safeguard, NOT image behavior: a sentinel sorts before recognized numbers, but
    // that does not prove it precedes the anchor. Any eligible unknown makes numeric order unsafe.
    // Never silently substitute SOURCE, coerce to zero, or infer a safe successor from that sort.
    val numericAmbiguity = manga.sorting == Manga.CHAPTER_SORTING_NUMBER && (
        current.hasUnknownNumber() || ordered.any {
            it.hasUnknownNumber() && (!preferences.downloadedOnly || it.id in downloadedChapterIds)
        }
        )
    if (numericAmbiguity) {
        return NovelChapterNavigation(ordered, null, NovelNavigationStatus.UNKNOWN_NUMBER, preferences.downloadedOnly)
    }

    // ReaderViewModel.kt:289–295 filters the WHOLE list for downloadedOnly. Approved S29 exception:
    // retain current as anchor and apply downloadedOnly ONLY to destinations AFTER that anchor.
    val anchorIndex = ordered.indexOfFirst { it.id == current.id }
    val previous = ordered.take(anchorIndex).asReversed().firstOrNull {
        !preferences.downloadedOnly || it.id in downloadedChapterIds
    }
    val destinations = ordered.drop(anchorIndex + 1)
    val next = destinations.firstOrNull {
        it.id != current.id && (!preferences.downloadedOnly || it.id in downloadedChapterIds)
    }
    val status = when {
        next != null -> NovelNavigationStatus.NEXT
        preferences.downloadedOnly -> NovelNavigationStatus.NO_MORE_DOWNLOADED
        else -> NovelNavigationStatus.LAST_CHAPTER
    }
    return NovelChapterNavigation(ordered, next, status, preferences.downloadedOnly, previous)
}

private fun Chapter.hasUnknownNumber(): Boolean = chapterNumber == UNRECOGNIZED_NUMBER.toDouble()
