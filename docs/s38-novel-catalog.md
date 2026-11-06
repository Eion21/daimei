# S38 novel catalog: metadata only, never remote installation

The S36 catalog remains a closed local-test proposal. Daimei's new consumer can read its metadata over bounded HTTPS, but `hostCompatible: false` is an absolute remote download/install/update block. Valid JSON does not prove host compatibility or grant execution permission. No separate debug protocol is supported.

## Contract and limits

- Exact S36 JSON fields and schema version 1; status `local-test`, boolean `hostCompatible: false`, every `artifact.downloadUrl` explicitly null, signing purpose `development-only`.
- Package identity is the real Android package name (`extensions[].id`), not a source ID, source name or UI suffix. Manga repo/index/protobuf formats are rejected, not adapted.
- Supported novel-api versions come from the existing host loader: 1.0–1.3. Unsupported API/schema versions, extra/missing fields, wrong types, duplicate keys/identities/filenames, invalid UTF-8 and malformed JSON are rejected.
- At most 2,000,000 response bytes, 1,000 entries, 256 characters per metadata string, 16 nesting levels and 16 unique signer digests per entry. Artifact size metadata is capped at 128 MiB; no APK is fetched. These are conservative consumer safety bounds, not a modification of S36's generator.
- Catalog URLs must be HTTPS, at most 2,048 characters and contain no credentials or fragment. No redirects, automatic retries, authentication, cookie store or shared manga repository client are enabled. Connect/read timeouts are 10 seconds, whole-call timeout 20 seconds. Compressed responses are refused; requests explicitly ask for identity encoding.
- Responses and streams are closed; typed errors do not include response bodies, raw server messages, credentials or URLs. A failed query is not a successful empty catalog. Network tests use isolated interceptors, not the public repositories.

## Architecture

`NovelCatalogClient` performs read-only HTTP GETs. `NovelCatalogCodec` validates bounded bytes and returns package-level novel metadata. Neither component depends on `ExtensionStoreRepository`, `TrustExtension`, `ExtensionInstaller`, package installation or classloading. Catalog certificate strings are untrusted expected values, not trust enrollment.

The existing manga store protocol, novel installed-extension loader, source identities, signature/trust gates and reading data remain unchanged. Future policy preparation is isolated from these runtime paths.

## Future APK policies, not an installer

`NovelApkIdentity` represents observations that a future independent platform inspector must establish. Catalog metadata cannot supply verified signer observations. The isolated policy checks bytes against expected SHA-256/size, actual package/version/entry/API/minSdk/kind metadata, verified certificate set, installed signing identity and increasing version, explicit trust, interactive system installation and package context availability.

Even matching observations cannot authorize an installation in S38. Policy findings retain host incompatibility and the absent download URL. Remote download/install/update requests must fail unconditionally. There is no private, silent, Shizuku, trust-enrollment or classloader handoff.

These tests use synthetic bytes and observations; they are **not** APK parsing, signature cryptography or Android installation proof. Preserving the existing loader is not a claim that an untrusted APK is sandboxed: loaded extensions execute in the host process. Jabra-style context requirements cannot be replaced by private `.ext` loading.

## UI integration and verification status

Explore → Extensions has one **Extension repositories** overflow action; the empty state and Browse settings open the same novel screen. Legacy manga repository links are rejected with a notice, not converted or fetched. The query is temporary and no novel repository is saved. Enter an HTTPS catalog URL and explicitly request metadata; there is no default, automatic query, or enrollment in manga stores. Loading and typed failures are distinct from available metadata. Rows show the real package name, catalog origin without query or fragment, version, novel API, and `hostCompatible: false`; remote installation is not enabled. The screen has no install, update, download, or trust action. Existing installed-extension management stays on the original screen.

The earlier S38 revision passed 23 focused JVM tests (six presentation-state tests plus 17 consumer/policy tests); those historical results are not new evidence. The unified revision passed 25 focused JVM tests, including two new callback tests, and 388 full app JVM tests with no failures, errors or skips. The focused tests overlap the full suite and are not added together. Six source/resource contracts and focused six-file Kotlin formatting passed. Independent security verification passed the scoped review and six copied contracts plus two extra structural probes; its auxiliary inventory command failed by treating XML as JSON and was not retried. Separate Git checks and the parent fixture/preservation checks supply the remaining evidence.

The initial structural RED was observed, but its temporary raw log expired. A manifest-test assumption and an initial new-test compilation error were corrected; their disposition and the retained failed/green JVM logs are documented, not represented as product behavior failures. Exact independent guard-order and Spanish-copy assertions are retained in the source contract tests.

This UI has not been rendered or exercised on a phone. These tests are not extension-APK parsing, extension-signature cryptography, data-preservation or device-acceptance proof. Native assessment could not delimit undeclared untracked files; there is no native review approval. See the surrounding workspace's `s38/unified-repositories/evidence/` for the separate checks and limitations.

From the workspace parent, the S38 runner uses the existing offline Gradle cache/SDK and the established no-signing init script:

```sh
python3 s38/run-gradle.py my-s38-check main :app:testDebugUnitTest \
  --tests 'eu.kanade.tachiyomi.extension.novel.NovelCatalogTest'
# Later feature gates use distinct evidence labels; do not overwrite earlier RED/GREEN evidence.
```

The runner is an evidence wrapper around the checked-in Gradle wrapper, not a package installer. It retains native command, exit code, stdout, stderr and JUnit results under `s38/evidence/<label>/`. The runner performs no signing or device actions. Separate local signing uses only the existing development key under explicit owner authorization; it never authorizes installation.

## Requirements before any future promotion

A separate, explicitly authorized protocol change must define non-null immutable artifact HTTPS URLs, origin/redirect and repository ownership/enrollment policies, actual Android APK metadata and cryptographic signature verification, same-signer/version continuity, explicit per-version trust and interactive installation. It also needs current real-APK evidence, end-to-end host/catalog compatibility proof and owner device acceptance. Only then could the owner authorize changing `hostCompatible`; S38 itself cannot promote it.

No local or remote S36 snapshot is changed, and no APK/catalog is published by this work. Gradle builds remain unsigned with signing disabled. A separately authorized local debug copy, `s38/unified-repositories/artifacts/Daimei-S38-unified-repositories-debug-signed.apk` in the surrounding workspace, passed APK signature, alignment, ZIP-payload and identity checks using the unchanged existing Daimei development key. Its version remains `0.1.0-118`, versionCode 1; the filename and SHA-256 distinguish the new build, not a version increment. The exact APK/certificate hashes and native outputs are in `evidence/apk-preparation/` under that workspace directory. No phone was accessed or updated by this revision, no fresh current-device compatibility is claimed, and installation/visual acceptance require later owner authorization.
