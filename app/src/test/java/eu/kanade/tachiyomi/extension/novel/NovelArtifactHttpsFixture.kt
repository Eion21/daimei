package eu.kanade.tachiyomi.extension.novel

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Real, isolated TLS. The disposable fixture CA/key never modifies a system trust store. */
internal class NovelArtifactFixtureTls : Closeable {
    private val root = Files.createTempDirectory("s39-disposable-tls-")
    val context: SSLContext
    val trustManager: X509TrustManager

    init {
        val store = root.resolve("fixture.p12")
        val log = root.resolve("keytool.log")
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", "fixture", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
            "-validity", "2", "-storetype", "PKCS12", "-keystore", store.toString(),
            "-storepass", "fixture-only", "-keypass", "fixture-only", "-noprompt",
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        check(process.waitFor(20, TimeUnit.SECONDS)) { "Fixture keytool timed out; no TLS probes ran" }
        check(process.exitValue() == 0) { "Fixture keytool failed: ${Files.readString(log)}" }
        val keys = KeyStore.getInstance("PKCS12")
        Files.newInputStream(store).use { keys.load(it, "fixture-only".toCharArray()) }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        km.init(keys, "fixture-only".toCharArray())
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tm.init(keys)
        trustManager = tm.trustManagers.filterIsInstance<X509TrustManager>().single()
        context = SSLContext.getInstance("TLS").apply { init(km.keyManagers, arrayOf(trustManager), null) }
    }

    override fun close() {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}

internal class NovelArtifactHttpsFixture(
    tls: NovelArtifactFixtureTls,
    private val host: String = "localhost",
) : Closeable {
    val payload = "Daimei S39 inert HTTPS integrity fixture.\n".toByteArray()
    val requests = AtomicInteger()
    val headers = CopyOnWriteArrayList<Map<String, List<String>>>()

    @Volatile var mode = "valid"

    @Volatile var redirect = "https://localhost/forbidden"
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "s39-local-https").apply { isDaemon = true }
    }
    private val server = HttpsServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    val origin: String get() = "https://$host:${server.address.port}"

    init {
        server.httpsConfigurator = HttpsConfigurator(tls.context)
        server.executor = executor
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            headers.add(exchange.requestHeaders.mapValues { it.value.toList() })
            try {
                val behavior = mode
                exchange.responseHeaders.add("Set-Cookie", "fixture=must-not-return; Secure")
                when (behavior) {
                    "redirect" -> {
                        exchange.responseHeaders.add("Location", redirect)
                        exchange.sendResponseHeaders(302, -1)
                    }
                    "http-error" -> exchange.sendResponseHeaders(503, -1)
                    "unauthorized" -> {
                        exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=fixture")
                        exchange.sendResponseHeaders(401, -1)
                    }
                    "truncated", "false-length" -> {
                        exchange.sendResponseHeaders(
                            200,
                            payload.size.toLong() + if (behavior == "false-length") 7 else 0,
                        )
                        exchange.responseBody.write(
                            if (behavior ==
                                "truncated"
                            ) {
                                payload.copyOf(payload.size / 2)
                            } else {
                                payload
                            },
                        )
                    }
                    "stall" -> {
                        exchange.sendResponseHeaders(200, payload.size.toLong())
                        Thread.sleep(5_000)
                    }
                    "huge" -> {
                        exchange.sendResponseHeaders(200, 0)
                        val chunk = ByteArray(65536) { 7 }
                        repeat((NovelDistributionCatalogCodec.MAX_APK_BYTES / chunk.size + 1).toInt()) {
                            exchange.responseBody.write(chunk)
                        }
                    }
                    "slow" -> {
                        exchange.sendResponseHeaders(200, 0)
                        repeat(100) {
                            exchange.responseBody.write(ByteArray(8192) { 3 })
                            exchange.responseBody.flush()
                            Thread.sleep(30)
                        }
                    }
                    else -> {
                        if (behavior == "encoded") exchange.responseHeaders.add("Content-Encoding", "gzip")
                        exchange.sendResponseHeaders(200, if (behavior == "chunked") 0 else payload.size.toLong())
                        exchange.responseBody.write(payload)
                    }
                }
            } catch (_: java.io.IOException) {
                // Expected when the real client cancels, times out, rejects headers, or hits the cap.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    fun candidate(size: Long = payload.size.toLong(), hash: String = sha256(payload)): NovelDistributionEntry =
        NovelDistributionCatalogCodec.decode(
            """{"protocol":"daimei-novel-distribution","schemaVersion":2,"repository":"isolated-fixture",
              "extensions":[{"packageName":"app.novel.extension.fixture","longVersionCode":1,
              "versionName":"1","novelApi":"1.1","entryClasses":["app.novel.extension.fixture.Source"],
              "minSdk":26,"artifact":{"url":"$origin/fixture.bin","size":$size,"sha256":"$hash"},
              "currentSigners":["${"0".repeat(64)}"]}]}""".byteInputStream(),
        ).entries.single()

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS))
    }

    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
