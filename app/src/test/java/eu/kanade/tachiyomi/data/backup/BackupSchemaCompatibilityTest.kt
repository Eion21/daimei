package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovelContent
import eu.kanade.tachiyomi.data.backup.models.toBackupChapter
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import mihon.core.common.extensions.JsonObjectEmptyBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import kotlin.time.Instant

/**
 * The backup schema is ProtoBuf over hand-written models, so compatibility is decided by field numbers and by what
 * leaves the wire — not by anything a Kotlin type checker can see. These are the assertions that a new field cannot
 * be added by accident: that it has a number nothing else uses, and that a chapter it does not apply to serializes to
 * exactly the bytes it did before.
 *
 * The golden hex below is the encoding of `BackupChapter` as it stood before the fraction existed, and the test
 * that pins it encodes that pre-change model by hand rather than taking it on trust — so the comparison is between
 * the old wire format and the new one, not between a literal and itself. That is the strongest statement available
 * without a device: if this change ever puts a novel field on an image chapter's wire, the bytes differ and this
 * fails on a number, not on a reading of the output.
 */
class BackupSchemaCompatibilityTest {

    @Test
    fun `an image chapter's backup bytes are the ones this change was written against`() {
        val bytes = ProtoBuf.encodeToByteArray(BackupChapter.serializer(), imageChapter().toBackupChapter())

        assertEquals(PRE_NOVEL_IMAGE_CHAPTER_HEX, bytes.toHex())
    }

