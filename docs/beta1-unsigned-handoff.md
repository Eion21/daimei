# Beta1 unsigned artifacts — exact human signing handoff

**Unsigned preparation is verified. Signing and publication are NOT authorized or executed.** Do not regenerate keys, repeat the accepted first-backup restoration, install on a device, or rebuild these inputs merely to sign them.

## Exact selection

- App source: `72ecd6dadb15a80c56de1d51e56fda2101a6cd37` on `chore/beta1-release`; nine focused functional commits after `bee31ff`.
- Public app: `app.daimei`, `0.1.0-beta.1`, code2. QA remains `app.daimei.dev.s39qa`; Debug remains `.dev`, with its existing signer.
- Extension source: `4fe7e9835e5cac98e458290c320177175de451ed` on `chore/beta1-release-sources`, five explicit Eiones local commits,95/95 source/API/build/fixture files byte-identical to the parent. Mechanical imports are larger than400lines because complete unchanged modules/tests/fixtures stay together; no new source behavior was authored.
- Catalog preparation: `ef320e49df3c657877a219a8b7e36eebc27d38c3` on `chore/beta1-official-preparation`; three null-artifact plan entries, not an installable catalog. All commits remain local.
- Build/inspection: offline isolated packaging exit0,197.59s; inspection exit0. Eight outputs are unsigned; five are requested and three are default additional app ABI outputs.29 older APK hashes are unchanged.

## Exact unsigned inputs

Paths below are relative to `../odd/s39/public-release-readiness/` from the app repository root:

- `unsigned-apks-beta1-72ecd6d/app-universal/app-universal-release-unsigned.apk`
- `unsigned-apks-beta1-72ecd6d/app-arm64-v8a/app-arm64-v8a-release-unsigned.apk`
- `unsigned-apks-beta1-72ecd6d/novel-matchanovels-extension-universal/novel-matchanovels-extension-release-unsigned.apk`
- `unsigned-apks-beta1-72ecd6d/novel-skynovels-extension-universal/novel-skynovels-extension-release-unsigned.apk`
- `unsigned-apks-beta1-72ecd6d/novel-jabrascan-extension-universal/novel-jabrascan-extension-release-unsigned.apk`

| Unsigned output | Bytes | SHA-256 | Scope |
|---|---:|---|---|
| Daimei universal | 67923828 | `9a83d88c07eba00f65e76bcfdf6e1208b0c1c45cf37bf991590092791c6cb6ee` | requested |
| Daimei armeabi-v7a | 24025711 | `20b128c1093082290c04f19aff1a18aa4a8e332e8aaea5ce90b83bc23d00e07f` | default extra ABI |
| Daimei x86_64 | 29632816 | `dd12697df6144c5f45512cfa94cb0a3c3849c9840ffc011657a38631d1fc1fc5` | default extra ABI |
| Daimei x86 | 28559895 | `8f640d65eda8d6037afb74223a9b536bc4c8984fc6e9b617c98007e1805c3659` | default extra ABI |
| Daimei arm64-v8a | 27205229 | `a1cfb22551daf632f61ab83ef364bb3b80011588f08fc02eae3266e00acb3558` | requested |
| MatchaNovels | 198564 | `a82a8dcceb1efb10d4168cf42cb25cab9d1db80fc1bd1a267c242dbad8e64a0c` | requested |
| SkyNovels | 19436 | `015b019ec62eaacc71870007bb645b9207fb775511e564e60923c4a4a2009a5f` | requested |
| JabraScan | 8462220 | `0a40de5cad674d4ea2e523e85e0bb1912b122a53b01d4a247547e053c4cabf15` | requested |


Full artifact paths, manifest permissions, declared permissions, ABI lists, API/entry/source-ID evidence, byte sizes, command outcomes and per-APK logs are in `unsigned-apks-beta1-72ecd6d/inventory.json`. The source-ID check combines chosen-source constants and compiled DEX, not a fresh runtime source test. Recheck the public hashes from the evidence directory with `sha256sum -c unsigned-apks-beta1-72ecd6d/SHA256SUMS`. Do not use historical `unsigned-apks-141033df-pass5` as the functional app candidate. The three extension hashes match that snapshot because their sources/configuration did not change; they were rebuilt and inspected from the new chosen commit.

## Future private-terminal signing, only after explicit authorization

