package tachiyomi.data.backup

import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/** Frozen v1 encoding shared by backup creation and restore; serialization defaults are omitted. */
object ContentFingerprint {
    fun canonicalBytes(content: ChapterContent): ByteArray = JsonArray(content.blocks.map { block ->
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        when (block) {
            is ContentBlock.Paragraph -> {
                fields["type"] = JsonPrimitive("app.novel.source.api.ContentBlock.Paragraph")
                fields["text"] = JsonPrimitive(block.text)
            }
            is ContentBlock.Heading -> {
                fields["type"] = JsonPrimitive("app.novel.source.api.ContentBlock.Heading")
                fields["text"] = JsonPrimitive(block.text)
                if (block.level != 2) fields["level"] = JsonPrimitive(block.level)
            }
            is ContentBlock.Image -> {
                fields["type"] = JsonPrimitive("app.novel.source.api.ContentBlock.Image")
                fields["url"] = JsonPrimitive(block.url)
                if (block.alt != null) fields["alt"] = JsonPrimitive(block.alt)
            }
            ContentBlock.Divider -> fields["type"] = JsonPrimitive("app.novel.source.api.ContentBlock.Divider")
        }
        JsonObject(fields)
    }).toString().toByteArray(Charsets.UTF_8)

    fun of(content: ChapterContent): String = "v1:sha256:" +
        MessageDigest.getInstance("SHA-256").digest(canonicalBytes(content))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
