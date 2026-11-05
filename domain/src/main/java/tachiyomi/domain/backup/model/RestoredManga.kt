// Modified for Daimei; see MODIFICATIONS.md.

package tachiyomi.domain.backup.model

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track
import kotlin.time.Instant

/**
 * One library entry on its way back into the database.
 *
 * The novel fields are keyed by chapter url rather than by id, because a backup cannot carry an id: ids are
 * assigned by the database when the chapter row is written, which is after this object exists. The url is what
 * `chapter.remote_url` holds on both devices. They default to empty, so a backup written before novels existed
 * restores as an ordinary entry.
 */
data class RestoredManga(
    val manga: Manga,
    val chapters: List<Chapter>,
    val categoryIds: List<Long>,
    val history: List<RestoredHistory>,
    val tracks: List<Track>,
    val excludedScanlators: List<String>,
    /**
     * How far through its chapter each of these novel chapters was read, from 0 to 1.
     *
     * The block index travels with the chapter itself, in `Chapter.lastPageRead`.
     */
    val novelPositions: Map<String, Float> = emptyMap(),
    /**
     * The `ChapterContent` JSON of these novel chapters, still undecoded.
     *
     * Undecoded on purpose: `:domain` has no dependency on the novel source API, and a payload that will not
     * decode should cost one chapter rather than the whole restore.
     */
    val novelContents: Map<String, String> = emptyMap(),
    /** Optional backup fingerprints, transported without interpreting them. */
    val novelContentFingerprints: Map<String, String?> = emptyMap(),
)

data class RestoredHistory(
    val chapterUrl: String,
    val readAt: Instant?,
    val readDuration: Long,
)
