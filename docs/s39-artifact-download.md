# S39 artifact download and byte integrity

Stage2B-1 adds a headless, S39-only acquisition API. It does not connect S38 to downloads or add installation UI. The original S38 refusal and S39 installation-policy contracts remain independent.

## Boundary

A `NovelDistributionEntry` decoded from `daimei-novel-distribution` schema2 supplies **untrusted expectations**. It cannot authorize its own origin. The host separately supplies `NovelArtifactOriginPolicy.publicHttps(setOf("https://approved-artifacts.example"))` from trusted application configuration. No origin is enabled by default; the catalog's repository or signer declarations are not enrollment or trust.

The downloader validates the entry and exact HTTPS scheme/host/effective-port before connecting. All DNS answers must pass the public-destination policy, then those exact addresses are frozen for the operation. The direct socket also checks the actual selected IP and port before connect, including IP-literal routes that can bypass OkHttp DNS. Private, loopback, link-local, reserved, multicast and transition destinations are rejected rather than silently filtered from a mixed answer set. A bounded resolver executor and the remaining operation deadline prevent a slow resolver from making the caller wait indefinitely.

A fresh dedicated OkHttp client uses direct sockets, no system HTTP/SOCKS proxy, no cookies, no credentials or authenticator, no redirects/retry/cache/interceptors, and no shared Mihon networking state. HTTPS uses platform certificate/hostname verification. Requests require identity encoding; encoded responses and unexpected Content-Length are rejected. A missing length is allowed only if the received stream ultimately matches both expected size and SHA-256.

## Host API

Invoke the blocking API from an I/O executor, never the Android main thread. Supply an existing canonical, private directory (for example a host-created directory below `Context.filesDir`), not a catalog path or external shared-storage directory.

```kotlin
val origins = NovelArtifactOriginPolicy.publicHttps(setOf("https://approved-artifacts.example"))
val cancellation = NovelArtifactCancellation()
NovelArtifactDownloader(privateQuarantineRoot, origins).use { downloader ->
    val result = downloader.download(s39Entry, cancellation) { progress ->
        // Observe receivedBytes and expectedBytes without granting any trust.
    }
    when (result) {
        is NovelArtifactDownloadResult.Success -> {
            val quarantine = result.artifact
            // Byte integrity only. Do not install, load, or treat it as a verified signer.
        }
        is NovelArtifactDownloadResult.Failure -> {
            // Present the typed reason. No successful quarantine artifact is returned.
        }
    }
}
// cancellation.cancel() is callable from another thread or the progress observer.
```

The cap is `NovelDistributionCatalogCodec.MAX_APK_BYTES` (128MiB). Default total timeout is120seconds, including DNS and body processing; tests may select a shorter finite deadline. Timeout and cancellation close/cancel transport and clean only this operation's owned partial. Observers must be fast and nonblocking; observer failures also require cleanup. A cancelled token is not reusable for an uncancelled operation.

## Storage and evidence

The host's private root is not purged or chmodded. Each operation owns a new0700 child and a0600 partial file with generated names, never names from URL/package metadata. Only after EOF, exact size and SHA-256 verification does an atomic move promote the file to quarantine. Failure does not delete siblings or unrelated artifacts. Cleanup failures are typed rather than reported as success.

`NovelArtifactIntegrity` records expected and observed byte counts/digests. `NovelQuarantinedArtifact` records the retained path and integrity result. Neither is `NovelVerifiedCandidate`, cryptographic APK-signature evidence, a trust preference, or installation authorization. Private files remain mutable by trusted host code: any later signature/installation stage must bind its own evidence to the bytes it actually inspects, not assume this old result authorizes changed bytes.

## Real isolated HTTPS tests and demonstration

`NovelArtifactDownloaderTest` runs a real JDK `HttpsServer` bound to127.0.0.1 with a generated disposable TLS fixture certificate. Test code trusts only that fixture; it never modifies a system trust store. An **internal** controlled-loopback enrollment is restricted to the exact fixture HTTPS authority/port and loopback addresses. Public enrollment does not inherit that exception, and catalog input cannot select it. The test DNS resolver is pinned just like production; public-policy refusal cases never connect to the supplied destinations.

Run:

```sh
./gradlew --offline --no-daemon :app:testDebugUnitTest --tests '*NovelArtifactDownloaderTest' --info
```

The `reproducible HTTPS demonstration records integrity cancellation and rejection without installation` test invokes the same production downloader and prints `S39_DEMO` receipts with origin/URL, expected/observed size and SHA-256, the existing quarantine path, cancellation and redirect rejection. JUnit removes its own temporary state after assertions; it does not make an installation claim. Fixtures are inert text bytes, not executable APKs.

Evidence and exact RED/GREEN outcomes live under `../odd/s39/stage2b/`. Implementation/testing status must be read from that delivery record; this API guide alone is not proof of a passing build. No emulator is required or authorized by this stage.
