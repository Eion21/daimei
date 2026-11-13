package eu.kanade.tachiyomi.extension.novel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.util.zip.ZipFile

/** Structural observations only; none imply signature verification or installation authority. */
internal sealed interface NovelStructuralLayout {
    data class Base(val packageName: String) : NovelStructuralLayout
    data class Split(val packageName: String) : NovelStructuralLayout
    data object Malformed : NovelStructuralLayout
    data object Unknown : NovelStructuralLayout
}

/** Strict, deliberately limited binary XML reader. Unsupported encodings/chunks fail closed. */
internal object NovelApkLayoutInspector {
    private const val MAX_MANIFEST = 1024 * 1024
    private const val ANDROID = "http://schemas.android.com/apk/res/android"

    // Framework attribute IDs: only these exact name/ID pairs are understood.
    private val knownFrameworkAttributes = mapOf(
        0x01010001 to "label", 0x01010003 to "name", 0x0101000f to "debuggable",
        0x01010024 to "value", 0x0101020c to "minSdkVersion", 0x0101021b to "versionCode",
        0x0101021c to "versionName", 0x01010270 to "targetSdkVersion", 0x010104ea to "extractNativeLibs",
        0x01010572 to "compileSdkVersion", 0x01010573 to "compileSdkVersionCodename",
    )
    private class Unknown : Exception()
    private class Bad : Exception()

