package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * The text of one novel chapter, as the backup carries it.
 *
 * [content] is the `ChapterContent` JSON itself and never `chapter_content.file_path`, which is an absolute path
 * into the cache directory of whichever device wrote the backup and means nothing on any other one. Storing the
 * path would produce a backup that restores a chapter as "downloaded" and then fails to open.
 *
 * It stays a [String] rather than a decoded `ChapterContent` on purpose. A typed field is validated while the
 * whole backup is being deserialized, so one corrupt payload would fail the restore of every entry in the file
 * instead of the one chapter it belongs to. Decoded per chapter, a payload that will not decode costs one
 * chapter; see `RestoreRepositoryImpl`.
 *
 * Keyed by the chapter's url because that is what both `chapter.remote_url` and [BackupHistory.url] carry
 * across devices, and what a chapter id cannot: ids are assigned by the database on restore.
 */
@Serializable
class BackupNovelContent(
    @ProtoNumber(1) var chapterUrl: String,
    @ProtoNumber(2) var content: String,
)