    @Test
    fun `an image chapter's wire carries no novel field`() {
        val bytes = ProtoBuf.encodeToByteArray(BackupChapter.serializer(), imageChapter().toBackupChapter())

        // Read off the wire rather than off the model, so this cannot be satisfied by renaming something.
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 13), wireFieldNumbers(bytes))
        assertTrue(FRACTION_PROTO_NUMBER !in wireFieldNumbers(bytes))
        assertTrue(FINGERPRINT_PROTO_NUMBER !in wireFieldNumbers(bytes))
    }

    @Test
    fun `a manga entry with no novel chapter carries no novel field`() {
        val entry = BackupManga(source = 1L, url = "n1", title = "La torre de cristal")
        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), Backup(backupManga = listOf(entry)))

        // The entry is a field inside the backup, so its own numbers are read out of the nested message rather
        // than off the whole file, where field 114 would be indistinguishable from a top-level field.
        val mangaFields = nestedMessageFields(bytes)
        assertTrue(NOVEL_CONTENTS_PROTO_NUMBER !in mangaFields, "novel contents reached the wire: $mangaFields")
        assertEquals(listOf(1, 2, 3), mangaFields)
    }

    @Test
    fun `the fraction's proto number is not already in use on a chapter`() {
        val numbers = protoNumbers(BackupChapter.serializer().descriptor)

        assertEquals(numbers.size, numbers.toSet().size, "a proto number is used twice on BackupChapter: $numbers")
        assertEquals(listOf(13, FRACTION_PROTO_NUMBER, FINGERPRINT_PROTO_NUMBER), numbers.takeLast(3))
        assertEquals(13, numbers.size)
    }

    @Test
    fun `the content payload's proto number is not already in use on a manga`() {
        val numbers = protoNumbers(BackupManga.serializer().descriptor)

        assertEquals(numbers.size, numbers.toSet().size, "a proto number is used twice on BackupManga: $numbers")
        assertTrue(NOVEL_CONTENTS_PROTO_NUMBER in numbers)
        assertEquals(113, numbers[numbers.size - 2], "BackupManga should still end at 113 before the new field")
    }

    @Test
    fun `the content payload's own numbers are unique`() {
        val numbers = protoNumbers(BackupNovelContent.serializer().descriptor)

        assertEquals(listOf(1, 2), numbers)
    }

    @Test
    fun `the golden bytes are what the model emitted before this change`() {
        // Without this the golden hex above is just a constant: it would prove that today's model still agrees
        // with itself, and nothing about where the constant came from. Transcribing `BackupChapter` as it stood
        // before the fraction was added — every field, every number, no 14 — and encoding it independently is what
        // makes the comparison above a statement about compatibility rather than about a literal.
        val beforeTheChange = PreNovelBackupChapter(
            url = "/chapter-12",
            name = "Chapter 12",
            scanlator = "scan",
            read = true,
            bookmark = true,
            lastPageRead = 12L,
            dateFetch = 1_700_000_000_000L,
            dateUpload = 1_600_000_000_000L,
            chapterNumber = 12F,
            sourceOrder = 5L,
            // A *fresh* `{}`, not the `JsonObjectEmptyBytes` the model declares as its default, and that detail is
            // the whole difference between this matching and not. `toBackupChapter` reaches the memo through
            // `JsonObject.toByteArray()`, which allocates a new array every call, while ProtoBuf drops a field only
            // when it `equals` the default — and `ByteArray.equals` is identity, not content. So a backup has always
            // carried an empty memo on the wire as field 13, and a model handed the shared instance silently drops
            // it. Transcribing the default faithfully here would have "reproduced" the old format less accurately.
            memo = "{}".encodeToByteArray(),
        )

        assertEquals(
            PRE_NOVEL_IMAGE_CHAPTER_HEX,
            ProtoBuf.encodeToByteArray(PreNovelBackupChapter.serializer(), beforeTheChange).toHex(),
            "the golden hex is not the encoding of the model this change replaced",
        )
        assertEquals(
            PRE_NOVEL_IMAGE_CHAPTER_HEX,
            ProtoBuf.encodeToByteArray(BackupChapter.serializer(), imageChapter().toBackupChapter()).toHex(),
            "adding the fraction moved an image chapter's bytes",
        )
    }

    @Test
    fun `a backup written before the fraction existed still decodes`() {
        val decoded = ProtoBuf.decodeFromByteArray(
            BackupChapter.serializer(),
            PRE_NOVEL_IMAGE_CHAPTER_HEX.hexToBytes(),
        )

        assertEquals(0f, decoded.lastBlockFraction)
        assertEquals("/chapter-12", decoded.url)
        assertEquals(12L, decoded.lastPageRead)
    }

    @Test
    fun `legacy chapter decodes with an optional default content fingerprint`() {
        val decoded = ProtoBuf.decodeFromByteArray(
            BackupChapter.serializer(),
            PRE_NOVEL_IMAGE_CHAPTER_HEX.hexToBytes(),
        )
        assertEquals("/chapter-12", decoded.url)
        assertEquals("Chapter 12", decoded.name)
        assertEquals(12L, decoded.lastPageRead)
        assertEquals(0f, decoded.lastBlockFraction)
        assertNull(decoded.contentFingerprint)

        // Inspect the real serializer: this compiles before the field exists, without fabricating a fingerprint.
        // That described the original descriptor-only check; the new nullable-property assertions require the stub.
        // The default new field must stay off the legacy wire.
        val descriptor = BackupChapter.serializer().descriptor
        val index = (0 until descriptor.elementsCount).firstOrNull {
            descriptor.getElementName(it) == "contentFingerprint"
        }
        assertTrue(index != null, "BackupChapter must have an optional contentFingerprint field")
        val fingerprintIndex = requireNotNull(index)
        assertTrue(descriptor.isElementOptional(fingerprintIndex), "contentFingerprint must have a default")
        val number = descriptor.getElementAnnotations(fingerprintIndex).filterIsInstance<ProtoNumber>().single().number
        assertTrue(number !in wireFieldNumbers(PRE_NOVEL_IMAGE_CHAPTER_HEX.hexToBytes()))
        assertTrue(number !in wireFieldNumbers(ProtoBuf.encodeToByteArray(BackupChapter.serializer(), decoded)))
    }

    @Test
    fun `synthetic old schema JSON without fingerprint decodes with null`() {
        val oldJson = """{"url":"/chapter-12","name":"Chapter 12","lastPageRead":12}"""
        val decoded = Json.decodeFromString(BackupChapter.serializer(), oldJson)

        assertEquals("/chapter-12", decoded.url)
        assertEquals("Chapter 12", decoded.name)
        assertEquals(12L, decoded.lastPageRead)
        assertNull(decoded.contentFingerprint)
    }

    @Test
    fun `a backup written before novel contents existed still decodes`() {
        val decoded = ProtoBuf.decodeFromByteArray(
            BackupManga.serializer(),
            ProtoBuf.encodeToByteArray(BackupManga.serializer(), BackupManga(source = 1L, url = "n1")),
        )

        assertTrue(decoded.novelContents.isEmpty())
    }

    @Test
    fun `an old build skips the novel fields instead of failing on them`() {
        // The two halves of the compatibility promise are both here: a new backup read by an old app, and an old
        // backup read by a new one. Neither can be checked with the current model — reading new bytes needs a
        // reader that does not know the field, so this stands in the pre-change shape by hand.
        val novelChapter = BackupChapter(url = "c1", name = "Capítulo 1", lastBlockFraction = 0.375F)
        val novelEntry = BackupManga(
            source = 1L,
            url = "n1",
            title = "La torre de cristal",
            novelContents = listOf(BackupNovelContent(chapterUrl = "c1", content = """{"blocks":[]}""")),
        )
        val newChapterBytes = ProtoBuf.encodeToByteArray(BackupChapter.serializer(), novelChapter)
        val newMangaBytes = ProtoBuf.encodeToByteArray(BackupManga.serializer(), novelEntry)

        assertEquals(
            "c1",
            ProtoBuf.decodeFromByteArray(PreNovelBackupChapter.serializer(), newChapterBytes).url,
        )
        assertEquals(
            0L,
            ProtoBuf.decodeFromByteArray(PreNovelBackupChapter.serializer(), newChapterBytes).lastPageRead,
        )
        assertEquals(
            "La torre de cristal",
            ProtoBuf.decodeFromByteArray(PreNovelBackupManga.serializer(), newMangaBytes).title,
        )
    }

    // Helpers.

    private fun imageChapter() = Chapter.create().copy(
        id = 7L,
        mangaId = 3L,
        read = true,
        bookmark = true,
        lastPageRead = 12,
        dateFetch = Instant.fromEpochMilliseconds(1_700_000_000_000),
        sourceOrder = 5,
        url = "/chapter-12",
        name = "Chapter 12",
        dateUpload = Instant.fromEpochMilliseconds(1_600_000_000_000),
        chapterNumber = 12.0,
        scanlator = "scan",
    )

    private fun protoNumbers(descriptor: SerialDescriptor): List<Int> =
        (0 until descriptor.elementsCount).map { index ->
            descriptor.getElementAnnotations(index)
                .filterIsInstance<ProtoNumber>()
                .single()
                .number
        }

    /**
     * The field numbers a ProtoBuf message actually put on the wire, in order.
     *
     * Written out rather than decoded through the model, because the point is that the bytes do not depend on the
     * model: a field that is dropped, renamed or renumbered shows up here and nowhere else. Each number is paired
     * with the bytes of its own value so a caller can look inside one.
     */
    private fun wireFields(bytes: ByteArray): List<WireField> {
        val fields = mutableListOf<WireField>()
        var index = 0

        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                val byte = bytes[index++].toInt() and 0xFF
                value = value or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return value
                shift += 7
            }
        }

        while (index < bytes.size) {
            val tag = varint().toInt()
            // The value of a length-delimited field starts after its length, and the length has to land in its own
            // local before `index` moves: `index += varint()` reads `index` before the call that advances it.
            val valueStart = when (tag and 7) {
                0 -> {
                    varint()
                    index
                }
                1 -> index.also { index += 8 }
                2 -> {
                    val length = varint().toInt()
                    index.also { index += length }
                }
                5 -> index.also { index += 4 }
                else -> error("unexpected wire type ${tag and 7} at $index in " + bytes.toHex())
            }
            fields += WireField(number = tag ushr 3, value = bytes.copyOfRange(valueStart, index))
        }
        return fields
    }

    private fun wireFieldNumbers(bytes: ByteArray) = wireFields(bytes).map { it.number }

    /** The field numbers inside the first length-delimited field of [bytes], which is where the entry sits. */
    private fun nestedMessageFields(bytes: ByteArray) =
        wireFieldNumbers(wireFields(bytes).first { it.number == 1 }.value)

    private data class WireField(val number: Int, val value: ByteArray)

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }

    private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val FRACTION_PROTO_NUMBER = 14
        const val FINGERPRINT_PROTO_NUMBER = 15
        const val NOVEL_CONTENTS_PROTO_NUMBER = 114

        /** `BackupChapter` as it serialized before the fraction existed: 62 bytes, eleven fields, no 14. */
        const val PRE_NOVEL_IMAGE_CHAPTER_HEX =
            "0A0B2F636861707465722D3132120A436861707465722031321A047363616E20012801300C3880D095FFBC31" +
                "408080BABBC82E4D0000404150056A027B7D"
    }
}