1. Finish the human-owned second independent encrypted backup before declaring key custody complete. The first full restoration/byte-equality result is already accepted PASS; do not repeat it as a missing proof.
2. Select one of the five requested inputs and its deliberate profile (`daimei`, `matchanovels`, `skynovels`, `jabrascan`). Check hash, exact package/version/API/ABI from the inventory before signing. No Debug/QA, downgrade, uninstall or data migration is authorized.
3. Follow [the existing protected console template](official-release-signing.md#future-signing-direct-private-terminal-only), using `tools/signing/RequireSigningConsole.java` from the chosen source. Use a real, unrecorded terminal and matching Java console; no agent runner, pipe, `tee`, CI, fake PTY or redirected stdin/output. A failed probe means STOP before apksigner, never a fallback to stdin.
4. Use a new private output directory per APK. Align before signing, keep original unsigned bytes, enter passwords only at hidden console prompts. Never use `--ks-pass`, `--key-pass`, environment/properties/password files or JVM/Gradle arguments. No keystore access was performed for this handoff.
5. Verify actual signatures/alignment and full current signer set. Required public app fingerprint: `e665aa10b5ad8c1d5c3f993a3e733687ff0ef066da465706a69c7635b0652d77`; extensions: `2782d342e313fe5cb3ca81996f084661a4eb21bbf11e139d8c3913edacf75408`. Successful signing or an alias is not identity proof. Do not modify signed bytes afterward.
6. Record real signed SHA-256/size and public metadata separately. Signed hashes necessarily differ; none is invented here. Actual signing, signed positive catalog verification, remotely served byte checks and publication each remain future human-controlled steps.

## Catalog and future publication boundary

Use [official catalog preparation](beta1-official-catalog.md) only on actual signed extension APKs. Hosting is fixed: commit-pinned `raw.githubusercontent.com/Daimei-Extensions/extensions-repo`, strict HTTPS without redirects. Do not use redirecting Release asset URLs or broaden hosts. Keep stable official catalog separate from QA; only build installable schema2entries after real signed artifact/immutable URL/size/hash/signer checks. Run the real host codec. No catalog.json, signed hash or final artifact URL is manufactured here. Publishing needs separate permission and correct repository identity; missing Eion21 credentials do not authorize substituting Eiones. No CI key upload or unproven signing provenance is approved.

## Verification boundary

Parent focused checks:249/249 JVM +39/39 Python, main/test/i18n compilation PASS. New-diff independent verifier:240/240 isolated JVM +39 Python +5 real SDK/unsigned CLI refusals, PASS S2–S6. Baseline8 probes yielded6 PASS/2 semantic RED. The successful format-only SDK fixture is not real signed cryptographic success. Parser staged3tests/CLI staged9tests also passed. Historical490/189 suites and Android16 PASS/0 FAIL/1 NOT_VERIFIED are retained, not rerun. Historical dedicated revoke remains NOT_VERIFIED, and old unavailable verifiers remain NOT_RUN; none is retroactively approved. No new Android experiment or public signed install was run. Public installer on Android26–27 fails closed because complete current-signer facts are unavailable; minSdk26 does not promise activation there. Guards block subsequent retained-source calls after revoke/restart/update, not already-running calls or arbitrary malicious background threads.

All eight ZIPs, unsigned APK signing-block/JAR status, apksigner refusal, manifests/version/SDK/ABI, unchanged permission lines, extension API1.1/1.3, entry classes, source IDs/assets and host/Kotlin/coroutines exclusion passed. All app DEX outputs include public signer pins and the passive official releases URL. Generated experimental flag and automatic updater remain false. API support is canonical1.0–1.3 without a contract bump. Graph refresh was AST-only, exit0 with351 unsupported files/one partial extraction warning, navigation only, not proof.

### Informational native review caveats (summaries, not verbatim claims)

The original reviewer claims were relayed unchanged at closure. No corrective authority was opened; these five advisories do not authorize another code/review cycle:

- Parser `R3-BASE-PATH-NEWLINE` and `R3-COMMIT-NEWLINE`: Python `$`/`.match` can accept trailing newlines. Supply canonical commit/base-path arguments without line endings.
- CLI `R3-001`: a merely nonempty plan repository field could disagree with the pinned URL repository. Keep the checked-in official plan/repository unchanged.
- CLI `R3-002`: a competing creator, dangling output symlink or filesystem without hard links may cause a traceback instead of a clean `CatalogError`. Use a fresh, non-symlink destination on a hard-link-capable filesystem without a competing creator.
- CLI `R3-003`: the intermediate nine-test review slice lacked successful finalization/write coverage. The already planned final39-test slice adds format-only successful preparation and real host codec compatibility, not genuine signed crypto success.

Parser review `review-b057b6d6831b0b48` and CLI review `review-f32f4c0fadd66eaf` were approved and acknowledged (authority consumed), respectively three and four pertinent lenses. This is historical outcome reporting, not reusable authority or delivery permission. Other functional units and the final test/documentation unit assessed under budget/not due; no whole-branch native approval is claimed.


## Stop point

No pending technical investigation is required for this unsigned preparation. Stop here. Future signing requires the human's explicit authorization and private console; future publication requires separate authorization, real signed artifact/catalog/served-byte proof and appropriate credentials/provenance. No push, tags, Releases, device operations, keystore/password reads or certificate changes occurred in this closure.
