package eu.kanade.tachiyomi.extension.novel

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ

/**
 * NEW-only, controlled orchestration. The final Android bridge cannot dispatch installation.
 * No path in this coordinator grants execution: the durable hold outlives success, failure and restart.
 */
internal class NovelInteractiveInstallCoordinator(
    private val inspect: (NovelQuarantinedArtifact, NovelDistributionEntry) -> NovelInspectionResult,
    private val installed: NovelInstalledPackages,
    private val holds: NovelExecutionHolds,
    private val journal: NovelInstallJournal,
    private val bridge: NovelInstallSessionBridge,
    private val recognition: NovelInstallRecognition = NovelInstallRecognition { _, _ -> false },
    private val operationId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val nonce: () -> String = {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it) }
    },
) {
    private data class Request(val artifact: NovelQuarantinedArtifact, val expected: NovelDistributionEntry)
    private val requests = mutableMapOf<String, Request>()
    private val confirmations = mutableMapOf<String, NovelInstallUserConfirmation>()

    private fun disabled(op: String?) = NovelInstallOutcome(
        op,
        NovelInstallPhase.PRODUCTION_DISABLED,
        NovelInstallFailure.PRODUCTION_DISABLED,
    )
    private fun error(op: String?, reason: NovelInstallFailure) =
        NovelInstallOutcome(op, NovelInstallPhase.RECOVERY_REQUIRED, reason)
    private fun outcome(r: NovelInstallRecord) = NovelInstallOutcome(r.operationId, r.phase, r.failure)
    private fun read(op: String) = (journal.read(op) as? NovelInstallJournalRead.Found)?.record
    private fun change(old: NovelInstallRecord, new: NovelInstallRecord): NovelInstallRecord? {
        if (old.revision == Long.MAX_VALUE) return null
        val next = new.copy(revision = old.revision + 1)
        return if (journal.replace(old, next)) next else null
    }
    private fun absent(packageName: String): Boolean = try {
        installed.read(packageName) == NovelInstalledRead.Absent
    } catch (_: Exception) {
        false
    }
    private fun inspection(request: Request): NovelInspectionResult.Observed? = try {
        val value = inspect(request.artifact, request.expected) as? NovelInspectionResult.Observed
        value?.takeIf {
            NovelDistributionCatalogCodec.isValidExpectation(request.expected) &&
                it.layout.packageName == request.expected.packageName &&
                it.metadata.packageName == request.expected.packageName &&
                it.metadata.longVersionCode == request.expected.longVersionCode &&
                it.metadata.signers.hasCompleteCurrent &&
                it.metadata.signers.current == request.expected.currentSigners
        }
    } catch (_: Exception) {
        null
    }
    private fun matchesRequest(r: NovelInstallRecord, request: Request): Boolean =
        r.artifactPath == request.artifact.path.toString() && r.artifactSize == request.expected.size &&
            r.artifactSha256 == request.expected.sha256 && r.packageName == request.expected.packageName &&
            r.versionCode == request.expected.longVersionCode && r.currentSigners == request.expected.currentSigners

    private fun abandon(r: NovelInstallRecord): Boolean = try {
        val session = r.sessionId
        session == null || (bridge.ownsSession(session, r.packageName) && bridge.abandonSession(session))
    } catch (_: Exception) {
        false
    }
    private fun fail(
        r: NovelInstallRecord,
        reason: NovelInstallFailure,
        phase: NovelInstallPhase = NovelInstallPhase.FAILED,
        abandonSession: Boolean = true,
    ): NovelInstallOutcome {
        val cleaned = !abandonSession || abandon(r)
        confirmations.remove(r.operationId)
        val next = change(
            r,
            r.copy(phase = if (cleaned) phase else NovelInstallPhase.RECOVERY_REQUIRED, failure = reason),
        )
        return next?.let(::outcome) ?: error(r.operationId, NovelInstallFailure.JOURNAL)
    }

    @Synchronized
    fun begin(
        artifact: NovelQuarantinedArtifact,
        expected: NovelDistributionEntry,
        explicitConsent: Boolean,
    ): NovelInstallOutcome {
        if (!bridge.controlledTestActivation) return disabled(null)
        if (!explicitConsent) return error(null, NovelInstallFailure.CONSENT_REQUIRED)
        val request = Request(artifact, expected)
        val observed = inspection(request) ?: return error(null, NovelInstallFailure.INVALID_ARTIFACT)
        if (!absent(observed.metadata.packageName)) return error(null, NovelInstallFailure.NOT_NEW)
        val record = NovelInstallRecord(
            operationId(),
            nonce(),
            observed.metadata.packageName,
            observed.metadata.longVersionCode,
            observed.metadata.signers.current,
            artifact.path.toString(),
            expected.size,
            expected.sha256,
        )
        if (!journal.insert(record)) return error(record.operationId, NovelInstallFailure.JOURNAL)
        if (!holds.enroll(
                record.operationId,
                record.packageName,
                record.versionCode,
                record.currentSigners,
                record.artifactSha256,
            )
        ) {
            return fail(record, NovelInstallFailure.HOLD)
        }
        requests[record.operationId] = request
        return stage(record, request)
    }

    private fun stage(initial: NovelInstallRecord, request: Request): NovelInstallOutcome {
        var record = initial
        try {
            if (!bridge.hasInstallPermission()) {
                return change(record, record.copy(phase = NovelInstallPhase.PERMISSION_REQUIRED))?.let(::outcome)
                    ?: error(record.operationId, NovelInstallFailure.JOURNAL)
            }
            if (!absent(record.packageName)) return fail(record, NovelInstallFailure.STALE_PACKAGE)
            if (!matchesRequest(record, request) || inspection(request) == null) {
                return fail(record, NovelInstallFailure.INVALID_ARTIFACT)
            }
            val session = bridge.createSession(record)
            if (session < 0) return fail(record, NovelInstallFailure.SESSION)
            val bound = record.copy(sessionId = session)
            val persisted = change(record, bound)
            if (persisted == null) {
                abandon(bound)
                return error(record.operationId, NovelInstallFailure.JOURNAL)
            }
            record = persisted
            val receipt = Files.newInputStream(request.artifact.path, READ, NOFOLLOW_LINKS).use {
                bridge.writeSession(session, it, record.artifactSize)
            }
            if (receipt.size != record.artifactSize || receipt.sha256 != record.artifactSha256) {
                return fail(record, NovelInstallFailure.STREAM_INTEGRITY)
            }
            if (inspection(request) == null) return fail(record, NovelInstallFailure.INVALID_ARTIFACT)
            val staged = change(
                record,
                record.copy(streamedSha256 = receipt.sha256, phase = NovelInstallPhase.STAGED),
            ) ?: run {
                abandon(record)
                return error(record.operationId, NovelInstallFailure.JOURNAL)
            }
            return outcome(staged)
        } catch (_: SecurityException) {
            return fail(record, NovelInstallFailure.PERMISSION)
        } catch (_: Exception) {
            return fail(record, NovelInstallFailure.SESSION)
        }
    }

    @Synchronized
    fun resumePermission(op: String, explicitConsent: Boolean): NovelInstallOutcome {
        if (!bridge.controlledTestActivation) return disabled(op)
        if (!explicitConsent) return error(op, NovelInstallFailure.CONSENT_REQUIRED)
        val record = read(op) ?: return error(op, NovelInstallFailure.JOURNAL)
        if (record.phase != NovelInstallPhase.PERMISSION_REQUIRED) return outcome(record)
        val request = requests[op] ?: return error(op, NovelInstallFailure.PROCESS_RESTART)
        if (record.sessionId == null) return stage(record, request)
        if (!bridge.hasInstallPermission()) return outcome(record)
        if (!absent(record.packageName)) return fail(record, NovelInstallFailure.STALE_PACKAGE)
        if (inspection(request) == null) return fail(record, NovelInstallFailure.INVALID_ARTIFACT)
        return change(record, record.copy(phase = NovelInstallPhase.STAGED, failure = null))?.let(::outcome)
            ?: error(op, NovelInstallFailure.JOURNAL)
    }

    @Synchronized
    fun submit(op: String): NovelInstallOutcome {
        if (!bridge.controlledTestActivation) return disabled(op)
        var record = read(op) ?: return error(op, NovelInstallFailure.JOURNAL)
        if (record.phase != NovelInstallPhase.STAGED) return outcome(record)
        val request = requests[op] ?: return error(op, NovelInstallFailure.PROCESS_RESTART)
        try {
            if (!bridge.hasInstallPermission()) {
                return change(record, record.copy(phase = NovelInstallPhase.PERMISSION_REQUIRED))?.let(::outcome)
                    ?: error(op, NovelInstallFailure.JOURNAL)
            }
            if (!absent(record.packageName)) return fail(record, NovelInstallFailure.STALE_PACKAGE)
            if (!matchesRequest(record, request) || inspection(request) == null) {
                return fail(record, NovelInstallFailure.INVALID_ARTIFACT)
            }
            val session = record.sessionId ?: return fail(record, NovelInstallFailure.SESSION)
            if (record.streamedSha256 != record.artifactSha256 || !bridge.ownsSession(session, record.packageName)) {
                return fail(record, NovelInstallFailure.SESSION)
            }
            record = change(record, record.copy(phase = NovelInstallPhase.COMMITTING, commitRequested = true))
                ?: return error(op, NovelInstallFailure.JOURNAL)
            if (bridge.submit(record) != NovelInstallDispatch.SUBMITTED) {
                return fail(record, NovelInstallFailure.PRODUCTION_DISABLED, NovelInstallPhase.PRODUCTION_DISABLED)
            }
            // A controlled bridge may deliver a callback synchronously; do not overwrite its newer state.
            return read(op)?.let(::outcome) ?: error(op, NovelInstallFailure.JOURNAL)
        } catch (_: Exception) {
            return fail(record, NovelInstallFailure.SESSION, NovelInstallPhase.RECOVERY_REQUIRED, false)
        }
    }

    private fun installedFacts(r: NovelInstallRecord): NovelInstalledPackageFacts? {
        val facts = (installed.read(r.packageName) as? NovelInstalledRead.Present)?.facts ?: return null
        return facts.takeIf {
            it.packageName == r.packageName && it.longVersionCode == r.versionCode &&
                it.origin == NovelInstallOrigin.SHARED && !it.split && it.signers.hasCompleteCurrent &&
                it.signers.current == r.currentSigners
        }
    }
    private fun observeInstalled(record: NovelInstallRecord): NovelInstallOutcome {
        val facts = try {
            installedFacts(record)
        } catch (_: Exception) {
            null
        } ?: return fail(record, NovelInstallFailure.INSTALLED_IDENTITY, NovelInstallPhase.RECOVERY_REQUIRED, false)
        if (!holds.reconcile(record.operationId, facts)) {
            return fail(record, NovelInstallFailure.HOLD, NovelInstallPhase.RECOVERY_REQUIRED, false)
        }
        val recognized = try {
            recognition.recognizes(record.packageName, record.versionCode)
        } catch (_: Exception) {
            false
        }
        val next = record.copy(
            phase = if (recognized) NovelInstallPhase.INSTALLED_HELD else NovelInstallPhase.INSTALLED_UNRECOGNIZED,
            failure = null,
        )
        if (next == record) return outcome(record)
        return change(record, next)?.let(::outcome) ?: error(record.operationId, NovelInstallFailure.JOURNAL)
    }

    @Synchronized
    fun onCallback(callback: NovelInstallCallback): NovelInstallOutcome {
        if (!bridge.controlledTestActivation) return disabled(callback.operationId)
        val r = read(callback.operationId) ?: return error(callback.operationId, NovelInstallFailure.FOREIGN_CALLBACK)
        if (r.nonce != callback.nonce || r.sessionId != callback.sessionId) {
            return error(callback.operationId, NovelInstallFailure.FOREIGN_CALLBACK)
        }
        val active = r.phase == NovelInstallPhase.COMMITTING || r.phase == NovelInstallPhase.PENDING_USER_ACTION ||
            (r.phase == NovelInstallPhase.RECOVERY_REQUIRED && r.commitRequested)
        if (!active || r.androidResult == callback.status) return outcome(r)
        if (!r.commitRequested) return error(r.operationId, NovelInstallFailure.FOREIGN_CALLBACK)
        return when (callback.status) {
            NovelInstallCallbackStatus.PENDING_USER_ACTION -> {
                val action = callback.confirmation
                    ?: return fail(r, NovelInstallFailure.USER_ACTION, NovelInstallPhase.RECOVERY_REQUIRED, false)
                val next = change(
                    r,
                    r.copy(phase = NovelInstallPhase.PENDING_USER_ACTION, androidResult = callback.status),
                ) ?: return error(r.operationId, NovelInstallFailure.JOURNAL)
                confirmations[r.operationId] = action
                outcome(next)
            }
            NovelInstallCallbackStatus.SUCCESS -> {
                // Save the Android result separately from actual installed identity and execution permission.
                val received = change(r, r.copy(androidResult = callback.status))
                    ?: return error(r.operationId, NovelInstallFailure.JOURNAL)
                confirmations.remove(r.operationId)
                observeInstalled(received)
            }
            NovelInstallCallbackStatus.CANCELLED, NovelInstallCallbackStatus.FAILURE,
            NovelInstallCallbackStatus.UNKNOWN,
            -> {
                val received = change(r, r.copy(androidResult = callback.status))
                    ?: return error(r.operationId, NovelInstallFailure.JOURNAL)
                fail(
                    received,
                    NovelInstallFailure.ANDROID_FAILURE,
                    if (callback.status == NovelInstallCallbackStatus.CANCELLED) {
                        NovelInstallPhase.CANCELLED
                    } else {
                        NovelInstallPhase.FAILED
                    },
                )
            }
        }
    }

    @Synchronized
    fun confirmUserAction(op: String, explicitConsent: Boolean): NovelInstallOutcome {
        if (!bridge.controlledTestActivation) return disabled(op)
        if (!explicitConsent) return error(op, NovelInstallFailure.CONSENT_REQUIRED)
        val record = read(op) ?: return error(op, NovelInstallFailure.JOURNAL)
        if (record.phase != NovelInstallPhase.PENDING_USER_ACTION || record.actionPresented) return outcome(record)
        if (!absent(record.packageName)) return fail(record, NovelInstallFailure.STALE_PACKAGE)
        val session = record.sessionId ?: return error(op, NovelInstallFailure.SESSION)
        if (!bridge.ownsSession(session, record.packageName)) return error(op, NovelInstallFailure.SESSION)
        val confirmation = confirmations.remove(op)
            ?: return fail(record, NovelInstallFailure.PROCESS_RESTART, NovelInstallPhase.RECOVERY_REQUIRED, false)
        val presented = change(record, record.copy(actionPresented = true))
            ?: return error(op, NovelInstallFailure.JOURNAL)
        val launched = try {
            confirmation.launch()
        } catch (_: Exception) {
            false
        }
        return if (launched) {
            outcome(presented)
        } else {
            fail(presented, NovelInstallFailure.USER_ACTION, NovelInstallPhase.RECOVERY_REQUIRED, false)
        }
    }

    @Synchronized
    fun cancel(op: String): NovelInstallOutcome {
        if (!bridge.controlledTestActivation) return disabled(op)
        val r = read(op) ?: return error(op, NovelInstallFailure.JOURNAL)
        if (r.phase == NovelInstallPhase.INSTALLED_HELD || r.phase == NovelInstallPhase.INSTALLED_UNRECOGNIZED ||
            r.phase == NovelInstallPhase.CANCELLED || r.phase == NovelInstallPhase.FAILED
        ) {
            return outcome(r)
        }
        return fail(r, NovelInstallFailure.ANDROID_FAILURE, NovelInstallPhase.CANCELLED)
    }

    @Synchronized
    fun reconcile(op: String): NovelInstallOutcome {
        if (!bridge.controlledTestActivation) return disabled(op)
        val r = read(op) ?: return error(op, NovelInstallFailure.JOURNAL)
        if (!r.commitRequested || r.phase == NovelInstallPhase.CANCELLED || r.phase == NovelInstallPhase.FAILED) {
            return outcome(r)
        }
        // A restart can observe installed identity, but never replay submission, user action or trust.
        return observeInstalled(r)
    }
}
