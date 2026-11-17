package eu.kanade.tachiyomi.extension.novel

import java.io.InputStream

/** Non-authoritative observations. None of these types issue Stage1 evidence or execution trust. */
internal enum class NovelInstallPhase {
    PREPARING,
    PERMISSION_REQUIRED,
    STAGED,
    COMMITTING,
    PENDING_USER_ACTION,
    INSTALLED_HELD,
    INSTALLED_UNRECOGNIZED,
    CANCELLED,
    FAILED,
    RECOVERY_REQUIRED,
    PRODUCTION_DISABLED,
}

internal enum class NovelInstallFailure {
    CONSENT_REQUIRED,
    INVALID_ARTIFACT,
    NOT_NEW,
    STALE_PACKAGE,
    JOURNAL,
    HOLD,
    PERMISSION,
    SESSION,
    STREAM_INTEGRITY,
    FOREIGN_CALLBACK,
    DUPLICATE_CALLBACK,
    INSTALLED_IDENTITY,
    USER_ACTION,
    ANDROID_FAILURE,
    PROCESS_RESTART,
    PRODUCTION_DISABLED,
}

/** Journal state is DENY-only: there is deliberately no persisted execution grant. */
internal data class NovelInstallRecord(
    val operationId: String,
    val nonce: String,
    val packageName: String,
    val versionCode: Long,
    val currentSigners: Set<String>,
    val artifactPath: String,
    val artifactSize: Long,
    val artifactSha256: String,
    val priorAbsent: Boolean = true,
    val explicitInstallConsent: Boolean = true,
    val sessionId: Int? = null,
    val streamedSha256: String? = null,
    val phase: NovelInstallPhase = NovelInstallPhase.PREPARING,
    val commitRequested: Boolean = false,
    val actionPresented: Boolean = false,
    val androidResult: NovelInstallCallbackStatus? = null,
    val failure: NovelInstallFailure? = null,
    val revision: Long = 0,
)

internal sealed interface NovelInstallJournalRead {
    data class Found(val record: NovelInstallRecord) : NovelInstallJournalRead
    data object Missing : NovelInstallJournalRead
    data object Unavailable : NovelInstallJournalRead
}

internal interface NovelInstallJournal {
    fun insert(record: NovelInstallRecord): Boolean
    fun read(operationId: String): NovelInstallJournalRead
    fun replace(expected: NovelInstallRecord, replacement: NovelInstallRecord): Boolean
}

internal data class NovelInstallStreamReceipt(val size: Long, val sha256: String)
internal enum class NovelInstallDispatch { SUBMITTED, DISABLED }

/** Process-local result; never persisted or equivalent to legacy signer trust. */
internal enum class NovelExecutionConsentOutcome { GRANTED, DENIED }

/** The default Android bridge is cold; only a separately pinned QA factory may dispatch. */
internal interface NovelInstallSessionBridge {
    val controlledTestActivation: Boolean
    fun hasInstallPermission(): Boolean
    fun createSession(record: NovelInstallRecord): Int
    fun writeSession(sessionId: Int, source: InputStream, expectedSize: Long): NovelInstallStreamReceipt
    fun ownsSession(sessionId: Int, packageName: String): Boolean
    fun submit(record: NovelInstallRecord): NovelInstallDispatch
    fun abandonSession(sessionId: Int): Boolean
}

internal enum class NovelInstallCallbackStatus { PENDING_USER_ACTION, SUCCESS, FAILURE, CANCELLED, UNKNOWN }
internal fun interface NovelInstallUserConfirmation {
    fun launch(): Boolean
}

internal data class NovelInstallCallback(
    val operationId: String,
    val nonce: String,
    val sessionId: Int,
    val status: NovelInstallCallbackStatus,
    val confirmation: NovelInstallUserConfirmation? = null,
)

internal fun interface NovelInstallRecognition {
    /** Observation only: must not instantiate or load extension code. */
    fun recognizes(packageName: String, versionCode: Long): Boolean
}

internal data class NovelInstallOutcome(
    val operationId: String?,
    val phase: NovelInstallPhase,
    val failure: NovelInstallFailure? = null,
) {
    /** Installation never conveys execution permission, including in controlled tests. */
    val executionGranted: Boolean get() = false
}
