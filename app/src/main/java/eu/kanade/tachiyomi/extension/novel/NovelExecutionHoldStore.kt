package eu.kanade.tachiyomi.extension.novel

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

internal class NovelExecutionEpoch {
    internal val leases = mutableMapOf<String, Any>()
}

/** Separate from legacy trust. The coordinator must never start a session unless enroll returns true. */
internal class NovelExecutionHoldStore(
    private val root: File,
    private val epoch: NovelExecutionEpoch = processEpoch,
    private val afterRename: () -> Unit = {},
) : NovelExecutionHolds {
    internal var onFaultPoint: (String) -> Unit = {}
    private data class Record(
        val op: String,
        val pkg: String,
        val version: Long,
        val signers: Set<String>,
        val hash: String,
        val reconciled: Boolean,
        val granted: Boolean,
    )

    companion object {
        private val localLock = Any()
        val processEpoch = NovelExecutionEpoch()
        private val hex = Regex("[0-9a-f]{64}")
        private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val dirMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        private val fileMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        private fun validPackage(pkg: String) = pkg.length <= 255 && packagePattern.matches(pkg)
        private fun validOp(op: String) = op.length in 1..128 &&
            op.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        private fun digest(text: String) = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        private fun valid(r: Record) = validPackage(r.pkg) && validOp(r.op) && r.version > 0 && hex.matches(r.hash) &&
            r.signers.size in 1..16 && r.signers.all { hex.matches(it) } && (!r.granted || r.reconciled)
        private fun attributes(path: Path): BasicFileAttributes? = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            null
        }
        private fun syncDir(dir: Path) = FileChannel.open(dir, StandardOpenOption.READ, NOFOLLOW_LINKS).use {
            it.force(true)
        }
    }

    private val directory get() = root.toPath().toAbsolutePath().normalize()
    private fun <T> locked(f: () -> T): T? = try {
        synchronized(localLock) {
            val dir = directory
            val parent = requireNotNull(dir.parent)
            require(attributes(parent)?.isDirectory == true)
            if (attributes(dir) == null) {
                try {
                    Files.createDirectory(dir, dirMode)
                } catch (_: java.nio.file.FileAlreadyExistsException) { }
            }
            require(attributes(dir)?.isDirectory == true)
            // Sync even for an existing directory: another process may have failed after creating it.
            syncDir(parent)
            val lockPath = dir.resolve(".lock")
            val options = setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, NOFOLLOW_LINKS)
            FileChannel.open(lockPath, options, fileMode).use { channel ->
                require(attributes(lockPath)?.isRegularFile == true)
                channel.lock().use { f() }
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun path(pkg: String) = directory.resolve("${digest(pkg)}.record")
    private fun pending(pkg: String) = directory.resolve("${digest(pkg)}.pending")
    private fun read(pkg: String): Record? {
        // Any marker, including a symlink or malformed file, represents uncertain authority.
        require(attributes(pending(pkg)) == null)
        val p = path(pkg)
        val attrs = attributes(p) ?: return null
        require(attrs.isRegularFile && attrs.size() in 1..4096)
        val buffer = ByteBuffer.allocate(4097)
        FileChannel.open(p, StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
            while (buffer.hasRemaining() && channel.read(buffer) != -1) { }
        }
        require(buffer.position() in 1..4096)
        val bytes = buffer.array().copyOf(buffer.position())
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 0x53333931)
            val op = input.readUTF()
            val packageName = input.readUTF()
            val version = input.readLong()
            val hash = input.readUTF()
            val count = input.readInt()
            require(count in 1..16)
            val signers = (1..count).map { input.readUTF() }.toSet()
            require(signers.size == count)
            val state = input.readUnsignedByte()
            require(state in 0..2 && input.available() == 0)
            // A legacy serialized grant is only reconciliation, never execution authority.
            Record(op, packageName, version, signers, hash, state >= 1, false).also {
                require(valid(it) && it.pkg == pkg)
            }
        }
    }

    private fun write(r: Record) {
        require(valid(r))
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(0x53333931)
                out.writeUTF(r.op)
                out.writeUTF(r.pkg)
                out.writeLong(r.version)
                out.writeUTF(r.hash)
                out.writeInt(r.signers.size)
                r.signers.sorted().forEach(out::writeUTF)
                out.writeByte(if (r.reconciled) 1 else 0)
            }
        }.toByteArray()
        require(bytes.size <= 4096)
        val marker = pending(r.pkg)
        onFaultPoint("beforeHoldWrite")
        FileChannel.open(
            marker,
            setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS),
            fileMode,
        ).use {
            it.write(ByteBuffer.wrap(byteArrayOf(1)))
            it.force(true)
        }
        syncDir(directory)
        val temp = Files.createTempFile(directory, "s39-", ".tmp", fileMode)
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            // Never replace a symlink or an unexpected object.
            val old = attributes(path(r.pkg))
            require(old == null || old.isRegularFile)
            onFaultPoint("persist")
            Files.move(temp, path(r.pkg), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            afterRename()
            syncDir(directory)
            Files.delete(marker)
            try {
                onFaultPoint("finalSync")
                syncDir(directory)
            } catch (failure: Exception) {
                // Re-establish uncertainty if removing its marker could not be synchronized.
                onFaultPoint("restore")
                FileChannel.open(
                    marker,
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS),
                    fileMode,
                ).use { it.force(true) }
                syncDir(directory)
                throw failure
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun enroll(
        operationId: String,
        packageName: String,
        versionCode: Long,
        currentSigners: Set<String>,
        artifactSha256: String,
    ): Boolean {
        val candidate = Record(
            operationId,
            packageName,
            versionCode,
            currentSigners.toSet(),
            artifactSha256,
            false,
            false,
        )
        if (!valid(candidate)) return false
        return locked {
            val old = read(packageName)
            if (old == null) {
                write(candidate)
                true
            } else {
                old.copy(reconciled = false, granted = false) == candidate
            }
        } ?: false
    }

    private fun matches(r: Record, op: String, facts: NovelInstalledPackageFacts) =
        r.op == op &&
            r.pkg == facts.packageName &&
            r.version == facts.longVersionCode &&
            facts.origin == NovelInstallOrigin.SHARED &&
            !facts.split &&
            facts.signers.hasCompleteCurrent &&
            r.signers == facts.signers.current

    override fun reconcile(operationId: String, installed: NovelInstalledPackageFacts): Boolean = locked {
        if (!validPackage(installed.packageName)) return@locked false
        val r = read(installed.packageName) ?: return@locked false
        if (!matches(r, operationId, installed)) return@locked false
        if (!r.reconciled) write(r.copy(reconciled = true))
        true
    } ?: false

    override fun grantExecution(
        operationId: String,
        installed: NovelInstalledPackageFacts,
        explicitConsent: Boolean,
    ): Boolean {
        if (!explicitConsent || !validPackage(installed.packageName)) return false
        return synchronized(localLock) {
            val key = path(installed.packageName).toString()
            epoch.leases.remove(key)
            val record = locked {
                val r = read(installed.packageName) ?: return@locked null
                if (!r.reconciled || !matches(r, operationId, installed)) return@locked null
                // The durable record remains DENY even if any subsequent synchronization fails.
                write(r.copy(granted = false))
                r
            } ?: return@synchronized false
            // Lock close, temporary cleanup and all filesystem I/O completed before publication.
            epoch.leases[key] = record
            true
        }
    }

    override fun permits(
        packageName: String,
        versionCode: Long,
        signers: NovelPackageSigners,
    ): Boolean = permits(packageName, versionCode, signers, true)
    fun permits(
        packageName: String,
        versionCode: Long,
        signers: NovelPackageSigners,
        isShared: Boolean,
    ): Boolean = locked {
        if (!validPackage(packageName)) return@locked false
        val r = read(packageName) ?: return@locked true
        isShared && r.reconciled && epoch.leases[path(packageName).toString()] == r &&
            r.version == versionCode && signers.hasCompleteCurrent && r.signers == signers.current
    } ?: false
}
