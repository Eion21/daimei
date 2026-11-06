package eu.kanade.tachiyomi.extension.novel

import eu.kanade.tachiyomi.extension.util.isSupportedNovelApiVersion
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/** S36 metadata only. This type never grants access to an installer or classloader. */
data class NovelCatalog(val extensions: List<NovelCatalogEntry>) {
    val hostCompatible: Boolean get() = false
    val canInstall: Boolean get() = false
}

data class NovelCatalogEntry(
    /** Actual Android package name, never a source ID or presentation key. */
    val id: String,
    val name: String,
    val versionCode: Long,
    val versionName: String,
    val novelApi: String,
    val minSdk: Int,
    val entryClass: String,
    val artifact: NovelCatalogArtifact,
    val signing: NovelCatalogSigning,
)

data class NovelCatalogArtifact(val fileName: String, val sha256: String, val size: Long)

data class NovelCatalogSigning(val certificateSha256: List<String>)

enum class NovelCatalogError { URL, NETWORK, HTTP, SIZE, JSON, SCHEMA }

/** Deliberately excludes response bodies, URLs, credentials and provider exception messages. */
class NovelCatalogException(val reason: NovelCatalogError) : Exception(reason.name)

/** Closed S36 local-test schema, not Mihon's legacy JSON/protobuf store protocol. */
object NovelCatalogCodec {
    const val MAX_BYTES = 2_000_000
    const val MAX_ENTRIES = 1000
    const val MAX_APK_BYTES = 128L * 1024 * 1024

    private val packageName = Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+")
    private val fileName = Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]*\\.apk")
    private val sha256 = Regex("[0-9a-f]{64}")
    private val integer = Regex("[1-9][0-9]*")

    /** Caller owns the stream; at most MAX_BYTES + 1 bytes are consumed, including unknown lengths. */
    fun decode(input: InputStream): NovelCatalog {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (output.size() <= MAX_BYTES) {
            val count = input.read(chunk, 0, minOf(chunk.size, MAX_BYTES + 1 - output.size()))
            if (count == -1) break
            if (count == 0) {
                val byte = input.read()
                if (byte == -1) break
                output.write(byte)
            } else {
                output.write(chunk, 0, count)
            }
        }
        if (output.size() > MAX_BYTES) throw NovelCatalogException(NovelCatalogError.SIZE)
        val text = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.toByteArray())).toString()
        } catch (_: CharacterCodingException) {
            throw NovelCatalogException(NovelCatalogError.JSON)
        }
        val root = try {
            checkJsonStructure(text)
            Json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            throw NovelCatalogException(NovelCatalogError.JSON)
        } catch (_: IllegalArgumentException) {
            throw NovelCatalogException(NovelCatalogError.JSON)
        }
        val catalog = root.fields("schemaVersion status hostCompatible extensions")
        schema(catalog.positive("schemaVersion") == 1L)
        schema(catalog.text("status") == "local-test")
        schema(catalog["hostCompatible"] == JsonPrimitive(false))
        val entries = catalog["extensions"] as? JsonArray ?: invalid()
        schema(entries.size in 1..MAX_ENTRIES)
        val packages = HashSet<String>()
        val filenames = HashSet<String>()
        return NovelCatalog(
            entries.map { element ->
                val entry = element.fields(
                    "id name versionCode versionName novelApi minSdk entryClass artifact signing",
                )
                val id = entry.text("id")
                schema(packageName.matches(id) && packages.add(id))
                val api = entry.text("novelApi")
                schema(api in setOf("1.0", "1.1", "1.2", "1.3") && isSupportedNovelApiVersion(api.toDouble()))
                val sdk = entry.positive("minSdk")
                schema(sdk in 26..Int.MAX_VALUE.toLong())
                val sourceClass = entry.text("entryClass")
                schema(packageName.matches(sourceClass))
                val artifact = entry.getValue("artifact").fields("fileName sha256 size downloadUrl")
                val filename = artifact.text("fileName")
                schema(fileName.matches(filename) && filenames.add(filename))
                schema(artifact["downloadUrl"] === JsonNull)
                val hash = artifact.text("sha256")
                schema(sha256.matches(hash))
                val size = artifact.positive("size")
                schema(size <= MAX_APK_BYTES)
                val signing = entry.getValue("signing").fields("certificateSha256 purpose")
                schema(signing.text("purpose") == "development-only")
                val certs = signing["certificateSha256"] as? JsonArray ?: invalid()
                schema(certs.size in 1..16)
                val fingerprints = certs.map { value ->
                    val cert = value.string()
                    schema(sha256.matches(cert))
                    cert
                }
                schema(fingerprints.distinct().size == fingerprints.size)
                NovelCatalogEntry(
                    id, entry.text("name"), entry.positive("versionCode"), entry.text("versionName"),
                    api, sdk.toInt(), sourceClass,
                    NovelCatalogArtifact(filename, hash, size), NovelCatalogSigning(fingerprints),
                )
            },
        )
    }

    /** Bound nesting before recursive JSON parsing; reject ambiguous duplicate keys, including escaped keys. */
    private fun checkJsonStructure(text: String) {
        val frames = mutableListOf<MutableSet<String>?>()
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '{', '[' -> {
                    require(frames.size < 16)
                    frames.add(if (text[index] == '{') HashSet() else null)
                }
                '}', ']' -> {
                    require(frames.isNotEmpty())
                    frames.removeAt(frames.lastIndex)
                }
                '"' -> {
                    val start = index++
                    while (index < text.length && text[index] != '"') {
                        if (text[index] == '\\') index++
                        index++
                    }
                    require(index < text.length)
                    var next = index + 1
                    while (next < text.length && text[next].isWhitespace()) next++
                    if (next < text.length && text[next] == ':') {
                        val keys = frames.lastOrNull() ?: throw IllegalArgumentException()
                        val key = Json.decodeFromString<String>(text.substring(start, index + 1))
                        require(keys.add(key))
                    }
                }
            }
            index++
        }
        require(frames.isEmpty())
    }

    private fun JsonElement.fields(keys: String): JsonObject {
        val obj = this as? JsonObject ?: invalid()
        schema(obj.keys == keys.split(' ').toSet())
        return obj
    }

    private fun JsonElement.string(): String {
        val value = this as? JsonPrimitive ?: invalid()
        schema(value.isString && value.content.length in 1..256 && value.content.none(Char::isISOControl))
        return value.content
    }

    private fun JsonObject.text(key: String): String = getValue(key).string()

    private fun JsonObject.positive(key: String): Long {
        val value = getValue(key) as? JsonPrimitive ?: invalid()
        schema(!value.isString && integer.matches(value.content))
        return value.content.toLongOrNull()?.takeIf { it > 0 } ?: invalid()
    }

    private fun schema(condition: Boolean) {
        if (!condition) invalid()
    }

    private fun invalid(): Nothing = throw NovelCatalogException(NovelCatalogError.SCHEMA)
}
