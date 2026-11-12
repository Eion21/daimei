package eu.kanade.tachiyomi.extension.novel

import okhttp3.Dns
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class NovelArtifactDownloaderTest {
    @TempDir lateinit var root: Path

    @BeforeEach fun trustMarker() {
        Files.writeString(root.resolve("existing-trust-marker"), "unchanged")
    }

    @AfterEach fun noTrustChangesOrPartialFiles() {
        assertEquals("unchanged", Files.readString(root.resolve("existing-trust-marker")))
        Files.walk(root).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().endsWith(".partial") }) }
    }

    private fun downloader(
        fixture: NovelArtifactHttpsFixture,
        timeoutMillis: Long = 10_000,
        policy: NovelArtifactOriginPolicy = NovelArtifactOriginPolicy.controlledLoopback(fixture.origin),
        resolver: Dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
        },
        directory: Path = root,
    ) = NovelArtifactDownloader(directory, policy, timeoutMillis, resolver, tls.context.socketFactory, tls.trustManager)

    private fun failure(result: NovelArtifactDownloadResult, reason: NovelArtifactDownloadError) {
        assertTrue(result is NovelArtifactDownloadResult.Failure, "Expected $reason, got $result")
        assertEquals(reason, (result as NovelArtifactDownloadResult.Failure).reason)
        Files.list(root).use {
            assertEquals(listOf("existing-trust-marker"), it.map { p -> p.fileName.toString() }.toList())
        }
    }

    @Test fun `valid HTTPS download preserves only exact verified quarantine bytes and monotonic progress`() {
        NovelArtifactHttpsFixture(tls).use { server ->
            downloader(server).use { downloader ->
                val progress = mutableListOf<NovelArtifactProgress>()
                val candidate = server.candidate()
                val result = downloader.download(candidate, onProgress = progress::add)
                assertTrue(result is NovelArtifactDownloadResult.Success)
                val artifact = (result as NovelArtifactDownloadResult.Success).artifact
                assertArrayEquals(server.payload, Files.readAllBytes(artifact.path))
                assertEquals(candidate.size, artifact.integrity.observedSize)
                assertEquals(candidate.sha256, artifact.integrity.observedSha256)
                assertEquals(artifact.integrity.expectedSize, artifact.integrity.observedSize)
                assertEquals(artifact.integrity.expectedSha256, artifact.integrity.observedSha256)
                assertTrue(artifact.path.startsWith(root))
                assertFalse(artifact.path.fileName.toString().endsWith(".apk"))
                assertTrue(progress.isNotEmpty())
                assertEquals(candidate.size, progress.last().receivedBytes)
                assertTrue(progress.zipWithNext().all { (a, b) -> a.receivedBytes <= b.receivedBytes })
                assertTrue(progress.all { it.expectedBytes == candidate.size })
                assertEquals(1, server.requests.get())
            }
        }
    }

    @Test fun `wrong hash rejects real received bytes and removes own partial only`() {
        NovelArtifactHttpsFixture(tls).use { server ->
            downloader(server).use { d ->
                val result = d.download(server.candidate(hash = "a".repeat(64)))
                failure(result, NovelArtifactDownloadError.HASH_MISMATCH)
                assertEquals(server.payload.size.toLong(), (result as NovelArtifactDownloadResult.Failure).observedSize)
                assertEquals(NovelArtifactHttpsFixture.sha256(server.payload), result.observedSha256)
                assertEquals(1, server.requests.get())
            }
        }
    }

    @Test fun `wrong size with absent content length rejects real stream`() {
        NovelArtifactHttpsFixture(tls).use { server ->
            server.mode = "chunked"
            downloader(server).use { d ->
                failure(
                    d.download(server.candidate(size = server.payload.size.toLong() - 1)),
                    NovelArtifactDownloadError.SIZE_MISMATCH,
                )
                assertEquals(1, server.requests.get())
            }
        }
    }

    @Test fun `truncated fixed length response rejects and cleans partial`() {
        NovelArtifactHttpsFixture(tls).use { server ->
            server.mode = "truncated"
            downloader(server).use { d ->
                failure(d.download(server.candidate()), NovelArtifactDownloadError.TRUNCATED)
                assertEquals(1, server.requests.get())
            }
        }
    }

    @Test fun `false content length rejects before trusting the body`() {
        NovelArtifactHttpsFixture(tls).use { server ->
            server.mode = "false-length"
            downloader(server).use { d ->
                failure(d.download(server.candidate()), NovelArtifactDownloadError.CONTENT_LENGTH)
                assertEquals(1, server.requests.get())
            }
        }
    }

    @Test fun `absent content length succeeds only after exact size and hash at EOF`() {
        NovelArtifactHttpsFixture(tls).use { server ->
            server.mode = "chunked"
            downloader(server).use { d ->
                val result = d.download(server.candidate())
                assertTrue(result is NovelArtifactDownloadResult.Success)
                assertArrayEquals(
                    server.payload,
                    Files.readAllBytes((result as NovelArtifactDownloadResult.Success).artifact.path),
                )
                assertEquals(1, server.requests.get())
            }
        }
    }

    @Test fun `HTTP failure is typed with no quarantined artifact`() {
        NovelArtifactHttpsFixture(tls).use { server ->
            server.mode = "http-error"
            downloader(server).use { d ->
                val result = d.download(server.candidate())
                failure(result, NovelArtifactDownloadError.HTTP_STATUS)
                assertEquals(503, (result as NovelArtifactDownloadResult.Failure).httpStatus)
                assertEquals(1, server.requests.get())
            }
        }
    }

    @Test fun `neither HTTP nor HTTPS redirects are followed`() {
        NovelArtifactHttpsFixture(tls).use { server ->
            server.mode = "redirect"
            downloader(server).use { d ->
                listOf(
                    "http://localhost:${URI(server.origin).port}/target",
                    "${server.origin}/target",
                ).forEach { target ->
                    server.redirect = target
                    failure(d.download(server.candidate()), NovelArtifactDownloadError.REDIRECT)
                }
                assertEquals(2, server.requests.get())
            }
        }
    }

    @Test fun `cancel during real streaming aborts request and deletes partial only`() {
        NovelArtifactHttpsFixture(tls).use { server ->
            server.mode = "slow"
            downloader(server).use { d ->
                val token = NovelArtifactCancellation()
                var received = false
                val result = d.download(server.candidate(size = 819_200), token) { progress ->
                    if (progress.receivedBytes > 0) {
                        received = true
                        token.cancel()
                    }
                }
                assertTrue(received)
                failure(result, NovelArtifactDownloadError.CANCELLED)
                assertEquals(1, server.requests.get())
            }
        }
    }

    @Test fun `pre cancelled operation cannot connect or create quarantine files`() {
        NovelArtifactHttpsFixture(tls).use { server ->
            downloader(server).use { d ->
                val token = NovelArtifactCancellation().apply { cancel() }
                failure(d.download(server.candidate(), token), NovelArtifactDownloadError.CANCELLED)
                assertEquals(0, server.requests.get())
            }
        }
    }

    @Test fun `total deadline aborts a real stalled HTTPS body`() {
        NovelArtifactHttpsFixture(tls).use { server ->
            server.mode = "stall"
            downloader(server, timeoutMillis = 800).use { d ->
                val start = System.nanoTime()
                failure(d.download(server.candidate()), NovelArtifactDownloadError.TIMEOUT)
                assertTrue((System.nanoTime() - start) / 1_000_000 < 3000)
                assertEquals(1, server.requests.get())
            }
        }
    }

    @Test fun `deadline also bounds DNS before any connection`() {
        NovelArtifactHttpsFixture(tls).use { server ->
            val dns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    Thread.sleep(5_000)
                    return listOf(InetAddress.getByName("127.0.0.1"))
                }
            }
            downloader(server, timeoutMillis = 100, resolver = dns).use { d ->
                val start = System.nanoTime()
                failure(d.download(server.candidate()), NovelArtifactDownloadError.TIMEOUT)
                assertTrue((System.nanoTime() - start) / 1_000_000 < 1500)
                assertEquals(0, server.requests.get())
            }
        }
    }

    @Test fun `stream crossing 128 MiB is rejected without leaving a partial`() {
        NovelArtifactHttpsFixture(tls).use { server ->
            server.mode = "huge"
            downloader(server, timeoutMillis = 30_000).use { d ->
                val result = d.download(server.candidate(size = NovelDistributionCatalogCodec.MAX_APK_BYTES))
                failure(result, NovelArtifactDownloadError.LIMIT_EXCEEDED)
                assertEquals(
                    NovelDistributionCatalogCodec.MAX_APK_BYTES + 1,
                    (result as NovelArtifactDownloadResult.Failure).observedSize,
                )
                assertEquals(1, server.requests.get())
            }
        }
    }

    @Test fun `production origin authorization never inherits loopback access from catalog`() {
        NovelArtifactHttpsFixture(tls).use { server ->
            downloader(server, policy = NovelArtifactOriginPolicy.publicHttps(setOf(server.origin))).use { d ->
                failure(d.download(server.candidate()), NovelArtifactDownloadError.DESTINATION_REJECTED)
                assertEquals(0, server.requests.get())
            }
        }
    }

    @Test fun `unapproved origin is rejected before DNS and any network call`() {
        NovelArtifactHttpsFixture(tls).use { server ->
            val lookups = AtomicInteger()
            val dns = object : Dns {
                override fun lookup(
                    hostname: String,
                ): List<InetAddress> {
                    lookups.incrementAndGet()
                    error("Must not resolve")
                }
            }
            downloader(
                server,
                policy = NovelArtifactOriginPolicy.publicHttps(setOf("https://approved.example")),
                resolver = dns,
            ).use { d ->
                failure(d.download(server.candidate()), NovelArtifactDownloadError.ORIGIN_NOT_AUTHORIZED)
                assertEquals(0, lookups.get())
                assertEquals(0, server.requests.get())
            }
        }
    }

    @Test fun `mixed safe and private DNS answers fail closed before connecting`() {
        NovelArtifactHttpsFixture(tls).use { server ->
            val dns = object : Dns {
                override fun lookup(
                    hostname: String,
                ) = listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("127.0.0.1"))
            }
            downloader(
                server,
                policy = NovelArtifactOriginPolicy.publicHttps(setOf(server.origin)),
                resolver = dns,
            ).use { d ->
                failure(d.download(server.candidate()), NovelArtifactDownloadError.DESTINATION_REJECTED)
                assertEquals(0, server.requests.get())
            }
        }
    }

    @Test fun `validated DNS answer is pinned through actual TLS connection`() {
        NovelArtifactHttpsFixture(tls).use { server ->
            val lookups = AtomicInteger()
            val dns = object : Dns {
                override fun lookup(hostname: String) = listOf(
                    InetAddress.getByName(
                        if (lookups.incrementAndGet() ==
                            1
                        ) {
                            "127.0.0.1"
                        } else {
                            "10.0.0.1"
                        },
                    ),
                )
            }
            downloader(server, resolver = dns).use { d ->
                assertTrue(d.download(server.candidate()) is NovelArtifactDownloadResult.Success)
                assertEquals(1, lookups.get())
                assertEquals(1, server.requests.get())
            }
        }
    }

    @Test fun `dedicated transport ignores default proxies SOCKS cookies and authentication`() {
        NovelArtifactHttpsFixture(tls).use { server ->
            val oldSelector = ProxySelector.getDefault()
            val oldHost = System.getProperty("socksProxyHost")
            val oldPort = System.getProperty("socksProxyPort")
            val selections = AtomicInteger()
            try {
                ProxySelector.setDefault(object : ProxySelector() {
                    override fun select(
                        uri: URI,
                    ): List<Proxy> {
                        selections.incrementAndGet()
                        error("Must not consult system proxy")
                    }
                    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) = Unit
                })
                System.setProperty("socksProxyHost", "127.0.0.1")
                System.setProperty("socksProxyPort", "1")
                downloader(server).use { d ->
                    assertTrue(d.download(server.candidate()) is NovelArtifactDownloadResult.Success)
                    assertTrue(d.download(server.candidate()) is NovelArtifactDownloadResult.Success)
                    server.mode = "unauthorized"
                    failureWithoutRootAssertion(d.download(server.candidate()), NovelArtifactDownloadError.HTTP_STATUS)
                }
                assertEquals(0, selections.get())
                assertEquals(3, server.requests.get())
                server.headers.forEach { headers ->
                    assertFalse(
                        headers.keys.any {
                            it.equals("Cookie", true) || it.equals("Authorization", true) ||
                                it.equals("Proxy-Authorization", true)
                        },
                    )
                    assertEquals(
                        listOf("identity"),
                        headers.entries.single {
                            it.key.equals("Accept-Encoding", true)
                        }.value,
                    )
                }
            } finally {
                ProxySelector.setDefault(oldSelector)
                if (oldHost ==
                    null
                ) {
                    System.clearProperty("socksProxyHost")
                } else {
                    System.setProperty("socksProxyHost", oldHost)
                }
                if (oldPort ==
                    null
                ) {
                    System.clearProperty("socksProxyPort")
                } else {
                    System.setProperty("socksProxyPort", oldPort)
                }
            }
        }
    }

    @Test fun `encoded response is never transparently decompressed into a trusted artifact`() {
        NovelArtifactHttpsFixture(tls).use { server ->
            server.mode = "encoded"
            downloader(server).use { d ->
                failure(d.download(server.candidate()), NovelArtifactDownloadError.CONTENT_ENCODING)
                assertEquals(1, server.requests.get())
            }
        }
    }

    @Test fun `symlink quarantine root is refused without touching target data`() {
        NovelArtifactHttpsFixture(tls).use { server ->
            val target = Files.createDirectory(root.resolve("existing-data"))
            Files.writeString(target.resolve("keep"), "owned-by-someone-else")
            val link = Files.createSymbolicLink(root.resolve("alias"), target)
            downloader(server, directory = link).use { d ->
                failureWithoutRootAssertion(d.download(server.candidate()), NovelArtifactDownloadError.STORAGE)
                assertEquals("owned-by-someone-else", Files.readString(target.resolve("keep")))
                assertEquals(0, server.requests.get())
            }
        }
    }

    @Test fun `reproducible HTTPS demonstration records integrity cancellation and rejection without installation`() {
        NovelArtifactHttpsFixture(tls).use { server ->
            downloader(server).use { d ->
                val candidate = server.candidate()
                val ok = d.download(candidate)
                assertTrue(ok is NovelArtifactDownloadResult.Success)
                val artifact = (ok as NovelArtifactDownloadResult.Success).artifact
                assertArrayEquals(server.payload, Files.readAllBytes(artifact.path))
                println(
                    "S39_DEMO origin=${server.origin} url=${candidate.url} " +
                        "expectedSize=${candidate.size} observedSize=${artifact.integrity.observedSize} " +
                        "expectedSha256=${candidate.sha256} observedSha256=${artifact.integrity.observedSha256} " +
                        "quarantine=${artifact.path} exists=${Files.isRegularFile(artifact.path)} " +
                        "signature=NOT_IMPLEMENTED installation=BLOCKED trust=UNCHANGED",
                )
                server.mode = "slow"
                val token = NovelArtifactCancellation()
                val cancelled = d.download(server.candidate(size = 819_200), token) {
                    if (it.receivedBytes >
                        0
                    ) {
                        token.cancel()
                    }
                }
                failureWithoutRootAssertion(cancelled, NovelArtifactDownloadError.CANCELLED)
                server.mode = "redirect"
                val rejected = d.download(candidate)
                failureWithoutRootAssertion(rejected, NovelArtifactDownloadError.REDIRECT)
                println(
                    "S39_DEMO cancellation=${(cancelled as NovelArtifactDownloadResult.Failure).reason} " +
                        "redirect=${(rejected as NovelArtifactDownloadResult.Failure).reason} " +
                        "artifactStillExists=${Files.isRegularFile(artifact.path)} " +
                        "trustMarker=${Files.readString(root.resolve("existing-trust-marker"))} installs=0",
                )
                assertTrue(Files.isRegularFile(artifact.path))
            }
        }
    }

    @Test fun `IP literal cannot bypass validated addresses even with a faulty resolver`() {
        NovelArtifactHttpsFixture(tls, host = "127.0.0.1").use { server ->
            val faulty = object : Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getByName("93.184.216.34"))
            }
            downloader(
                server,
                policy = NovelArtifactOriginPolicy.publicHttps(setOf(server.origin)),
                resolver = faulty,
            ).use { d ->
                failure(d.download(server.candidate()), NovelArtifactDownloadError.DESTINATION_REJECTED)
                assertEquals(0, server.requests.get())
            }
        }
    }

    private fun failureWithoutRootAssertion(result: NovelArtifactDownloadResult, reason: NovelArtifactDownloadError) {
        assertTrue(result is NovelArtifactDownloadResult.Failure)
        assertEquals(reason, (result as NovelArtifactDownloadResult.Failure).reason)
    }

    companion object {
        private lateinit var tls: NovelArtifactFixtureTls

        @BeforeAll @JvmStatic
        fun createTls() {
            tls = NovelArtifactFixtureTls()
        }

        @AfterAll @JvmStatic
        fun destroyTls() {
            tls.close()
        }
    }
}
