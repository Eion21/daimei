package eu.kanade.tachiyomi.extension.novel

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.Collections

/** Catalog values are untrusted expectations, not verified evidence or trust. */
sealed interface NovelDistributionCatalog {
    val repository: String
    val entries: List<NovelDistributionEntry>
}

sealed interface NovelDistributionEntry {
    val packageName: String
    val longVersionCode: Long
    val versionName: String
    val novelApi: String
    val entryClasses: List<String>
    val minSdk: Int
    val url: String
    val size: Long
    val sha256: String
    val currentSigners: Set<String>
    val abis: Set<String>?
    val sourceIds: Set<String>?
}

enum class NovelDistributionError { SIZE, JSON, SCHEMA }
class NovelDistributionException(val reason: NovelDistributionError) : Exception(reason.name)

object NovelDistributionCatalogCodec {
    const val MAX_BYTES = 2_000_000
    const val MAX_ENTRIES = 1000
    const val MAX_APK_BYTES = 128L * 1024 * 1024
    private val name = Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+")
    private val digest = Regex("[0-9a-f]{64}")
    private val integer = Regex("[1-9][0-9]*")
    private val apiVersion = Regex("[0-9]+\\.[0-9]+")

    private class DecodedCatalog(
        override val repository: String,
        entries: List<NovelDistributionEntry>,
    ) : NovelDistributionCatalog {
        override val entries: List<NovelDistributionEntry> = Collections.unmodifiableList(entries.toList())
    }

    /** Only the decoder can construct this implementation through ordinary Kotlin/Java access. */
    private class DecodedEntry(
        override val packageName: String,
        override val longVersionCode: Long,
        override val versionName: String,
        override val novelApi: String,
        entryClasses: List<String>,
        override val minSdk: Int,
        override val url: String,
        override val size: Long,
        override val sha256: String,
        currentSigners: Set<String>,
        abis: Set<String>?,
        sourceIds: Set<String>?,
    ) : NovelDistributionEntry {
        override val entryClasses: List<String> = Collections.unmodifiableList(entryClasses.toList())
        override val currentSigners: Set<String> = Collections.unmodifiableSet(currentSigners.toSet())
        override val abis: Set<String>? = abis?.let { Collections.unmodifiableSet(it.toSet()) }
        override val sourceIds: Set<String>? = sourceIds?.let { Collections.unmodifiableSet(it.toSet()) }
    }

    /** Revalidate even a reflectively forged implementation; the type is not cryptographic evidence. */
    internal fun isValidExpectation(entry: NovelDistributionEntry): Boolean {
        if (entry !is DecodedEntry) return false
        fun validText(value: String) = value.length in 1..256 && value.none(Char::isISOControl)
        fun validOptional(values: Set<String>?) = values == null ||
            (values.size in 1..32 && values.all(::validText))
        val uri = try {
            URI(entry.url)
        } catch (_: Exception) {
            return false
        }
        return validText(entry.packageName) && name.matches(entry.packageName) &&
            entry.longVersionCode > 0 && validText(entry.versionName) &&
            validText(entry.novelApi) && apiVersion.matches(entry.novelApi) &&
            entry.entryClasses.size in 1..16 && entry.entryClasses.distinct().size == entry.entryClasses.size &&
            entry.entryClasses.all { validText(it) && name.matches(it) } && entry.minSdk > 0 &&
            validText(entry.url) && uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null &&
            uri.rawFragment == null && uri.port in -1..65535 && entry.size in 1..MAX_APK_BYTES &&
            digest.matches(entry.sha256) && entry.currentSigners.size in 1..16 &&
            entry.currentSigners.all(digest::matches) && validOptional(entry.abis) && validOptional(entry.sourceIds)
    }

