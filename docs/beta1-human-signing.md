# Beta1: exact human signing operation

**Prepared, not executed. Stop at your private terminal.** No signed APK or signed hash is claimed. Keep the first-backup restoration PASS; the second independent copy is pending human custody, not a reason to rebuild artifacts or repeat private checks.

## Exact inputs and output slots

Built source: `72ecd6dadb15a80c56de1d51e56fda2101a6cd37`, public `app.daimei`, `0.1.0-beta.1`, code2. Prepared manifest SHA-256: `121c8ece2df1ef0424a7d57b70cb14865d2bf3fbaca9be8ad298efe712c510c2`.

From the Daimei repository root, the workset is `../odd/s39/public-release-readiness/human-signing-beta1-72ecd6d/`. Its five `unsigned/` copies match the final inventory exactly. `UNSIGNED-SHA256SUMS` is input evidence, NOT distribution hashes. Historical artifacts remain unchanged. Expected signed files:

| Profile | Relative path below workset `signed/` | Required identity |
|---|---|---|
| daimei-arm64 | `daimei-arm64/daimei-v0.1.0-beta.1-arm64-v8a.apk` | app.daimei / app key |
| daimei-universal | `daimei-universal/daimei-v0.1.0-beta.1-universal.apk` | app.daimei / app key |
| matchanovels | `matchanovels/matchanovels-v1.0.2.apk` | app.novel.extension.matchanovels / extensions key |
| skynovels | `skynovels/skynovels-v1.0.2.apk` | app.novel.extension.skynovels / extensions key |
| jabrascan | `jabrascan/jabrascan-v1.0.3.apk` | app.novel.extension.jabrascan / extensions key |

## Your real, unrecorded terminal only

Follow the [existing console/security procedure](official-release-signing.md#future-signing-direct-private-terminal-only). Never send passwords to chat. No pipe, `tee`, redirection, CI, fake PTY or logging around these signing commands. Do not put passwords in variables, properties, files or command arguments. TTY checks and the unchanged Java console probe must pass; this agent did not run signing or probe keys.

Open a terminal at the Daimei repository root. Run each command deliberately; stop at any failure:

```sh
WORKSET='../odd/s39/public-release-readiness/human-signing-beta1-72ecd6d'
bash tools/signing/sign_beta1_one.sh "$WORKSET" daimei-arm64
bash tools/signing/sign_beta1_one.sh "$WORKSET" daimei-universal
bash tools/signing/sign_beta1_one.sh "$WORKSET" matchanovels
bash tools/signing/sign_beta1_one.sh "$WORKSET" skynovels
bash tools/signing/sign_beta1_one.sh "$WORKSET" jabrascan
```

`WORKSET` contains only a public artifact path, never a password. Help: `bash tools/signing/sign_beta1_one.sh --help`. Each invocation verifies its pinned unsigned SHA, refuses non-TTY/CI, executes the existing public Java-console probe, creates new `work/PROFILE` and `signed/PROFILE` directories without overwriting, copies the input, aligns with `zipalign -P16` BEFORE signing, invokes SDK apksigner with interactive prompting only, then verifies signatures/API26–36/alignment and prints public certificate/hash/size. It selects the existing app store/alias for both Daimei APKs and the existing extensions store/alias for all three sources. No store existence/stat/private-key check precedes the human signer. Do not modify signed bytes afterward.

On a partial failure, preserve the directories and artifacts. The command refuses a blind retry of an occupied profile directory. Do not delete, overwrite or regenerate anything to make it pass; report only public error/metadata. Completing all five commands is not acceptance of a publication set until the checker below passes with the exact singleton certificates.

## Read-only verification after the five files exist

This command never opens a store, signs, installs, publishes or writes files. It can be run by the agent after you supply the signed APKs:

```sh
python3 -B tools/signing/verify_beta1_signed.py \
  --manifest "$WORKSET/manifest.json" \
  --signed-dir "$WORKSET/signed" \
  --build-tools '/home/jervis/Proyectos/Desarrollo/Android/Sdk/build-tools/36.0.0'
```

Exit0 plus `SIGNED_SET_VERIFIED` is required. Exit1 or missing output is failure/pending, never a partial approval. [Checker details/help/tests](../tools/signing/README.md). It compares real SDK signatures/certificate sets, package/version/min26/target36/ABI/permissions/non-debuggable state,16KiB alignment and complete unsigned-to-signed ZIP payloads. It computes signed hashes/sizes from the actual files, rejects unsigned bytes, extra APKs and symlinks, and leaves inputs unchanged. This is signature/metadata compatibility, not new device runtime proof across every Android version.

Public certificate pins:

- Daimei: `e665aa10b5ad8c1d5c3f993a3e733687ff0ef066da465706a69c7635b0652d77`.
- Extensions: `2782d342e313fe5cb3ca81996f084661a4eb21bbf11e139d8c3913edacf75408`.

Only after real verification, preserve its public JSON and derive distribution SHA256SUMS from its **signed** `sha256`/`filename` rows. Never use `UNSIGNED-SHA256SUMS` for releases. App release sums cover its two APKs; the extension artifact commit/catalog use their three signed hashes/sizes. No final sums or installable catalog exist yet.

## Publication remains a separate authorization

Sequence: extension source → three verified immutable extension APKs → real full commit-pinned RawHTTPS URLs/served-byte checks → stable schema2 official catalog → Daimei source/tag selection → two app APKs and Spanish Beta1 Release. No push, tag, public Release or CI change is authorized. The proposed app tag targets the built source commit, not a guessed later HEAD; if a different source is deliberately chosen, its binaries/provenance must be rebuilt before publication.

Do not advertise official-key APKs as updates for Debug or differently signed installations. No uninstall, migration, Samsung operation, replacement of habitual Daimei, QA mutation, source-ID/package/certificate change is authorized. Public S39 activation on Android26–27 remains fail-closed; Jabra API1.3 is supported by the actual Beta1 host. See the current release-preparation report for GitHub identity/auth status and the explicitly pending signed/hosted/catalog proof.
