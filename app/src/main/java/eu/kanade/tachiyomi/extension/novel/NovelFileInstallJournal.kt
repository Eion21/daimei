package eu.kanade.tachiyomi.extension.novel

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Private, atomic, compare-and-replace journal. No serialized state is execution authority. */
internal class NovelFileInstallJournal(
    root: Path,
    private val fault: (String) -> Unit = {},
) : NovelInstallJournal {
    private val root = root.toAbsolutePath().normalize()
    private val localLock = locks.computeIfAbsent(this.root.toString()) { Any() }

    private companion object {
        val locks = ConcurrentHashMap<String, Any>()
        val id = Regex("[A-Za-z0-9_-]{1,128}")
        val pkg = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
        val hex = Regex("[0-9a-f]{64}")
        val fileMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        val directoryMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        const val MAGIC = 0x53333932
    }

    private fun valid(r: NovelInstallRecord): Boolean = try {
        id.matches(r.operationId) && hex.matches(r.nonce) && pkg.matches(r.packageName) &&
            r.versionCode > 0 && r.currentSigners.size in 1..16 && r.currentSigners.all(hex::matches) &&
            r.artifactPath.length in 1..4096 && Path.of(r.artifactPath).isAbsolute &&
            Path.of(r.artifactPath).normalize().toString() == r.artifactPath &&
            r.artifactSize in 1..NovelDistributionCatalogCodec.MAX_APK_BYTES && hex.matches(r.artifactSha256) &&
            r.priorAbsent && r.explicitInstallConsent && r.revision >= 0 &&
            (r.sessionId == null || r.sessionId >= 0) &&
            (r.streamedSha256 == null || r.streamedSha256 == r.artifactSha256) &&
            (!r.commitRequested || (r.sessionId != null && r.streamedSha256 != null))
    } catch (_: Exception) {
        false
    }

    private fun <T> locked(unavailable: T, block: () -> T): T = synchronized(localLock) {
        try {
            if (!Files.exists(root, NOFOLLOW_LINKS)) Files.createDirectory(root, directoryMode)
            require(Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root))
            require(Files.getPosixFilePermissions(root) == PosixFilePermissions.fromString("rwx------"))
            FileChannel.open(root.resolve(".lock"), setOf(CREATE, WRITE, NOFOLLOW_LINKS), fileMode).use { channel ->
                channel.lock().use { block() }
            }
        } catch (_: Exception) {
            // The caller receives an explicit unavailable/failure result; it must not dispatch installation.
            unavailable
        }
    }

    private fun readRecord(operationId: String): NovelInstallRecord? {
        val path = root.resolve("$operationId.journal")
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) in 1..16384)
        require(Files.getPosixFilePermissions(path) == PosixFilePermissions.fromString("rw-------"))
        return DataInputStream(Files.newInputStream(path, READ, NOFOLLOW_LINKS)).use { input ->
            require(input.readInt() == MAGIC)
            val op = input.readUTF()
            val nonce = input.readUTF()
            val packageName = input.readUTF()
            val version = input.readLong()
            val count = input.readInt()
            require(count in 1..16)
            val signers = (0 until count).map { input.readUTF() }
            require(signers.toSet().size == count)
            val artifactPath = input.readUTF()
            val size = input.readLong()
            val sha = input.readUTF()
            val priorAbsent = input.readBoolean()
            val consent = input.readBoolean()
            val session = input.readInt().let { if (it == -1) null else it }
            val streamed = input.readUTF().ifEmpty { null }
            val phase = NovelInstallPhase.valueOf(input.readUTF())
            val committed = input.readBoolean()
            val action = input.readBoolean()
            val status = input.readUTF().ifEmpty { null }?.let(NovelInstallCallbackStatus::valueOf)
            val failure = input.readUTF().ifEmpty { null }?.let(NovelInstallFailure::valueOf)
            val revision = input.readLong()
            // Reserved execution decision is DENY, and unknown/trailing state is rejected.
            require(input.readByte().toInt() == 0 && input.read() == -1)
            NovelInstallRecord(
                op, nonce, packageName, version, Collections.unmodifiableSet(signers.toSet()),
                artifactPath, size, sha, priorAbsent, consent, session, streamed, phase,
                committed, action, status, failure, revision,
            ).also { require(it.operationId == operationId && valid(it)) }
        }
    }

    private fun write(r: NovelInstallRecord) {
        val temp = Files.createTempFile(root, ".journal-", ".pending", fileMode)
        try {
            DataOutputStream(Files.newOutputStream(temp, WRITE, NOFOLLOW_LINKS)).use { out ->
                out.writeInt(MAGIC)
                out.writeUTF(r.operationId)
                out.writeUTF(r.nonce)
                out.writeUTF(r.packageName)
                out.writeLong(r.versionCode)
                out.writeInt(r.currentSigners.size)
                r.currentSigners.sorted().forEach(out::writeUTF)
                out.writeUTF(r.artifactPath)
                out.writeLong(r.artifactSize)
                out.writeUTF(r.artifactSha256)
                out.writeBoolean(r.priorAbsent)
                out.writeBoolean(r.explicitInstallConsent)
                out.writeInt(r.sessionId ?: -1)
                out.writeUTF(r.streamedSha256.orEmpty())
                out.writeUTF(r.phase.name)
                out.writeBoolean(r.commitRequested)
                out.writeBoolean(r.actionPresented)
                out.writeUTF(r.androidResult?.name.orEmpty())
                out.writeUTF(r.failure?.name.orEmpty())
                out.writeLong(r.revision)
                out.writeByte(0)
            }
            FileChannel.open(temp, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
            fault("beforeMove")
            Files.move(temp, root.resolve("${r.operationId}.journal"), ATOMIC_MOVE, REPLACE_EXISTING)
            fault("afterMove")
            FileChannel.open(root, READ, NOFOLLOW_LINKS).use { it.force(true) }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun insert(record: NovelInstallRecord): Boolean {
        if (!valid(record) || record.revision != 0L || record.phase != NovelInstallPhase.PREPARING) return false
        return locked(false) {
            if (readRecord(record.operationId) != null) return@locked false
            write(record)
            true
        }
    }

    override fun read(operationId: String): NovelInstallJournalRead {
        if (!id.matches(operationId)) return NovelInstallJournalRead.Unavailable
        return locked(NovelInstallJournalRead.Unavailable as NovelInstallJournalRead) {
            readRecord(operationId)?.let(NovelInstallJournalRead::Found) ?: NovelInstallJournalRead.Missing
        }
    }

    override fun replace(expected: NovelInstallRecord, replacement: NovelInstallRecord): Boolean {
        if (!valid(expected) || !valid(replacement) || expected.revision == Long.MAX_VALUE) return false
        if (replacement.revision != expected.revision + 1) return false
        if (expected.sessionId != null && replacement.sessionId != expected.sessionId) return false
        if (expected.streamedSha256 != null && replacement.streamedSha256 != expected.streamedSha256) return false
        if (expected.commitRequested && !replacement.commitRequested) return false
        if (expected.actionPresented && !replacement.actionPresented) return false
        val mutableFieldsOnly = expected.copy(
            sessionId = replacement.sessionId,
            streamedSha256 = replacement.streamedSha256,
            phase = replacement.phase,
            commitRequested = replacement.commitRequested,
            actionPresented = replacement.actionPresented,
            androidResult = replacement.androidResult,
            failure = replacement.failure,
            revision = replacement.revision,
        )
        if (mutableFieldsOnly != replacement) return false
        return locked(false) {
            if (readRecord(expected.operationId) != expected) return@locked false
            write(replacement)
            true
        }
    }
}