    fun decode(input: InputStream): NovelDistributionCatalog {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= MAX_BYTES) {
            val n = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (n < 0) break
            if (n == 0) {
                val b = input.read()
                if (b < 0) break
                output.write(b)
            } else {
                output.write(buffer, 0, n)
            }
        }
        if (output.size() > MAX_BYTES) throw NovelDistributionException(NovelDistributionError.SIZE)
        val text = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.toByteArray())).toString()
        } catch (_: CharacterCodingException) {
            throw NovelDistributionException(NovelDistributionError.JSON)
        }
        val root = try {
            checkStructure(text)
            Json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            throw NovelDistributionException(NovelDistributionError.JSON)
        } catch (_: IllegalArgumentException) {
            throw NovelDistributionException(NovelDistributionError.JSON)
        }
        val catalog = root.fields(setOf("protocol", "schemaVersion", "repository", "extensions"))
        schema(catalog.text("protocol") == "daimei-novel-distribution")
        schema(catalog.positive("schemaVersion") == 2L)
        val repository = catalog.text("repository")
        val entries = catalog["extensions"] as? JsonArray ?: invalid()
        schema(entries.size in 1..MAX_ENTRIES)
        val packages = HashSet<String>()
        return DecodedCatalog(
            repository,
            entries.map { item ->
                val e = item.fields(
                    setOf(
                        "packageName",
                        "longVersionCode",
                        "versionName",
                        "novelApi",
                        "entryClasses",
                        "minSdk",
                        "artifact",
                        "currentSigners",
                    ),
                    setOf("abis", "sourceIds"),
                )
                val pkg = e.text("packageName")
                schema(name.matches(pkg) && packages.add(pkg))
                val classes = e.strings("entryClasses", 16)
                schema(classes.all(name::matches))
                val api = e.text("novelApi")
                schema(apiVersion.matches(api))
                val sdk = e.positive("minSdk")
                schema(sdk <= Int.MAX_VALUE)
                val artifact = e.getValue("artifact").fields(setOf("url", "size", "sha256"))
                val url = artifact.text("url")
                val uri = try {
                    URI(url)
                } catch (_: Exception) {
                    invalid()
                }
                schema(
                    uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null &&
                        uri.port in -1..65535,
                )
                val size = artifact.positive("size")
                schema(size <= MAX_APK_BYTES)
                val hash = artifact.text("sha256")
                schema(digest.matches(hash))
                val signers = e.strings("currentSigners", 16)
                schema(signers.all(digest::matches))
                DecodedEntry(
                    pkg, e.positive("longVersionCode"), e.text("versionName"), api, classes, sdk.toInt(), url,
                    size, hash, signers.toSet(), e.optionalStrings("abis", 32), e.optionalStrings("sourceIds", 32),
                )
            },
        )
    }

    private fun checkStructure(text: String) {
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
                        require(keys.add(Json.decodeFromString<String>(text.substring(start, index + 1))))
                    }
                }
            }
            index++
        }
        require(frames.isEmpty())
    }

    private fun JsonElement.fields(required: Set<String>, optional: Set<String> = emptySet()): JsonObject {
        val obj = this as? JsonObject ?: invalid()
        schema(obj.keys.containsAll(required) && obj.keys.all { it in required || it in optional })
        return obj
    }
    private fun JsonElement.string(): String {
        val value = this as? JsonPrimitive ?: invalid()
        schema(value.isString && value.content.length in 1..256 && value.content.none(Char::isISOControl))
        return value.content
    }
    private fun JsonObject.text(key: String) = getValue(key).string()
    private fun JsonObject.positive(key: String): Long {
        val value = getValue(key) as? JsonPrimitive ?: invalid()
        schema(!value.isString && integer.matches(value.content))
        return value.content.toLongOrNull()?.takeIf { it > 0 } ?: invalid()
    }
    private fun JsonObject.strings(key: String, max: Int): List<String> {
        val array = getValue(key) as? JsonArray ?: invalid()
        schema(array.size in 1..max)
        val values = array.map { it.string() }
        schema(values.distinct().size == values.size)
        return values
    }
    private fun JsonObject.optionalStrings(
        key: String,
        max: Int,
    ) = if (containsKey(key)) strings(key, max).toSet() else null
    private fun schema(ok: Boolean) {
        if (!ok) invalid()
    }
    private fun invalid(): Nothing = throw NovelDistributionException(NovelDistributionError.SCHEMA)
}
