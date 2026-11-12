package eu.kanade.tachiyomi.extension.novel

import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** One token per operation; cancelling it never changes trust or installation state. */
class NovelArtifactCancellation {
    private val cancelled = AtomicBoolean()
    private val actions = CopyOnWriteArrayList<() -> Unit>()
    val isCancelled: Boolean get() = cancelled.get()
    fun cancel() {
        cancelled.set(true)
        actions.forEach { it() }
    }
    internal fun register(action: () -> Unit): Closeable {
        actions.add(action)
        if (isCancelled) action()
        return Closeable { actions.remove(action) }
    }
}

/** Blocking headless API: invoke on an I/O executor, never Android's main thread. */
class NovelArtifactDownloader private constructor(
    private val privateRoot: Path,
    private val origins: NovelArtifactOriginPolicy,
    private val timeoutMillis: Long,
    private val resolver: Dns,
    private val fixtureTls: Pair<SSLSocketFactory, X509TrustManager>?,
) : Closeable {
    constructor(
        privateRoot: Path,
        origins: NovelArtifactOriginPolicy,
        timeoutMillis: Long = 120_000,
    ) : this(privateRoot, origins, timeoutMillis, Dns.SYSTEM, null)

    internal constructor(
        privateRoot: Path,
        origins: NovelArtifactOriginPolicy,
        timeoutMillis: Long,
        resolver: Dns,
        sslSocketFactory: SSLSocketFactory,
        trustManager: X509TrustManager,
    ) : this(privateRoot, origins, timeoutMillis, resolver, sslSocketFactory to trustManager)

    init {
        require(timeoutMillis in 1..120_000)
    }

    private val closed = AtomicBoolean()
    private val busy = AtomicBoolean()
    private val active = AtomicReference<NovelArtifactCancellation?>()

    // A stuck platform resolver consumes at most one daemon per downloader, never an unbounded queue.
    private val dnsExecutor = ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        SynchronousQueue(),
        { task -> Thread(task, "s39-artifact-dns").apply { isDaemon = true } },
    )

    fun download(
        candidate: NovelDistributionEntry,
        cancellation: NovelArtifactCancellation = NovelArtifactCancellation(),
        onProgress: (NovelArtifactProgress) -> Unit = {},
    ): NovelArtifactDownloadResult {
        if (closed.get()) return NovelArtifactDownloadResult.Failure(NovelArtifactDownloadError.CLOSED)
        if (!busy.compareAndSet(
                false,
                true,
            )
        ) {
            return NovelArtifactDownloadResult.Failure(NovelArtifactDownloadError.BUSY)
        }
        active.set(cancellation)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var folder: Path? = null
        var partial: Path? = null
        var complete: Path? = null
        var received = 0L
        var observedHash: String? = null
        var client: OkHttpClient? = null
        var fatal: Error? = null
        fun checkActive() {
            if (cancellation.isCancelled || Thread.currentThread().isInterrupted || closed.get()) {
                throw Abort(NovelArtifactDownloadError.CANCELLED)
            }
            if (System.nanoTime() >= deadline) throw Abort(NovelArtifactDownloadError.TIMEOUT)
        }
        fun progress() {
            checkActive()
            try {
                onProgress(NovelArtifactProgress(received, candidate.size))
            } catch (_: Exception) {
                throw Abort(NovelArtifactDownloadError.OBSERVER_FAILED)
            }
            checkActive()
        }
        var result: NovelArtifactDownloadResult = try {
            checkActive()
            if (!NovelDistributionCatalogCodec.isValidExpectation(
                    candidate,
                )
            ) {
                throw Abort(NovelArtifactDownloadError.INVALID_CANDIDATE)
            }
            val url = candidate.url.toHttpUrlOrNull() ?: throw Abort(NovelArtifactDownloadError.INVALID_CANDIDATE)
            if (!origins.permits(url)) throw Abort(NovelArtifactDownloadError.ORIGIN_NOT_AUTHORIZED)
            // Refuse symlinks and shared storage, without chmodding or deleting the caller's root.
            val root = privateRoot.toAbsolutePath().normalize()
            val forbidden = setOf(
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE,
            )
            try {
                if (!Files.isDirectory(root, NOFOLLOW_LINKS) || root.toRealPath() != root ||
                    Files.getPosixFilePermissions(root).any { it in forbidden }
                ) {
                    throw Abort(NovelArtifactDownloadError.STORAGE)
                }
            } catch (_: IOException) {
                throw Abort(NovelArtifactDownloadError.STORAGE)
            }
            progress()
            val future = dnsExecutor.submit<List<InetAddress>> { resolver.lookup(url.host).toList() }
            val addresses = try {
                cancellation.register { future.cancel(true) }.use {
                    checkActive()
                    future.get(maxOf(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                }
            } finally {
                future.cancel(true)
            }
            checkActive()
            if (addresses.isEmpty()) throw Abort(NovelArtifactDownloadError.DNS_FAILURE)
            if (addresses.any { !origins.permits(it) }) throw Abort(NovelArtifactDownloadError.DESTINATION_REJECTED)
            val pinned = addresses.map { InetAddress.getByAddress(it.address) }.toList()
            val builder = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .socketFactory(DirectSockets(pinned, origins, url.port))
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        if (hostname != url.host) throw UnknownHostException("Unexpected artifact host")
                        return pinned
                    }
                })
                .cookieJar(CookieJar.NO_COOKIES)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .cache(null)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .fastFallback(false)
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            fixtureTls?.let { (factory, manager) -> builder.sslSocketFactory(factory, manager) }
            client = builder.build()
            val request = Request.Builder().url(url).header("Accept-Encoding", "identity").get().build()
            val call = client.newCall(request)
            call.timeout().deadlineNanoTime(deadline)
            cancellation.register { call.cancel() }.use {
                checkActive()
                call.execute().use { response ->
                    checkActive()
                    if (response.code in 300..399 ||
                        response.priorResponse != null
                    ) {
                        throw Abort(NovelArtifactDownloadError.REDIRECT)
                    }
                    if (response.code != 200) throw Abort(NovelArtifactDownloadError.HTTP_STATUS, response.code)
                    if (response.handshake == null) throw Abort(NovelArtifactDownloadError.NETWORK)
                    val encoding = response.header("Content-Encoding")
                    if (encoding != null &&
                        !encoding.equals("identity", true)
                    ) {
                        throw Abort(NovelArtifactDownloadError.CONTENT_ENCODING)
                    }
                    val lengths = response.headers.values("Content-Length")
                    if (lengths.size > 1 ||
                        (lengths.isNotEmpty() && lengths.single().toLongOrNull() != candidate.size)
                    ) {
                        throw Abort(NovelArtifactDownloadError.CONTENT_LENGTH)
                    }
                    val body = response.body
                    if (body.contentLength() >= 0 &&
                        body.contentLength() != candidate.size
                    ) {
                        throw Abort(NovelArtifactDownloadError.CONTENT_LENGTH)
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    try {
                        folder =
                            Files.createTempDirectory(
                                root,
                                "s39-",
                                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                            )
                        partial =
                            Files.createTempFile(
                                folder,
                                "bytes-",
                                ".partial",
                                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                            )
                        Files.newOutputStream(partial, WRITE, NOFOLLOW_LINKS).use { output ->
                            val input = body.byteStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                checkActive()
                                val count = try {
                                    input.read(
                                        buffer,
                                        0,
                                        minOf(
                                            buffer.size.toLong(),
                                            NovelDistributionCatalogCodec.MAX_APK_BYTES + 1 - received,
                                        ).toInt(),
                                    )
                                } catch (_: SocketTimeoutException) {
                                    throw Abort(NovelArtifactDownloadError.TIMEOUT)
                                } catch (_: IOException) {
                                    checkActive()
                                    throw Abort(
                                        if (received <
                                            candidate.size
                                        ) {
                                            NovelArtifactDownloadError.TRUNCATED
                                        } else {
                                            NovelArtifactDownloadError.NETWORK
                                        },
                                    )
                                }
                                if (count < 0) break
                                if (count == 0) continue
                                received += count
                                digest.update(buffer, 0, count)
                                if (received >
                                    NovelDistributionCatalogCodec.MAX_APK_BYTES
                                ) {
                                    throw Abort(NovelArtifactDownloadError.LIMIT_EXCEEDED)
                                }
                                if (received > candidate.size) throw Abort(NovelArtifactDownloadError.SIZE_MISMATCH)
                                output.write(buffer, 0, count)
                                progress()
                            }
                        }
                        observedHash = digest.digest().joinToString("") { "%02x".format(it) }
                        if (received != candidate.size) throw Abort(NovelArtifactDownloadError.TRUNCATED)
                        if (observedHash != candidate.sha256) throw Abort(NovelArtifactDownloadError.HASH_MISMATCH)
                        checkActive()
                        // Reserve our own destination before atomic replacement; never overwrite a sibling.
                        complete =
                            Files.createTempFile(
                                folder,
                                "artifact-",
                                ".quarantine",
                                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                            )
                        Files.move(partial, complete, ATOMIC_MOVE, REPLACE_EXISTING)
                        checkActive()
                    } catch (_: IOException) {
                        throw Abort(NovelArtifactDownloadError.STORAGE)
                    }
                }
            }
            NovelArtifactDownloadResult.Success(
                NovelQuarantinedArtifact(
                    requireNotNull(complete),
                    NovelArtifactIntegrity(candidate.size, received, candidate.sha256, requireNotNull(observedHash)),
                ),
            )
        } catch (failure: Abort) {
            NovelArtifactDownloadResult.Failure(failure.reason, received, observedHash, failure.status)
        } catch (_: TimeoutException) {
            NovelArtifactDownloadResult.Failure(NovelArtifactDownloadError.TIMEOUT, received)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            NovelArtifactDownloadResult.Failure(NovelArtifactDownloadError.CANCELLED, received)
        } catch (_: CancellationException) {
            NovelArtifactDownloadResult.Failure(NovelArtifactDownloadError.CANCELLED, received)
        } catch (_: ExecutionException) {
            NovelArtifactDownloadResult.Failure(NovelArtifactDownloadError.DNS_FAILURE)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            NovelArtifactDownloadResult.Failure(NovelArtifactDownloadError.DNS_FAILURE)
        } catch (_: SocketTimeoutException) {
            NovelArtifactDownloadResult.Failure(NovelArtifactDownloadError.TIMEOUT, received)
        } catch (failure: IOException) {
            NovelArtifactDownloadResult.Failure(
                when {
                    failure is RejectedDestination -> NovelArtifactDownloadError.DESTINATION_REJECTED
                    cancellation.isCancelled -> NovelArtifactDownloadError.CANCELLED
                    System.nanoTime() >= deadline -> NovelArtifactDownloadError.TIMEOUT
                    else -> NovelArtifactDownloadError.NETWORK
                },
                received,
            )
        } catch (_: SecurityException) {
            NovelArtifactDownloadResult.Failure(NovelArtifactDownloadError.STORAGE, received)
        } catch (_: UnsupportedOperationException) {
            NovelArtifactDownloadResult.Failure(NovelArtifactDownloadError.STORAGE, received)
        } catch (_: Exception) {
            NovelArtifactDownloadResult.Failure(NovelArtifactDownloadError.NETWORK, received)
        } catch (error: Error) {
            fatal = error
            NovelArtifactDownloadResult.Failure(NovelArtifactDownloadError.NETWORK, received)
        } finally {
            client?.connectionPool?.evictAll()
        }
        if (result !is NovelArtifactDownloadResult.Success) {
            try {
                partial?.let(Files::deleteIfExists)
                complete?.let(Files::deleteIfExists)
                folder?.let(Files::deleteIfExists)
            } catch (_: Exception) {
                result =
                    NovelArtifactDownloadResult.Failure(
                        NovelArtifactDownloadError.CLEANUP_FAILED,
                        received,
                        observedHash,
                    )
            }
        }
        active.compareAndSet(cancellation, null)
        busy.set(false)
        fatal?.let { throw it }
        return result
    }

    override fun close() {
        closed.set(true)
        active.get()?.cancel()
        dnsExecutor.shutdownNow()
    }

    private class Abort(val reason: NovelArtifactDownloadError, val status: Int? = null) : Exception()

    /** SocketFactory.getDefault() may honor global SOCKS properties; these sockets never do. */
    private class RejectedDestination : IOException("Artifact route rejected before connect")

    private class DirectSockets(
        private val addresses: List<InetAddress>,
        private val origins: NovelArtifactOriginPolicy,
        private val approvedPort: Int,
    ) : SocketFactory() {
        override fun createSocket(): Socket = object : Socket(Proxy.NO_PROXY) {
            override fun connect(endpoint: SocketAddress?, timeout: Int) {
                // IP-literal routes may bypass OkHttp.Dns. Enforce the frozen destination at the socket too.
                if (endpoint !is InetSocketAddress || endpoint.port != approvedPort ||
                    endpoint.address == null || endpoint.address !in addresses || !origins.permits(endpoint.address)
                ) {
                    throw RejectedDestination()
                }
                super.connect(endpoint, timeout)
            }
        }
        override fun createSocket(
            host: String,
            port: Int,
        ): Socket = throw SocketException("Pre-resolved direct sockets required")
        override fun createSocket(
            host: String,
            port: Int,
            local: InetAddress,
            localPort: Int,
        ): Socket = throw SocketException("Pre-resolved direct sockets required")
        override fun createSocket(
            host: InetAddress,
            port: Int,
        ): Socket = throw SocketException("Pre-resolved direct sockets required")
        override fun createSocket(
            host: InetAddress,
            port: Int,
            local: InetAddress,
            localPort: Int,
        ): Socket = throw SocketException("Pre-resolved direct sockets required")
    }
}
