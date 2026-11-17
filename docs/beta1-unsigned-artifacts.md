# Daimei Beta 1 — unsigned artifact checkpoint

Source repository: `Eion21/daimei`; exact source commit: `141033dfbdf717ac7cf34a7a3d7bfd140da2c1d3`.
This is a local unsigned preparation checkpoint, NOT a signed/public Beta 1 release.
All outputs were built in a registered detached same-clone worktree; experimental outputs were preserved.

## Exact unsigned APK identities

| Module | ABI | versionName / code | Bytes | SHA-256 of unsigned bytes |
|---|---|---|---:|---|
| app | universal | 0.1.0 / 1 | 67903380 | `4b315085465739e188bda5b561d342ebc1d4d51ea9fcda61bcc107a658ae2520` |
| app | arm64-v8a | 0.1.0 / 1 | 27184781 | `9bff13595b260aa1db87cb20014bdc8f184629ab3f2c4f6a034c15a4300c6711` |
| app | x86 | 0.1.0 / 1 | 28539447 | `7fe7477662b9eba43e6e8bd3e2fd8d3a216990121f0ac92cddbfd3e6c49654df` |
| app | armeabi-v7a | 0.1.0 / 1 | 24005263 | `f496441ffd9441d020171b22d58e40843eecc04cc811f790bb8d224885ca2d5c` |
| app | x86_64 | 0.1.0 / 1 | 29612368 | `be8716169f3e6214c274f2b324586dfcf97cde959c8037f39d5ff76c97100cdf` |
| novel-matchanovels-extension | universal | 1.0.2 / 3 | 198564 | `a82a8dcceb1efb10d4168cf42cb25cab9d1db80fc1bd1a267c242dbad8e64a0c` |
| novel-skynovels-extension | universal | 1.0.2 / 3 | 19436 | `015b019ec62eaacc71870007bb645b9207fb775511e564e60923c4a4a2009a5f` |
| novel-jabrascan-extension | universal | 1.0.3 / 4 | 8462220 | `0a40de5cad674d4ea2e523e85e0bb1912b122a53b01d4a247547e053c4cabf15` |

Local evidence root: `../odd/s39/public-release-readiness/` from the repository root.
Inventory: `unsigned-apks-141033df-pass5/inventory.json`; checksums: `unsigned-apks-141033df-pass5/SHA256SUMS`.
APK paths relative to that evidence root:
- `unsigned-apks-141033df-pass5/app-universal/app-universal-release-unsigned.apk`
- `unsigned-apks-141033df-pass5/app-arm64-v8a/app-arm64-v8a-release-unsigned.apk`
- `unsigned-apks-141033df-pass5/app-x86/app-x86-release-unsigned.apk`
- `unsigned-apks-141033df-pass5/app-armeabi-v7a/app-armeabi-v7a-release-unsigned.apk`
- `unsigned-apks-141033df-pass5/app-x86_64/app-x86_64-release-unsigned.apk`
- `unsigned-apks-141033df-pass5/novel-matchanovels-extension-universal/novel-matchanovels-extension-release-unsigned.apk`
- `unsigned-apks-141033df-pass5/novel-skynovels-extension-universal/novel-skynovels-extension-release-unsigned.apk`
- `unsigned-apks-141033df-pass5/novel-jabrascan-extension-universal/novel-jabrascan-extension-release-unsigned.apk`

## Observed checks

