package eu.kanade.tachiyomi.extension.novel

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** AXML structural fixture only; it is not an Android-signed or installable APK. */
internal object NovelBinaryManifestFixture {
    // Exact AndroidManifest.xml from controlled-base.apk.
    // SHA-256: 637a7938fcbe176958860b9a7a6f83fe3d1d0e5d6b0dda525636f1e7d889190b.
    // Source: novel-fixtures-extension-debug.apk; bytes are embedded so tests never read build outputs.
    fun compiledManifest(): ByteArray = java.util.Base64.getDecoder().decode(
        "AwAIACwIAAABABwABAUAAB0AAAAAAAAAAAAAAJAAAAAAAAAAAAAAAA4AAAAaAAAAMgAAAEAAAABeAAAAeAAAAJIAAAC2AAAA" +
            "3AAAAAIBAAA4AQAARgEAAE4BAACGAQAAmAEAAMIBAAD4AQAANAIAAP4CAAAuAwAASAMAAKADAAC0AwAAygMAANwDAAAQBAAA" +
            "RAQAAGAEAAAFAGwAYQBiAGUAbAAAAAQAbgBhAG0AZQAAAAoAZABlAGIAdQBnAGcAYQBiAGwAZQAAAAUAdgBhAGwAdQBlAAAA" +
            "DQBtAGkAbgBTAGQAawBWAGUAcgBzAGkAbwBuAAAACwB2AGUAcgBzAGkAbwBuAEMAbwBkAGUAAAALAHYAZQByAHMAaQBvAG4A" +
            "TgBhAG0AZQAAABAAdABhAHIAZwBlAHQAUwBkAGsAVgBlAHIAcwBpAG8AbgAAABEAZQB4AHQAcgBhAGMAdABOAGEAdABpAHYA" +
            "ZQBMAGkAYgBzAAAAEQBjAG8AbQBwAGkAbABlAFMAZABrAFYAZQByAHMAaQBvAG4AAAAZAGMAbwBtAHAAaQBsAGUAUwBkAGsA" +
            "VgBlAHIAcwBpAG8AbgBDAG8AZABlAG4AYQBtAGUAAAAFADEALgAwAC4AMAAAAAIAMQA3AAAAGgBGAGkAeAB0AHUAcgBlACAA" +
            "bgBvAHYAZQBsAHMAIAAoAGUAeAB0AGUAbgBzAGkAbwBuACkAAAAHAGEAbgBkAHIAbwBpAGQAAAATAGEAcABwAC4AbgBvAHYA" +
            "ZQBsAC4AZQB4AHQAZQBuAHMAaQBvAG4AAAAZAGEAcABwAC4AbgBvAHYAZQBsAC4AZQB4AHQAZQBuAHMAaQBvAG4ALgBjAGwA" +
            "YQBzAHMAAAAcAGEAcABwAC4AbgBvAHYAZQBsAC4AZQB4AHQAZQBuAHMAaQBvAG4ALgBmAGkAeAB0AHUAcgBlAHMAAABjAGEA" +
            "cABwAC4AbgBvAHYAZQBsAC4AZQB4AHQAZQBuAHMAaQBvAG4ALgBmAGkAeAB0AHUAcgBlAHMALgBGAGkAeAB0AHUAcgBlAE4A" +
            "bwB2AGUAbABTAG8AdQByAGMAZQA7AGEAcABwAC4AbgBvAHYAZQBsAC4AZQB4AHQAZQBuAHMAaQBvAG4ALgBmAGkAeAB0AHUA" +
            "cgBlAHMALgBIAHQAdABwAEYAaQB4AHQAdQByAGUATgBvAHYAZQBsAFMAbwB1AHIAYwBlAAAAFgBhAHAAcAAuAG4AbwB2AGUA" +
            "bAAuAGUAeAB0AGUAbgBzAGkAbwBuAEEAcABpAAAACwBhAHAAcABsAGkAYwBhAHQAaQBvAG4AAAAqAGgAdAB0AHAAOgAvAC8A" +
            "cwBjAGgAZQBtAGEAcwAuAGEAbgBkAHIAbwBpAGQALgBjAG8AbQAvAGEAcABrAC8AcgBlAHMALwBhAG4AZAByAG8AaQBkAAAA" +
            "CABtAGEAbgBpAGYAZQBzAHQAAAAJAG0AZQB0AGEALQBkAGEAdABhAAAABwBwAGEAYwBrAGEAZwBlAAAAGABwAGwAYQB0AGYA" +
            "bwByAG0AQgB1AGkAbABkAFYAZQByAHMAaQBvAG4AQwBvAGQAZQAAABgAcABsAGEAdABmAG8AcgBtAEIAdQBpAGwAZABWAGUA" +
            "cgBzAGkAbwBuAE4AYQBtAGUAAAAMAHUAcwBlAHMALQBmAGUAYQB0AHUAcgBlAAAACAB1AHMAZQBzAC0AcwBkAGsAAACAAQgA" +
            "NAAAAAEAAQEDAAEBDwABASQAAQEMAgEBGwIBARwCAQFwAgEB6gQBAXIFAQFzBQEBAAEQABgAAAACAAAA/////w4AAAAVAAAA" +
            "AgEQALAAAAACAAAA//////////8WAAAAFAAUAAcAAAAAAAAAFQAAAAUAAAD/////CAAAEAEAAAAVAAAABgAAAAsAAAAIAAAD" +
            "CwAAABUAAAAJAAAA/////wgAABAlAAAAFQAAAAoAAAAMAAAACAAAAwwAAAD/////GAAAABEAAAAIAAADEQAAAP////8ZAAAA" +
            "/////wgAABAlAAAA/////xoAAAD/////CAAAEBEAAAACARAATAAAAAcAAAD//////////xwAAAAUABQAAgAAAAAAAAAVAAAA" +
            "BAAAAP////8IAAAQGgAAABUAAAAHAAAA/////wgAABAkAAAAAwEQABgAAAAHAAAA//////////8cAAAAAgEQADgAAAAQAAAA" +
            "//////////8bAAAAFAAUAAEAAAAAAAAAFQAAAAEAAAAPAAAACAAAAw8AAAADARAAGAAAABAAAAD//////////xsAAAACARAA" +
            "YAAAABIAAAD//////////xQAAAAUABQAAwAAAAAAAAAVAAAAAAAAAA0AAAAIAAADDQAAABUAAAACAAAA/////wgAABL/////" +
            "FQAAAAgAAAD/////CAAAEgAAAAACARAATAAAACIAAAD//////////xcAAAAUABQAAgAAAAAAAAAVAAAAAQAAABAAAAAIAAAD" +
            "EAAAABUAAAADAAAAEgAAAAgAAAMSAAAAAwEQABgAAAAiAAAA//////////8XAAAAAgEQAEwAAAArAAAA//////////8XAAAA" +
            "FAAUAAIAAAAAAAAAFQAAAAEAAAATAAAACAAAAxMAAAAVAAAAAwAAAP////8IAAAEzcyMPwMBEAAYAAAAKwAAAP//////////" +
            "FwAAAAMBEAAYAAAAEgAAAP//////////FAAAAAMBEAAYAAAAAgAAAP//////////FgAAAAEBEAAYAAAAAgAAAP////8OAAAA" +
            "FQAAAA==",
    )
    fun make(
        pkg: String = "app.novel.example",
        split: String? = null,
        resourceFlag: Boolean = false,
        usesSplit: Boolean = false,
        duplicatePackage: Boolean = false,
    ): ByteArray {
        val strings = mutableListOf(
            "manifest",
            "package",
            pkg,
            "android",
            "http://schemas.android.com/apk/res/android",
            "isFeatureSplit",
            "isSplitRequired",
            "application",
            "uses-split",
            "split",
        )
        if (split != null) strings.add(split)
        fun index(value: String) = strings.indexOf(value)
        fun shorts(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                values.forEach { putShort(it.toShort()) }
            }
            .array()
        fun ints(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                values.forEach { putInt(it) }
            }
            .array()
        fun chunk(type: Int, header: Int, content: ByteArray): ByteArray =
            shorts(type, header) + ints(content.size + 8) + content
        fun node(type: Int, body: ByteArray) = chunk(type, 16, ints(1, -1) + body)
        val data = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        for (s in strings) {
            val b = s.toByteArray(Charsets.UTF_8)
            require(s.length < 128 && b.size < 128)
            offsets.add(data.size())
            data.write(s.length)
            data.write(b.size)
            data.write(b)
            data.write(0)
        }
        while (data.size() % 4 != 0) data.write(0)
        val poolBody = ints(strings.size, 0, 0x100, 28 + strings.size * 4, 0) +
            ints(*offsets.toIntArray()) + data.toByteArray()
        val pool = chunk(1, 28, poolBody)
        val android = index("http://schemas.android.com/apk/res/android")
        fun attribute(ns: Int, name: String, raw: Int, type: Int, value: Int) =
            ints(ns, index(name), raw) + shorts(8) + byteArrayOf(0, type.toByte()) + ints(value)
        val attrs = mutableListOf(
            attribute(-1, "package", index(pkg), 3, index(pkg)),
            attribute(android, "isFeatureSplit", -1, if (resourceFlag) 1 else 18, if (resourceFlag) 0x7f010001 else 0),
            attribute(android, "isSplitRequired", -1, 18, 0),
        )
        if (split != null) attrs.add(attribute(-1, "split", index(split), 3, index(split)))
        if (duplicatePackage) attrs.add(attribute(-1, "package", index(pkg), 3, index(pkg)))
        fun start(name: String, attributes: List<ByteArray>) = node(
            0x102,
            ints(-1, index(name)) +
                shorts(20, 20, attributes.size, 0, 0, 0) +
                attributes.fold(byteArrayOf()) { a, b -> a + b },
        )
        fun end(name: String) = node(0x103, ints(-1, index(name)))
        val namespaceStart = node(0x100, ints(index("android"), android))
        val namespaceEnd = node(0x101, ints(index("android"), android))
        val child = if (usesSplit) "uses-split" else "application"
        val body = pool + namespaceStart + start("manifest", attrs) +
            start(child, emptyList()) + end(child) + end("manifest") + namespaceEnd
        return chunk(3, 8, body)
    }
}
