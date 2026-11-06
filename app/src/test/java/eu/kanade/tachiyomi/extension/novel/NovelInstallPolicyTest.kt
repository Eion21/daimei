package eu.kanade.tachiyomi.extension.novel

import eu.kanade.tachiyomi.extension.novel.NovelInstallEvidenceTestFixtures.PACKAGE
import eu.kanade.tachiyomi.extension.novel.NovelInstallEvidenceTestFixtures.absent
import eu.kanade.tachiyomi.extension.novel.NovelInstallEvidenceTestFixtures.candidate
import eu.kanade.tachiyomi.extension.novel.NovelInstallEvidenceTestFixtures.expected
import eu.kanade.tachiyomi.extension.novel.NovelInstallEvidenceTestFixtures.forgedExpectation
import eu.kanade.tachiyomi.extension.novel.NovelInstallEvidenceTestFixtures.installed
import eu.kanade.tachiyomi.extension.novel.NovelInstallEvidenceTestFixtures.other
import eu.kanade.tachiyomi.extension.novel.NovelInstallEvidenceTestFixtures.signer
import eu.kanade.tachiyomi.extension.novel.NovelInstallEvidenceTestFixtures.trust
import eu.kanade.tachiyomi.extension.util.isSupportedNovelApiVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/** Fabricated observations test decisions, not real APK cryptography. */
class NovelInstallPolicyTest {
    private fun evaluate(
        operation: NovelInstallOperation = NovelInstallOperation.UPDATE,
        entry: NovelDistributionEntry = expected(),
        candidate: NovelVerifiedCandidate? = candidate(layout = NovelApkLayout.STANDALONE),
        installed: NovelInstalledSnapshot? = installed(),
        trust: NovelVersionTrust? = trust(),
    ) = NovelInstallPolicy.evaluate(operation, entry, candidate, installed, trust)

    private fun rejects(reason: NovelInstallReason, result: NovelInstallDecision) {
        assertTrue(result is NovelInstallDecision.Rejected && reason in result.reasons, "$reason: $result")
    }

    @Test fun `coherent new install is eligible only for policy`() =
        assertEquals(
            NovelInstallDecision.Eligible,
            evaluate(operation = NovelInstallOperation.NEW, installed = absent()),
        )

    @Test fun `higher version same current signers and source identity is eligible`() =
        assertEquals(NovelInstallDecision.Eligible, evaluate())

    @Test fun `missing candidate is rejected`() = rejects(NovelInstallReason.INCOMPLETE, evaluate(candidate = null))

    @Test fun `unknown installed state is rejected`() = rejects(
        NovelInstallReason.INCOMPLETE,
        evaluate(installed = null),
    )

    @Test fun `candidate package must match catalog`() = rejects(
        NovelInstallReason.PACKAGE,
        evaluate(candidate = candidate(packageName = "other.package")),
    )

