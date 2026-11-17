package eu.kanade.tachiyomi.extension.novel

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class NovelInstallExperimentTest {
    private val hash = "a".repeat(64)
    private val signer = "b".repeat(64)
    private val policy = NovelInstallExperiment("s39Qa", "app.daimei.dev.s39qa", true, hash, setOf(signer))
    private val record = NovelInstallRecord(
        "op", "c".repeat(64), "app.novel.extension.fixtures", 1, setOf(signer), "/artifact", 123, hash,
    )

    @Test fun `only pinned fixture on enabled QA host may request installation`() {
        assertTrue(policy.permits(record))
        for (p in listOf(
            policy.copy(buildType = "release"), policy.copy(buildType = "debug"),
            policy.copy(hostPackage = "app.daimei.dev"), policy.copy(enabled = false),
            policy.copy(apkSha256 = ""), policy.copy(apkSha256 = "not-a-hash"),
            policy.copy(signers = emptySet()), policy.copy(signers = setOf("invalid")),
        )) assertFalse(p.permits(record))
        for (r in listOf(
            record.copy(packageName = "app.novel.extension.matchanovels"),
            record.copy(packageName = "app.novel.extension.skynovels"),
            record.copy(packageName = "app.novel.extension.jabrascan"),
            record.copy(artifactSha256 = "d".repeat(64)),
            record.copy(currentSigners = setOf("e".repeat(64))),
            record.copy(explicitInstallConsent = false), record.copy(priorAbsent = false),
        )) assertFalse(policy.permits(r))
    }
}
