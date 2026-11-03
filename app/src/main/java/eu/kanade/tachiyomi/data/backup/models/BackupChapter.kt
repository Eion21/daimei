package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import mihon.core.common.extensions.JsonObjectEmptyBytes
import mihon.core.common.extensions.toByteArray
import mihon.core.common.extensions.toEpochMillisOrZero
import mihon.core.common.extensions.toInstantOrNull
import mihon.core.common.extensions.toJsonObject
import tachiyomi.domain.chapter.model.Chapter
import kotlin.time.Instant

@Serializable
class BackupChapter(
    // in 1.x some of these values have different names
    // url is called key in 1.x
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var scanlator: String? = null,
    @ProtoNumber(4) var read: Boolean = false,
    @ProtoNumber(5) var bookmark: Boolean = false,
    // lastPageRead is called progress in 1.x. For a novel chapter it carries the block the reader stopped in
    // rather than the page, because a novel chapter is read as text: see lastBlockFraction for why the index
    // alone is not enough. An image chapter keeps meaning the page and is written the same way it always was.
    @ProtoNumber(6) var lastPageRead: Long = 0,
    @ProtoNumber(7) var dateFetch: Long = 0,
    @ProtoNumber(8) var dateUpload: Long = 0,
    // chapterNumber is called number is 1.x
    @ProtoNumber(9) var chapterNumber: Float = 0F,
    @ProtoNumber(10) var sourceOrder: Long = 0,
    // @ProtoNumber(11) var lastModifiedAt: Long, artifact of the abandoned sync attempt
    // @ProtoNumber(12) var version: Long, artifact of the abandoned sync attempt
    @ProtoNumber(13) var memo: ByteArray = JsonObjectEmptyBytes,
    /**
     * How far through [lastPageRead] the reader had scrolled, from 0 to 1.
     *
     * Written only for a novel chapter, because only a novel chapter has one: this is the value that makes the
     * position survive a font-size change, since a pixel or block offset lands somewhere else on the page as
     * soon as the text reflows.
     *
     * 0F is the default and ProtoBuf leaves a default off the wire, so an image chapter's bytes are exactly
     * what they were before this field existed, and a backup written before it restores as a fraction of 0.
     */
    @ProtoNumber(14) var lastBlockFraction: Float = 0F,
    @ProtoNumber(15) var contentFingerprint: String? = null,
) {
    fun toChapterImpl(): Chapter {
        return Chapter.create().copy(
            url = this@BackupChapter.url,
            name = this@BackupChapter.name,
            chapterNumber = this@BackupChapter.chapterNumber.toDouble(),
            scanlator = this@BackupChapter.scanlator,
            read = this@BackupChapter.read,
            bookmark = this@BackupChapter.bookmark,
            // Only untrusted source of this value, and a truncating narrow can land negative
            lastPageRead = this@BackupChapter.lastPageRead.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            dateFetch = Instant.fromEpochMilliseconds(this@BackupChapter.dateFetch),
            dateUpload = this@BackupChapter.dateUpload.toInstantOrNull(),
            sourceOrder = this@BackupChapter.sourceOrder,
            memo = this@BackupChapter.memo.toJsonObject(),
        )
    }
}

fun Chapter.toBackupChapter() = BackupChapter(
    url = url,
    name = name,
    chapterNumber = chapterNumber.toFloat(),
    scanlator = scanlator,
    read = read,
    bookmark = bookmark,
    lastPageRead = lastPageRead.toLong(),
    dateFetch = dateFetch.toEpochMilliseconds(),
    dateUpload = dateUpload.toEpochMillisOrZero(),
    sourceOrder = sourceOrder,
    memo = memo.toByteArray(),
)