    @Test fun `candidate size must match catalog`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(candidate = candidate(size = 101)),
    )

    @Test fun `candidate digest must match catalog`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(candidate = candidate(sha = other)),
    )

    @Test fun `candidate version code must match catalog`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(candidate = candidate(version = 3)),
    )

    @Test fun `candidate version name must match catalog`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(candidate = candidate(versionName = "other")),
    )

    @Test fun `candidate API must match catalog`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(candidate = candidate(api = "1.2")),
    )

    @Test fun `candidate entry classes must match catalog`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(candidate = candidate(classes = setOf("other.Source"))),
    )

    @Test fun `candidate SDK must match catalog`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(candidate = candidate(minSdk = 27)),
    )

    @Test fun `unsigned candidate is rejected`() = rejects(
        NovelInstallReason.SIGNATURE,
        evaluate(candidate = candidate(signers = emptySet())),
    )

    @Test fun `unknown signers are rejected`() = rejects(
        NovelInstallReason.SIGNATURE,
        evaluate(candidate = candidate(signers = null)),
    )

    @Test fun `partial signer evidence is rejected`() = rejects(
        NovelInstallReason.SIGNATURE,
        evaluate(candidate = candidate(completeness = NovelSignerCompleteness.INCOMPLETE)),
    )

    @Test fun `catalog signers must equal observed signers`() = rejects(
        NovelInstallReason.SIGNATURE,
        evaluate(candidate = candidate(signers = setOf(other))),
    )

    @Test fun `unsupported API is rejected`() = rejects(
        NovelInstallReason.API,
        evaluate(candidate = candidate(api = "9.9")),
    )

    @Test fun `platform SDK is required and compatible`() = rejects(
        NovelInstallReason.COMPATIBILITY,
        evaluate(candidate = candidate(deviceSdk = 25)),
    )

    @Test fun `unknown platform ABI is rejected`() = rejects(
        NovelInstallReason.COMPATIBILITY,
        evaluate(candidate = candidate(deviceAbis = null)),
    )

    @Test fun `incompatible native ABI is rejected`() = rejects(
        NovelInstallReason.COMPATIBILITY,
        evaluate(candidate = candidate(apkAbis = setOf("x86"))),
    )

    @Test fun `dual manga novel feature is rejected`() = rejects(
        NovelInstallReason.COMPATIBILITY,
        evaluate(candidate = candidate(features = setOf("app.novel.extension", "tachiyomi.extension"))),
    )

    @Test fun `new install does not implicitly become update`() = rejects(
        NovelInstallReason.COLLISION,
        evaluate(operation = NovelInstallOperation.NEW),
    )

    @Test fun `private origin is rejected`() = rejects(
        NovelInstallReason.COLLISION,
        evaluate(installed = installed(origin = NovelInstallOrigin.PRIVATE)),
    )

    @Test fun `unknown origin is rejected`() = rejects(
        NovelInstallReason.COLLISION,
        evaluate(installed = installed(origin = NovelInstallOrigin.UNKNOWN)),
    )

    @Test fun `contradictory absence is rejected`() = rejects(
        NovelInstallReason.INCOMPLETE,
        evaluate(operation = NovelInstallOperation.NEW, installed = installed(origin = NovelInstallOrigin.ABSENT)),
    )

    @Test fun `missing trust is rejected`() = rejects(NovelInstallReason.TRUST, evaluate(trust = null))

    @Test fun `old version trust is rejected`() = rejects(
        NovelInstallReason.TRUST,
        evaluate(trust = trust(version = 1)),
    )

    @Test fun `wrong package trust is rejected`() = rejects(
        NovelInstallReason.TRUST,
        evaluate(trust = trust(packageName = "other.package")),
    )

    @Test fun `wrong signer trust is rejected`() = rejects(
        NovelInstallReason.TRUST,
        evaluate(trust = trust(signers = setOf(other))),
    )

    @Test fun `equal version is rejected`() = rejects(
        NovelInstallReason.VERSION,
        evaluate(installed = installed(version = 2)),
    )

    @Test fun `downgrade is rejected`() = rejects(
        NovelInstallReason.VERSION,
        evaluate(installed = installed(version = 3)),
    )

    @Test fun `different installed package is rejected`() = rejects(
        NovelInstallReason.PACKAGE,
        evaluate(installed = installed(packageName = "other.package")),
    )

    @Test fun `changed signer is rejected`() = rejects(
        NovelInstallReason.SIGNER_CONTINUITY,
        evaluate(installed = installed(signers = setOf(other))),
    )

    @Test fun `old multisigner subset is rejected`() = rejects(
        NovelInstallReason.SIGNER_CONTINUITY,
        evaluate(installed = installed(signers = setOf(signer, other))),
    )

    @Test fun `incomplete installed signer set is rejected`() = rejects(
        NovelInstallReason.SIGNER_CONTINUITY,
        evaluate(installed = installed(completeness = NovelSignerCompleteness.INCOMPLETE)),
    )

    @Test fun `ancestor cannot substitute for current signer`() = rejects(
        NovelInstallReason.SIGNER_CONTINUITY,
        evaluate(candidate = candidate(history = setOf(other)), installed = installed(signers = setOf(other))),
    )

    @Test fun `declared rotation is rejected`() = rejects(
        NovelInstallReason.ROTATION,
        evaluate(candidate = candidate(rotation = NovelRotation.DECLARED)),
    )

    @Test fun `unknown rotation is rejected`() = rejects(
        NovelInstallReason.INCOMPLETE,
        evaluate(candidate = candidate(rotation = NovelRotation.UNKNOWN)),
    )

    @Test fun `history alone is not rotation`() = assertEquals(
        NovelInstallDecision.Eligible,
        evaluate(candidate = candidate(history = setOf(signer), layout = NovelApkLayout.STANDALONE)),
    )

    @Test fun `stale snapshot is rejected`() = rejects(
        NovelInstallReason.STALE_STATE,
        evaluate(installed = installed(freshness = NovelStateFreshness.STALE)),
    )

    @Test fun `unknown snapshot freshness is rejected`() = rejects(
        NovelInstallReason.STALE_STATE,
        evaluate(installed = installed(freshness = NovelStateFreshness.UNKNOWN)),
    )

    @Test fun `unknown old source identity is rejected`() = rejects(
        NovelInstallReason.SOURCE_CONTINUITY,
        evaluate(installed = installed(sources = null)),
    )

    @Test fun `unknown candidate source identity is rejected`() = rejects(
        NovelInstallReason.SOURCE_CONTINUITY,
        evaluate(candidate = candidate(sources = null)),
    )

    @Test fun `changed source identity is rejected`() = rejects(
        NovelInstallReason.SOURCE_CONTINUITY,
        evaluate(candidate = candidate(sources = setOf("other"))),
    )

    @Test fun `all reviewed package source identities are checked`() {
        for ((name, source) in listOf(
            "matchanovels" to "matchanovels",
            "skynovels" to "skynovels",
            "jabrascan" to "jabrascan",
        )) {
            val pkg = "app.novel.extension.$name"
            assertEquals(
                NovelInstallDecision.Eligible,
                evaluate(
                    entry = expected(pkg),
                    candidate = candidate(
                        packageName = pkg,
                        sources = setOf(source),
                        layout = NovelApkLayout.STANDALONE,
                    ),
                    installed = installed(packageName = pkg, sources = setOf(source)),
                    trust = trust(packageName = pkg),
                ),
            )
            rejects(
                NovelInstallReason.SOURCE_CONTINUITY,
                evaluate(
                    entry = expected(pkg),
                    candidate = candidate(packageName = pkg, sources = setOf("wrong")),
                    installed = installed(packageName = pkg, sources = setOf("wrong")),
                    trust = trust(packageName = pkg),
                ),
            )
        }
    }

    @Test fun `complete multi signer set is accepted and a subset is not`() {
        val full = setOf(signer, other)
        assertEquals(
            NovelInstallDecision.Eligible,
            evaluate(
                entry = expected(signers = full),
                candidate = candidate(signers = full, layout = NovelApkLayout.STANDALONE),
                installed = installed(signers = full),
                trust = trust(signers = full),
            ),
        )
        rejects(
            NovelInstallReason.TRUST,
            evaluate(
                entry = expected(signers = full),
                candidate = candidate(signers = full),
                installed = installed(signers = full),
                trust = trust(signers = setOf(signer)),
            ),
        )
    }

    @Test fun `host supported API list remains synchronized`() {
        for (version in listOf("1.0", "1.1", "1.2", "1.3", "1.4", "9.9")) {
            val result = evaluate(candidate = candidate(api = version))
            val apiRejected = result is NovelInstallDecision.Rejected && NovelInstallReason.API in result.reasons
            assertEquals(isSupportedNovelApiVersion(version.toDouble()), !apiRejected)
        }
    }

    @Test fun `untrusted callers cannot directly construct distribution expectations`() {
        assertTrue(NovelDistributionEntry::class.java.declaredConstructors.none { Modifier.isPublic(it.modifiers) })
        assertTrue(NovelDistributionCatalog::class.java.declaredConstructors.none { Modifier.isPublic(it.modifiers) })
    }

    @Test fun `forged zero version cannot be eligible for new install`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(
            operation = NovelInstallOperation.NEW,
            entry = forgedExpectation(version = 0),
            candidate = candidate(version = 0, layout = NovelApkLayout.STANDALONE),
            installed = absent(),
            trust = trust(version = 0),
        ),
    )

    @Test fun `forged zero size cannot be eligible for new install`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(
            operation = NovelInstallOperation.NEW,
            entry = forgedExpectation(size = 0),
            candidate = candidate(size = 0, layout = NovelApkLayout.STANDALONE),
            installed = absent(),
        ),
    )

    @Test fun `forged malformed digest cannot be eligible for new install`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(
            operation = NovelInstallOperation.NEW,
            entry = forgedExpectation(sha = "bad"),
            candidate = candidate(sha = "bad", layout = NovelApkLayout.STANDALONE),
            installed = absent(),
        ),
    )

    @Test fun `forged empty classes cannot be eligible for new install`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(
            operation = NovelInstallOperation.NEW,
            entry = forgedExpectation(classes = emptyList()),
            candidate = candidate(classes = emptySet(), layout = NovelApkLayout.STANDALONE),
            installed = absent(),
        ),
    )

    @Test fun `forged malformed digest cannot be eligible for update`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(
            entry = forgedExpectation(sha = "bad"),
            candidate = candidate(sha = "bad", layout = NovelApkLayout.STANDALONE),
        ),
    )

    @Test fun `forged oversized artifact cannot be eligible for update`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(
            entry = forgedExpectation(size = NovelDistributionCatalogCodec.MAX_APK_BYTES + 1),
            candidate = candidate(
                size =
                NovelDistributionCatalogCodec.MAX_APK_BYTES + 1,
                layout = NovelApkLayout.STANDALONE,
            ),
        ),
    )

    @Test fun `forged invalid package is rejected`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(
            entry = forgedExpectation(packageName = "bad-package"),
            candidate = candidate(packageName = "bad-package", layout = NovelApkLayout.STANDALONE),
            installed = installed(packageName = "bad-package"),
            trust = trust(packageName = "bad-package"),
        ),
    )

    @Test fun `forged invalid SDK is rejected`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(
            entry = forgedExpectation(minSdk = 0),
            candidate = candidate(minSdk = 0, layout = NovelApkLayout.STANDALONE),
        ),
    )

    @Test fun `forged insecure URL is rejected`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(entry = forgedExpectation(url = "http://example.invalid/a.apk")),
    )

    @Test fun `forged duplicate classes are rejected`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(entry = forgedExpectation(classes = listOf("app.example.Source", "app.example.Source"))),
    )

    @Test fun `forged empty signer set is rejected`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(entry = forgedExpectation(signers = emptySet())),
    )

    @Test fun `new install rejects contradictory declared ABI`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(
            operation = NovelInstallOperation.NEW,
            entry = expected(abis = setOf("x86")),
            candidate = candidate(apkAbis = setOf("arm64-v8a"), layout = NovelApkLayout.STANDALONE),
            installed = absent(),
        ),
    )

    @Test fun `update rejects contradictory declared ABI`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(
            entry = expected(abis = setOf("x86")),
            candidate = candidate(apkAbis = setOf("arm64-v8a"), layout = NovelApkLayout.STANDALONE),
        ),
    )

    @Test fun `new install rejects contradictory declared source`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(
            operation = NovelInstallOperation.NEW,
            entry = expected(sources = setOf("different-source")),
            installed = absent(),
        ),
    )

    @Test fun `update rejects contradictory declared source`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(entry = expected(sources = setOf("different-source"))),
    )

    @Test fun `matching optional declarations allow new install`() = assertEquals(
        NovelInstallDecision.Eligible,
        evaluate(
            operation = NovelInstallOperation.NEW,
            entry = expected(abis = setOf("arm64-v8a"), sources = setOf("matchanovels")),
            candidate = candidate(apkAbis = setOf("arm64-v8a"), layout = NovelApkLayout.STANDALONE),
            installed = absent(),
        ),
    )

    @Test fun `matching optional declarations allow update`() = assertEquals(
        NovelInstallDecision.Eligible,
        evaluate(
            entry = expected(abis = setOf("arm64-v8a"), sources = setOf("matchanovels")),
            candidate = candidate(apkAbis = setOf("arm64-v8a"), layout = NovelApkLayout.STANDALONE),
        ),
    )

    @Test fun `declared source cannot substitute for missing observation`() = rejects(
        NovelInstallReason.EXPECTATION_MISMATCH,
        evaluate(
            operation = NovelInstallOperation.NEW,
            entry = expected(sources = setOf("matchanovels")),
            candidate = candidate(sources = null, layout = NovelApkLayout.STANDALONE),
            installed = absent(),
        ),
    )

    @Test fun `absent optional ABI does not substitute for missing APK ABI observation`() = rejects(
        NovelInstallReason.COMPATIBILITY,
        evaluate(
            operation = NovelInstallOperation.NEW,
            candidate = candidate(apkAbis = null, layout = NovelApkLayout.STANDALONE),
            installed = absent(),
        ),
    )

    @Test fun `absent optional source does not require source observation for a new install`() = assertEquals(
        NovelInstallDecision.Eligible,
        evaluate(
            operation = NovelInstallOperation.NEW,
            candidate = candidate(sources = null, layout = NovelApkLayout.STANDALONE),
            installed = absent(),
        ),
    )

    @Test fun `unknown APK layout is rejected`() = rejects(
        NovelInstallReason.COMPATIBILITY,
        evaluate(candidate = candidate(layout = NovelApkLayout.UNKNOWN)),
    )

    @Test fun `split APK layout is rejected`() = rejects(
        NovelInstallReason.COMPATIBILITY,
        evaluate(candidate = candidate(layout = NovelApkLayout.SPLIT)),
    )

    @Test fun `new install rejects unknown APK layout`() = rejects(
        NovelInstallReason.COMPATIBILITY,
        evaluate(
            operation = NovelInstallOperation.NEW,
            candidate = candidate(layout = NovelApkLayout.UNKNOWN),
            installed = absent(),
        ),
    )

    @Test fun `new install rejects split APK layout`() = rejects(
        NovelInstallReason.COMPATIBILITY,
        evaluate(
            operation = NovelInstallOperation.NEW,
            candidate = candidate(layout = NovelApkLayout.SPLIT),
            installed = absent(),
        ),
    )

    @Test fun `standalone APK layout is eligible`() = assertEquals(
        NovelInstallDecision.Eligible,
        evaluate(candidate = candidate(layout = NovelApkLayout.STANDALONE)),
    )

    @Test fun `privileged types have private constructors and immutable defensive sets`() {
        for (type in listOf(
            NovelVerifiedCandidate::class.java,
            NovelInstalledSnapshot::class.java,
            NovelVersionTrust::class.java,
        )) {
            assertTrue(type.declaredConstructors.all { Modifier.isPrivate(it.modifiers) })
            assertTrue(type.methods.none { it.name == "copy" || it.name.startsWith("copy$") })
        }
        val signers = mutableSetOf(signer)
        val evidence = candidate(signers = signers)
        signers.add(other)
        assertEquals(setOf(signer), evidence.currentSigners)
        assertThrows(UnsupportedOperationException::class.java) {
            (evidence.currentSigners as MutableSet<String>).add(other)
        }
    }
}
