package eu.kanade.tachiyomi.extension.novel

/** Fabricated test observations only; no APK or signature is verified here. */
internal object NovelInstallEvidenceTestFixtures {
    const val PACKAGE = "app.novel.extension.matchanovels"
    val signer = "a".repeat(64)
    val other = "b".repeat(64)
    val hash = "c".repeat(64)

    private fun <T> construct(type: Class<T>, vararg args: Any?): T {
        val constructor = type.declaredConstructors.single()
        constructor.isAccessible = true
        return type.cast(constructor.newInstance(*args))
    }

    fun expected(
        packageName: String = PACKAGE,
        signers: Set<String> = setOf(signer),
        abis: Set<String>? = null,
        sources: Set<String>? = null,
    ): NovelDistributionEntry {
        val signersJson = signers.joinToString(",") { "\"$it\"" }
        fun optional(name: String, values: Set<String>?) =
            values?.joinToString(",", prefix = ",\"$name\":[", postfix = "]") { "\"$it\"" } ?: ""
        val declarations = optional("abis", abis) + optional("sourceIds", sources)
        val json = """{"protocol":"daimei-novel-distribution","schemaVersion":2,"repository":"example",""" +
            """"extensions":[{"packageName":"$packageName","longVersionCode":2,"versionName":"2","novelApi":"1.1",""" +
            """"entryClasses":["app.example.Source"],"minSdk":26,""" +
            """"artifact":{"url":"https://example.invalid/a.apk","size":100,"sha256":"$hash"},""" +
            """"currentSigners":[$signersJson]$declarations}]}"""
        return NovelDistributionCatalogCodec.decode(json.byteInputStream()).entries.single()
    }

    /** Deliberately bypasses the decoder solely to exercise the policy boundary. */
    fun forgedExpectation(
        packageName: String = PACKAGE,
        version: Long = 2,
        size: Long = 100,
        sha: String = hash,
        classes: List<String> = listOf("app.example.Source"),
        minSdk: Int = 26,
        url: String = "https://example.invalid/a.apk",
        signers: Set<String> = setOf(signer),
    ): NovelDistributionEntry = construct(
        NovelDistributionCatalogCodec::class.java.declaredClasses.single { it.simpleName == "DecodedEntry" },
        packageName, version, "2", "1.1", classes, minSdk, url, size, sha, signers, null, null,
    ) as NovelDistributionEntry

    fun candidate(
        packageName: String? = PACKAGE,
        version: Long? = 2,
        versionName: String? = "2",
        api: String? = "1.1",
        classes: Set<String>? = setOf("app.example.Source"),
        minSdk: Int? = 26,
        size: Long? = 100,
        sha: String? = hash,
        signers: Set<String>? = setOf(signer),
        history: Set<String>? = emptySet(),
        completeness: NovelSignerCompleteness = NovelSignerCompleteness.COMPLETE,
        rotation: NovelRotation = NovelRotation.NONE,
        layout: NovelApkLayout = NovelApkLayout.UNKNOWN,
        deviceSdk: Int? = 35,
        apkAbis: Set<String>? = emptySet(),
        deviceAbis: Set<String>? = setOf("arm64-v8a"),
        features: Set<String>? = setOf("app.novel.extension"),
        sources: Set<String>? = setOf("matchanovels"),
    ) = construct(
        NovelVerifiedCandidate::class.java, packageName, version, versionName, api, classes, minSdk, size, sha,
        signers, history, completeness, rotation, layout, deviceSdk, apkAbis, deviceAbis, features, sources,
    )

    fun installed(
        packageName: String = PACKAGE,
        version: Long? = 1,
        signers: Set<String>? = setOf(signer),
        history: Set<String>? = emptySet(),
        sources: Set<String>? = setOf("matchanovels"),
        completeness: NovelSignerCompleteness = NovelSignerCompleteness.COMPLETE,
        origin: NovelInstallOrigin = NovelInstallOrigin.SHARED,
        freshness: NovelStateFreshness = NovelStateFreshness.CURRENT,
    ) = construct(
        NovelInstalledSnapshot::class.java, packageName, version, signers, history, sources,
        completeness, origin, freshness,
    )

    fun absent(packageName: String = PACKAGE) = installed(
        packageName = packageName,
        version = null,
        signers = null,
        history = null,
        sources = null,
        completeness = NovelSignerCompleteness.UNKNOWN,
        origin = NovelInstallOrigin.ABSENT,
    )

    fun trust(packageName: String = PACKAGE, version: Long = 2, signers: Set<String> = setOf(signer)) =
        construct(NovelVersionTrust::class.java, packageName, version, signers)
}
