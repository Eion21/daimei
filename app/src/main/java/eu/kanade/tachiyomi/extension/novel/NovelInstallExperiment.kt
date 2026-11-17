package eu.kanade.tachiyomi.extension.novel

internal data class NovelInstallExperiment(
    val buildType: String,
    val hostPackage: String,
    val enabled: Boolean,
    val apkSha256: String,
    val signers: Set<String>,
) {
    private val hex = Regex("[0-9a-f]{64}")

    val active: Boolean get() = enabled && buildType == "s39Qa" && hostPackage == "app.daimei.dev.s39qa" &&
        hex.matches(apkSha256) && signers.size in 1..16 && signers.all(hex::matches)

    fun permits(record: NovelInstallRecord): Boolean = active && record.priorAbsent &&
        record.explicitInstallConsent && record.packageName == "app.novel.extension.fixtures" &&
        record.artifactSize in 1..NovelDistributionCatalogCodec.MAX_APK_BYTES &&
        record.artifactSha256 == apkSha256 && record.currentSigners == signers
}