    fun inspect(path: Path): NovelStructuralLayout = try {
        val bytes = ZipFile(path.toFile()).use { zip ->
            val entries = zip.entries().asSequence().filter { it.name == "AndroidManifest.xml" }.toList()
            if (entries.size != 1 || entries[0].isDirectory) throw Bad()
            zip.getInputStream(entries[0]).use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > MAX_MANIFEST) throw Unknown()
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        }
        parse(bytes)
    } catch (_: Unknown) {
        NovelStructuralLayout.Unknown
    } catch (_: Exception) {
        NovelStructuralLayout.Malformed
    }

    private fun parse(bytes: ByteArray): NovelStructuralLayout {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun u16(p: Int): Int {
            if (p < 0 || p > bytes.size - 2) throw Bad()
            return b.getShort(p).toInt() and 0xffff
        }
        fun i32(p: Int): Int {
            if (p < 0 || p > bytes.size - 4) throw Bad()
            return b.getInt(p)
        }
        if (bytes.size < 8 || u16(0) != 3 || u16(2) != 8 || i32(4) != bytes.size) throw Bad()
        var strings: List<String>? = null
        var resourceIds: List<Int>? = null
        val elements = mutableListOf<Pair<Int, Int>>()
        val namespaces = mutableListOf<Pair<Int, Int>>()
        var rootSeen = false
        var rootClosed = false
        var pkg: String? = null
        var split = false
        var cursor = 8
        var chunks = 0
        fun string(index: Int): String = strings?.getOrNull(index) ?: throw Bad()
        fun optional(index: Int): String? = if (index == -1) null else string(index)
        while (cursor < bytes.size) {
            if (++chunks > 4096 || cursor > bytes.size - 8) throw Bad()
            val type = u16(cursor)
            val header = u16(cursor + 2)
            val size = i32(cursor + 4)
            if (header < 8 || size < header || size > bytes.size - cursor || size % 4 != 0) throw Bad()
            val end = cursor + size
            when (type) {
                1 -> {
                    if (strings != null || rootSeen || header != 28 || size < 28) throw Bad()
                    val count = i32(cursor + 8)
                    val styles = i32(cursor + 12)
                    val flags = i32(cursor + 16)
                    val start = i32(cursor + 20)
                    val stylesStart = i32(cursor + 24)
                    if (
                        count !in 1..2048 ||
                        styles != 0 ||
                        stylesStart != 0 ||
                        flags !in setOf(0, 0x100)
                    ) {
                        throw Unknown()
                    }
                    if (start < 28 + count * 4 || start > size) throw Bad()
                    val list = ArrayList<String>(count)
                    for (i in 0 until count) {
                        val offset = i32(cursor + 28 + i * 4)
                        if (offset < 0 || offset >= size - start) throw Bad()
                        var p = cursor + start + offset
                        fun length(): Int {
                            if (p >= end) throw Bad()
                            val a = bytes[p++].toInt() and 255
                            if (a and 128 == 0) return a
                            if (p >= end) throw Bad()
                            return ((a and 127) shl 8) or (bytes[p++].toInt() and 255)
                        }
                        val text = if (flags == 0x100) {
                            val utf16Length = length()
                            val byteLength = length()
                            if (byteLength > end - p - 1 || bytes[p + byteLength].toInt() != 0) throw Bad()
                            val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes, p, byteLength))
                                .toString()
                            if (decoded.length != utf16Length) throw Bad()
                            decoded
                        } else {
                            if (p > end - 2) throw Bad()
                            var utf16Length = u16(p)
                            p += 2
                            if (utf16Length and 0x8000 != 0) {
                                if (p > end - 2) throw Bad()
                                utf16Length = ((utf16Length and 0x7fff) shl 16) or u16(p)
                                p += 2
                            }
                            if (utf16Length > (end - p - 2) / 2 || p > end - 2) throw Bad()
                            val byteLength = utf16Length * 2
                            if (u16(p + byteLength) != 0) throw Bad()
                            val decoded = Charsets.UTF_16LE.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes, p, byteLength))
                                .toString()
                            if (decoded.length != utf16Length) throw Bad()
                            decoded
                        }
                        list.add(text)
                    }
                    strings = list
                }
                0x180 -> {
                    if (
                        strings == null ||
                        resourceIds != null ||
                        rootSeen ||
                        header != 8 ||
                        (size - 8) % 4 != 0 ||
                        (size - 8) / 4 > strings.size
                    ) {
                        throw Bad()
                    }
                    val ids = (cursor + 8 until end step 4).map(::i32)
                    val seen = HashSet<Int>()
                    for ((index, id) in ids.withIndex()) {
                        if (id != 0 && (knownFrameworkAttributes[id] != string(index) || !seen.add(id))) throw Unknown()
                    }
                    resourceIds = ids
                }
                0x100, 0x101 -> {
                    if (strings == null || header != 16 || size != 24) throw Bad()
                    val prefix = i32(cursor + 16)
                    val uri = i32(cursor + 20)
                    string(prefix)
                    string(uri)
                    if (type == 0x100) {
                        if (rootClosed || namespaces.size >= 64) throw Bad()
                        namespaces.add(prefix to uri)
                    } else {
                        if (
                            namespaces.isEmpty() ||
                            namespaces.removeAt(namespaces.lastIndex) != (prefix to uri)
                        ) {
                            throw Bad()
                        }
                    }
                }
                0x102 -> {
                    if (strings == null || header != 16 || size < 36 || rootClosed || elements.size >= 64) throw Bad()
                    val ns = i32(cursor + 16)
                    val name = i32(cursor + 20)
                    val nameText = string(name)
                    optional(ns)
                    val attrStart = u16(cursor + 24)
                    val attrSize = u16(cursor + 26)
                    val count = u16(cursor + 28)
                    if (attrStart != 20 || attrSize != 20 || count > 256 || 36 + count * 20 != size ||
                        u16(cursor + 30) > count || u16(cursor + 32) > count || u16(cursor + 34) > count
                    ) {
                        throw Bad()
                    }
                    val isRoot = elements.isEmpty()
                    if (isRoot) {
                        if (rootSeen || ns != -1 || nameText != "manifest") throw Bad()
                        rootSeen = true
                    } else if (nameText == "manifest") {
                        throw Bad()
                    }
                    if (nameText == "uses-split") split = true
                    val names = HashSet<String>()
                    for (i in 0 until count) {
                        val a = cursor + 36 + i * 20
                        val attrNs = optional(i32(a))
                        val nameIndex = i32(a + 4)
                        val attrName = string(nameIndex)
                        val raw = optional(i32(a + 8))
                        if (
                            resourceIds?.getOrNull(nameIndex)?.let { it != 0 && attrNs != ANDROID } == true
                        ) {
                            throw Unknown()
                        }
                        if (!names.add(attrName)) throw Bad()
                        if (attrNs != null && namespaces.none { string(it.second) == attrNs }) throw Bad()
                        if (u16(a + 12) != 8 || bytes[a + 14].toInt() != 0) throw Bad()
                        val valueType = bytes[a + 15].toInt() and 255
                        val value = i32(a + 16)
                        val text = if (valueType == 3) string(value) else null
                        if (raw != null && text != null && raw != text) throw Bad()
                        if (isRoot) {
                            when (attrName) {
                                "package" -> {
                                    if (attrNs != null || text == null || text.isBlank()) throw Unknown()
                                    pkg = text
                                }
                                "split", "configForSplit", "requiredSplitTypes" -> {
                                    if ((attrName == "split" && attrNs != null) ||
                                        (attrName != "split" && attrNs != ANDROID) || text == null
                                    ) {
                                        throw Unknown()
                                    }
                                    if (text.isNotEmpty()) split = true else throw Unknown()
                                }
                                "isFeatureSplit", "isSplitRequired" -> {
                                    if (
                                        attrNs != ANDROID ||
                                        valueType != 18 ||
                                        value !in listOf(0, -1, 1)
                                    ) {
                                        throw Unknown()
                                    }
                                    if (value != 0) split = true
                                }
                            }
                        }
                    }
                    elements.add(ns to name)
                }
                0x103 -> {
                    if (strings == null || header != 16 || size != 24 || elements.isEmpty()) throw Bad()
                    val pair = i32(cursor + 16) to i32(cursor + 20)
                    if (elements.removeAt(elements.lastIndex) != pair) throw Bad()
                    if (elements.isEmpty()) rootClosed = true
                }
                else -> throw Unknown()
            }
            cursor = end
        }
        if (
            cursor != bytes.size ||
            !rootSeen ||
            !rootClosed ||
            elements.isNotEmpty() ||
            namespaces.isNotEmpty()
        ) {
            throw Bad()
        }
        val name = pkg ?: throw Unknown()
        return if (split) NovelStructuralLayout.Split(name) else NovelStructuralLayout.Base(name)
    }
}