- All eight APK ZIPs are intact; aapt2 compiled-manifest/badging checks match package, version, minSdk26 and targetSdk36. Five app merged manifests are validated, one per ABI output.
- App identity: `app.daimei`, visible Daimei; five outputs: universal, arm64-v8a, armeabi-v7a, x86 and x86_64. Native ABI contents match their splits; universal contains ARM64.
- Extension identities: Matcha `app.novel.extension.matchanovels`, API1.1, source ID `matchanovels`; Sky `app.novel.extension.skynovels`, API1.1, source ID `skynovels`; Jabra `app.novel.extension.jabrascan`, API1.3, source ID `jabrascan`.
- Extension entry classes exist in DEX. No host novel-api, Kotlin stdlib or coroutines class definitions are bundled. Jabra PDF resources are present. Source-ID constants match the chosen committed source and identifier bytes exist in DEX; this is not runtime source registration or install/update continuity proof. Unchanged SourceInfo.version1.0.1 is distinct from APK versionName.
- All eight have no APK signing block and no JAR signature entries. apksigner verification returns nonzero as EXPECTED for unsigned APKs; it is not a valid-signature PASS and no signer certificate is attached.
- Actual Gradle model checks pass for all four Release signingConfig=null and Debug signingConfig=debug. App Debug/S39QA/foss/nightly/benchmark configurations remain as before; no package IDs/source IDs changed.
- All21 preexisting APK hashes are unchanged. No earlier490JVM/189extension suites, Android tests, external verifiers or old audits were rerun.
- Build job40 exited0 after381s:597 tasks,393 executed/204 from cache. Final inspector job45 exited0 after5s. Initial inspector jobs41–44 exited1 due local harness assumptions (multiple ABI manifests, AGP filter value, aapt2 minSdkVersion, DEX type_ids_off). Failures/logs/partial directories are preserved, not reclassified as APK defects or successful runs. No APK rebuild occurred.

## Human-verified public signing certificates

These are future expected certificates, NOT signers of the unsigned APKs above.
- Daimei Release: `e665aa10b5ad8c1d5c3f993a3e733687ff0ef066da465706a69c7635b0652d77`.
- Daimei Extensions Release: `2782d342e313fe5cb3ca81996f084661a4eb21bbf11e139d8c3913edacf75408`.
PKCS12/RSA4096/SHA256withRSA, validity through2056-11-08, as reported by the human. No agent key-store access.
First GPG AES-256 backup fully restored and byte-compared successfully by the human; only the second independent backup remains pending.

## Future operator procedure, not executed

Follow [official-release-signing.md](official-release-signing.md). Use the same selected JDK for the real unrecorded JavaConsole guard and direct apksigner default protected prompt; zipalign before signing into fresh no-overwrite outputs. Then verify the complete expected signer set, manifest identity, bytes and NEW hashes. Never route official passwords through Gradle or legacy root keystore.properties. No signing operation is authorized by this document.

## Remaining Beta 1 gates

1. App still builds versionName0.1.0/versionCode1, NOT the proposed0.1.0-beta.1/code2. Public activation, official origin/pins, updater selection and a clear safe session-end/revoke interaction remain incomplete. QA package app.daimei.dev.s39qa is not a public distribution candidate.
2. Extension APKs were built from Eion21/daimei commit141033df, not from a newly committed standalone extensions-source export. That export and its conscious Eiones author identity/provenance remain to consolidate; no publication of source or binary is claimed.
3. First encrypted backup decryption/restoration/full byte comparisons PASS are human-confirmed, with original key certreq exits0. Only the second independent encrypted copy remains a custody gate; do not repeat the first-backup/private-key checks.
4. Final human signing and public GitHub publication are NOT authorized. These unsigned APK hashes will change after signing. Official signing must use the protected real-console procedure, expected full signer set and fresh outputs; no passwords in files/env/arguments/logs/chat.
5. Old and Release-focused external verifier attempts remain NOT_RUN (API invalid_request), no retry/replacement or approval. Release committed native ASSESS is available: agent item5high/native medium, reviewDue=false/under_budget, independentVerifier=true, outcome unknown; no START/receipt/RDD mutation. Prior uncommitted assessment unavailability is historical, not the current assessment result.
6. Final official signed APKs/schema2 catalog/live immutable HTTPS/download/install/update proofs, Eion21 credential and CI-safe signing gates remain unresolved. No device operation, push, tag, Release or catalog publication occurred.

Risk: item5 (Release build/distribution preparation). Focused parent controls PASS; no independent/native approval inferred. Signing/publication/install remain outside current authorization.