/**
 * [BackupChapter] exactly as it stood before the fraction was added — all eleven of its fields, no 14.
 *
 * Declared by hand from the model this change replaced rather than kept as a copy, so it cannot drift along with
 * whatever [BackupChapter] becomes next. It is used twice, and both uses need the whole shape: encoding it shows
 * that the golden hex really is the old wire format, and decoding a new chapter's bytes with it shows that a build
 * which has never heard of field 14 skips it instead of rejecting the file.
 */
@Serializable
private class PreNovelBackupChapter(
    @ProtoNumber(1) val url: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val scanlator: String? = null,
    @ProtoNumber(4) val read: Boolean = false,
    @ProtoNumber(5) val bookmark: Boolean = false,
    @ProtoNumber(6) val lastPageRead: Long = 0,
    @ProtoNumber(7) val dateFetch: Long = 0,
    @ProtoNumber(8) val dateUpload: Long = 0,
    @ProtoNumber(9) val chapterNumber: Float = 0F,
    @ProtoNumber(10) val sourceOrder: Long = 0,
    @ProtoNumber(13) val memo: ByteArray = JsonObjectEmptyBytes,
)

/**
 * [BackupManga] reduced to a few of the fields it had before novel contents were added.
 *
 * See [PreNovelBackupChapter] for why the pre-change shape is declared by hand.
 */
@Serializable
private class PreNovelBackupManga(
    @ProtoNumber(1) val source: Long,
    @ProtoNumber(2) val url: String,
    @ProtoNumber(3) val title: String = "",
)
