package eu.kanade.tachiyomi.extension.novel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class NovelDistributionCatalogTest {
    private val hash = "a".repeat(64)
    private val entry = """{"packageName":"app.novel.extension.matchanovels","longVersionCode":2,""" +
        """"versionName":"2","novelApi":"1.1","entryClasses":["app.novel.extension.matchanovels.Source"],""" +
        """"minSdk":26,"artifact":{"url":"https://example.invalid/a.apk","size":100,"sha256":"$hash"},""" +
        """"currentSigners":["$hash"]}"""
    private fun catalog(
        entries: String = entry,
    ) = """{"protocol":"daimei-novel-distribution","schemaVersion":2,"repository":"example","extensions":[$entries]}"""
    private fun decode(text: String) = NovelDistributionCatalogCodec.decode(text.byteInputStream())
    private fun reject(text: String, reason: NovelDistributionError = NovelDistributionError.SCHEMA) {
        val bytes = text.toByteArray()
        val before = bytes.copyOf()
        val error =
            assertThrows(NovelDistributionException::class.java) {
                NovelDistributionCatalogCodec.decode(bytes.inputStream())
            }
        assertEquals(reason, error.reason)
        assertTrue(before.contentEquals(bytes))
    }
    private fun replace(old: String, new: String) = catalog().replace(old, new)

    @Test fun `novelApi 1-3 is accepted`() {
        assertEquals("1.3", decode(replace("\"novelApi\":\"1.1\"", "\"novelApi\":\"1.3\"")).entries.single().novelApi)
    }

    @Test fun `novelApi 1-4 is rejected`() = reject(replace("\"novelApi\":\"1.1\"", "\"novelApi\":\"1.4\""))

    @Test fun `novelApi 2-0 is rejected`() = reject(replace("\"novelApi\":\"1.1\"", "\"novelApi\":\"2.0\""))

    @Test fun `valid distribution retains expectations`() {
        val value = decode(catalog())
        assertEquals("example", value.repository)
        assertEquals("app.novel.extension.matchanovels", value.entries.single().packageName)
        assertEquals(100L, value.entries.single().size)
    }

    @Test fun `optional declarations remain untrusted`() {
        val value = decode(
            replace("\"minSdk\":26", "\"abis\":[\"arm64-v8a\"],\"sourceIds\":[\"matchanovels\"],\"minSdk\":26"),
        ).entries.single()
        assertEquals(setOf("arm64-v8a"), value.abis)
        assertEquals(setOf("matchanovels"), value.sourceIds)
    }

    @Test fun `unknown protocol rejected`() = reject(replace("daimei-novel-distribution", "other"))

    @Test fun `old schema rejected`() = reject(replace("\"schemaVersion\":2", "\"schemaVersion\":1"))

    @Test fun `unknown schema rejected`() = reject(replace("\"schemaVersion\":2", "\"schemaVersion\":3"))

    @Test fun `unknown root key rejected`() = reject(
        replace("\"repository\":\"example\"", "\"repository\":\"example\",\"extra\":true"),
    )

    @Test fun `unknown nested key rejected`() = reject(replace("\"size\":100", "\"size\":100,\"other\":1"))

    @Test fun `missing required field rejected`() = reject(replace("\"repository\":\"example\",", ""))

    @Test fun `wrong version type rejected`() = reject(replace("\"schemaVersion\":2", "\"schemaVersion\":\"2\""))

    @Test fun `fractional integer rejected`() = reject(replace("\"longVersionCode\":2", "\"longVersionCode\":2.0"))

    @Test fun `boolean integer rejected`() = reject(replace("\"minSdk\":26", "\"minSdk\":true"))

    @Test fun `duplicate key rejected`() = reject(
        replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2"),
        NovelDistributionError.JSON,
    )

    @Test fun `escaped duplicate key rejected`() = reject(
        replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schema\\u0056ersion\":2"),
        NovelDistributionError.JSON,
    )

    @Test fun `duplicate packages rejected`() = reject(catalog("$entry,$entry"))

    @Test fun `zero version rejected`() = reject(replace("\"longVersionCode\":2", "\"longVersionCode\":0"))

    @Test fun `overflow version rejected`() = reject(
        replace("\"longVersionCode\":2", "\"longVersionCode\":9223372036854775808"),
    )

    @Test fun `zero SDK rejected`() = reject(replace("\"minSdk\":26", "\"minSdk\":0"))

    @Test fun `zero length rejected`() = reject(replace("\"size\":100", "\"size\":0"))

    @Test fun `oversize artifact rejected`() = reject(
        replace("\"size\":100", "\"size\":${NovelDistributionCatalogCodec.MAX_APK_BYTES + 1}"),
    )

    @Test fun `uppercase digest rejected`() = reject(replace(hash, "A".repeat(64)))

    @Test fun `short digest rejected`() = reject(replace(hash, "a".repeat(63)))

    @Test fun `empty signer set rejected`() = reject(replace("\"currentSigners\":[\"$hash\"]", "\"currentSigners\":[]"))

    @Test fun `duplicate signers rejected`() = reject(
        replace("\"currentSigners\":[\"$hash\"]", "\"currentSigners\":[\"$hash\",\"$hash\"]"),
    )

    @Test fun `malformed signer digest rejected`() = reject(
        replace("\"currentSigners\":[\"$hash\"]", "\"currentSigners\":[\"invalid\"]"),
    )

    @Test fun `empty entry class list rejected`() = reject(
        replace("\"entryClasses\":[\"app.novel.extension.matchanovels.Source\"]", "\"entryClasses\":[]"),
    )

    @Test fun `null optional source list rejected`() = reject(
        replace("\"minSdk\":26", "\"sourceIds\":null,\"minSdk\":26"),
    )

    // Mirrors the legacy prepare-official-catalog.py bug: spreading a plan's
    // "abis": null into the catalog produced JSON the real codec rejects.
    @Test fun `null optional abis list rejected`() = reject(
        replace("\"minSdk\":26", "\"abis\":null,\"minSdk\":26"),
    )

    // Pins the exact field layout produced by prepare-official-catalog.py for
    // the three official Beta 1 extensions: null plan optionals are omitted
    // (no "abis" key), sourceIds are present, and artifact URLs are immutable
    // Raw HTTPS paths pinned to a full 40-hex commit. Values below are
    // format-only test vectors, not real artifacts, hashes, or URLs.
    @Test fun `official finalize catalog structure decodes for all three extensions`() {
        val commit = "0123456789abcdef0123456789abcdef01234567"
        fun official(
            pkg: String,
            sourceId: String,
            api: String,
            klass: String,
            version: Long,
            name: String,
            file: String,
        ): String {
            return """{"packageName":"$pkg","longVersionCode":$version,"versionName":"$name",""" +
                """"novelApi":"$api","entryClasses":["$klass"],"minSdk":26,""" +
                """"artifact":{"url":"https://raw.githubusercontent.com/Daimei-Extensions/""" +
                """extensions-repo/$commit/distribution/official/s39/$file",""" +
                """"size":100,"sha256":"$hash"},"currentSigners":["$hash"],""" +
                """"sourceIds":["$sourceId"]}"""
        }
        val text = """{"protocol":"daimei-novel-distribution","schemaVersion":2,""" +
            """"repository":"Daimei-Extensions/extensions-repo","extensions":[""" +
            official(
                "app.novel.extension.matchanovels", "matchanovels", "1.1",
                "app.novel.extension.matchanovels.MatchaNovelsSource", 3, "1.0.2", "matcha-signed.apk",
            ) + "," +
            official(
                "app.novel.extension.skynovels", "skynovels", "1.1",
                "app.novel.extension.skynovels.SkyNovelsSource", 3, "1.0.2", "skynovels-signed.apk",
            ) + "," +
            official(
                "app.novel.extension.jabrascan", "jabrascan", "1.3",
                "app.novel.extension.jabrascan.JabraScanSource", 4, "1.0.3", "jabrascan-signed.apk",
            ) + "]}";
        val catalog = decode(text)
        assertEquals("Daimei-Extensions/extensions-repo", catalog.repository)
        assertEquals(3, catalog.entries.size)
        val byPackage = catalog.entries.associateBy { it.packageName }
        assertEquals(setOf("matchanovels"), byPackage["app.novel.extension.matchanovels"]!!.sourceIds)
        assertEquals(setOf("skynovels"), byPackage["app.novel.extension.skynovels"]!!.sourceIds)
        assertEquals(setOf("jabrascan"), byPackage["app.novel.extension.jabrascan"]!!.sourceIds)
        assertEquals("1.3", byPackage["app.novel.extension.jabrascan"]!!.novelApi)
        assertEquals(
            "https://raw.githubusercontent.com/Daimei-Extensions/extensions-repo/" +
                "$commit/distribution/official/s39/jabrascan-signed.apk",
            byPackage["app.novel.extension.jabrascan"]!!.url,
        )
    }

    @Test fun `HTTP rejected`() = reject(replace("https://example.invalid", "http://example.invalid"))

    @Test fun `credentials rejected`() = reject(replace("https://example.invalid", "https://user:pass@example.invalid"))

    @Test fun `fragment rejected`() = reject(replace("a.apk", "a.apk#fragment"))

    @Test fun `empty entries rejected`() = reject(catalog(""))
    private fun uniqueEntries(count: Int) = List(count) { index ->
        entry.replace("app.novel.extension.matchanovels\"", "app.novel.extension.matchanovels$index\"")
    }.joinToString()

    @Test fun `exact entry count limit accepted`() {
        val json = catalog(uniqueEntries(NovelDistributionCatalogCodec.MAX_ENTRIES))
        assertTrue(json.toByteArray().size < NovelDistributionCatalogCodec.MAX_BYTES)
        assertEquals(NovelDistributionCatalogCodec.MAX_ENTRIES, decode(json).entries.size)
    }

    @Test fun `entry count limit rejected`() {
        val json = catalog(uniqueEntries(NovelDistributionCatalogCodec.MAX_ENTRIES + 1))
        assertTrue(json.toByteArray().size < NovelDistributionCatalogCodec.MAX_BYTES)
        reject(json)
    }

    @Test fun `overlong string rejected`() = reject(
        replace("\"repository\":\"example\"", "\"repository\":\"${"x".repeat(257)}\""),
    )

    @Test fun `maximum length string accepted`() {
        assertEquals(
            "x".repeat(256),
            decode(replace("\"repository\":\"example\"", "\"repository\":\"${"x".repeat(256)}\"")).repository,
        )
    }

    @Test fun `malformed JSON rejected`() = reject("{", NovelDistributionError.JSON)

    @Test fun `excessive nesting rejected`() = reject("[".repeat(17) + "]".repeat(17), NovelDistributionError.JSON)

    @Test fun `excessive bytes rejected`() = reject(
        " ".repeat(NovelDistributionCatalogCodec.MAX_BYTES + 1),
        NovelDistributionError.SIZE,
    )

    @Test fun `exact byte limit accepted`() {
        val json = catalog()
        assertEquals(
            "example",
            decode(json + " ".repeat(NovelDistributionCatalogCodec.MAX_BYTES - json.toByteArray().size)).repository,
        )
    }

    @Test fun `invalid UTF8 rejected`() {
        val error = assertThrows(NovelDistributionException::class.java) {
            NovelDistributionCatalogCodec.decode(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        }
        assertEquals(NovelDistributionError.JSON, error.reason)
    }
}
