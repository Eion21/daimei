package eu.kanade.tachiyomi.extension.novel

import java.nio.file.Path

/** Byte integrity only. This is not APK signer evidence, trust, or installation authorization. */
class NovelArtifactIntegrity internal constructor(
    val expectedSize: Long,
    val observedSize: Long,
    val expectedSha256: String,
    val observedSha256: String,
)

/** Owned quarantine bytes, never a NovelVerifiedCandidate. Reverify bytes before any later use. */
class NovelQuarantinedArtifact internal constructor(
    val path: Path,
    val integrity: NovelArtifactIntegrity,
)

enum class NovelArtifactDownloadError {
    INVALID_CANDIDATE,
    ORIGIN_NOT_AUTHORIZED,
    DESTINATION_REJECTED,
    DNS_FAILURE,
    REDIRECT,
    HTTP_STATUS,
    CONTENT_ENCODING,
    CONTENT_LENGTH,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    LIMIT_EXCEEDED,
    TRUNCATED,
    TIMEOUT,
    CANCELLED,
    NETWORK,
    STORAGE,
    CLEANUP_FAILED,
    BUSY,
    CLOSED,
    OBSERVER_FAILED,
}

sealed interface NovelArtifactDownloadResult {
    class Success(val artifact: NovelQuarantinedArtifact) : NovelArtifactDownloadResult
    data class Failure(
        val reason: NovelArtifactDownloadError,
        val observedSize: Long = 0,
        val observedSha256: String? = null,
        val httpStatus: Int? = null,
    ) : NovelArtifactDownloadResult
}

data class NovelArtifactProgress(val receivedBytes: Long, val expectedBytes: Long)
