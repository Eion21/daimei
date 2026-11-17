# Official release packaging and human-operated signing

**This procedure is prepared, not executed. Do not sign or publish APKs during the current preparation phase.** Release APKs are deliberately unsigned in Gradle; Debug and S39 QA retain their existing signing. No keystore or password is configured for Release inside Gradle.

## Why signing is separate

A Gradle daemon is not a demonstrated private human console. Do not supply official passwords through Gradle properties, environment variables, JVM/process arguments, files or persistent signing configuration. Do not use the legacy root `keystore.properties` or `MIHON_GITHUB_RELEASE` route: it changes Debug signing. Release has no fallback to Debug. Keep the two existing official keys; never generate a key per build or in CI.

The human reports both stores passed `keytool -list` and `keytool -certreq` with exit0. The first GPG AES-256 backup on DATOS passed decryption, full restoration and byte comparisons against both original PKCS12 files (exit0). These are human-confirmed facts, not agent keystore reads; those checks are not repeated. Only the second independent backup remains pending for custody. Public certificate facts supplied and verified by the human: PKCS12, RSA4096, SHA256withRSA, valid until 2056-11-08. Expected app certificate SHA-256: `e665aa10b5ad8c1d5c3f993a3e733687ff0ef066da465706a69c7635b0652d77`; expected extensions certificate SHA-256: `2782d342e313fe5cb3ca81996f084661a4eb21bbf11e139d8c3913edacf75408`. These are certificate fingerprints, NOT hashes of unsigned APKs or agent keystore inspection.

## Package without credentials

Only after a future chosen commit contains this configuration, use a **fresh isolated checkout/worktree** at that commit. Do not rebuild over previously verified experimental APKs. Record the commit and resolve the generated names using each module's `build/outputs/apk/release/output-metadata.json`; do not guess file names or reuse an older signed output.

From that isolated checkout, the future packaging commands are:

```sh
./gradlew :app:assembleRelease
./gradlew :novel-matchanovels-extension:assembleRelease \
    :novel-skynovels-extension:assembleRelease \
    :novel-jabrascan-extension:assembleRelease
```

The standalone extensions-source checkout uses the second command after its selected commit includes the mirrored configuration. No credentials are required for these unsigned Release packages. Packaging is NOT a signed release, an installation or permission to publish. No such packaging command was run as part of preparation.

## Select exactly one identity

| APK | Required package | Private store | Alias |
|---|---|---|---|
| Daimei | `app.daimei` | `/home/jervis/.local/share/daimei-signing/daimei-app-release.p12` | `daimei-app-release` |
| MatchaNovels | `app.novel.extension.matchanovels` | `/home/jervis/.local/share/daimei-signing/daimei-extensions-release.p12` | `daimei-extensions-release` |
| SkyNovels | `app.novel.extension.skynovels` | same extensions store | `daimei-extensions-release` |
| JabraScan | `app.novel.extension.jabrascan` | same extensions store | `daimei-extensions-release` |

Keep package names, source IDs and versions as recorded in the selected source. Never sign QA/Debug as public Daimei. Inspect the unsigned input's manifest with the SDK's `aapt dump badging` and compare package/version/minSdk/targetSdk/ABI with the planned artifact BEFORE signing. Stop on an identity mismatch. Record the unsigned input SHA-256 and selected source commit.

## Future signing: direct private terminal only

Run this only when actual artifact signing is authorized, from the human's **real, unrecorded terminal**. No pipe, `tee`, redirection, CI, IDE task panel, command substitution around apksigner, or agent runner. Never type a password into chat. Do not use `--ks-pass`, `--key-pass` or any `pass:`, `env:`, `file:` transport. Default apksigner prompting uses Java `Console.readPassword` only if a real console exists; otherwise it can fall back to plain stdin. That fallback is NOT acceptable.

The following is the exact future template for ONE APK. Replace the three public artifact-path placeholders and `PROFILE` deliberately. No password placeholder exists. `CONSOLE_PROBE` is the public `RequireSigningConsole.java` versioned under `tools/signing/`, not a private file.

```sh
(
    set -eu
    set +x
    umask 077
    test -t 0 && test -t 1 && test -t 2
    tools=/home/jervis/Proyectos/Desarrollo/Android/Sdk/build-tools/36.0.0
    CONSOLE_PROBE='/absolute/path/to/chosen/checkout/tools/signing/RequireSigningConsole.java'
    INPUT='/absolute/path/to/chosen/unsigned-release.apk'
    OUTPUT_DIRECTORY='/absolute/path/to/a/NEW/private/signing-output-directory'
    PROFILE='daimei' # daimei, matchanovels, skynovels, or jabrascan
    case "$PROFILE" in
        daimei)
            STORE=/home/jervis/.local/share/daimei-signing/daimei-app-release.p12
            ALIAS=daimei-app-release
            ;;
        matchanovels|skynovels|jabrascan)
            STORE=/home/jervis/.local/share/daimei-signing/daimei-extensions-release.p12
            ALIAS=daimei-extensions-release
            ;;
        *) printf '%s\n' 'STOP: unknown signing profile.' >&2; exit 2 ;;
    esac
    if test -n "${JAVA_HOME:-}"; then
        SIGNING_JAVA="$JAVA_HOME/bin/java"
    else
        SIGNING_JAVA=java
    fi
    "$SIGNING_JAVA" "$CONSOLE_PROBE"
    test -f "$INPUT"
    # Must fail if any prior directory/file/symlink occupies this output path.
    mkdir -m 0700 -- "$OUTPUT_DIRECTORY"
    "$tools/zipalign" -P 16 4 "$INPUT" "$OUTPUT_DIRECTORY/aligned-unsigned.apk"
    "$tools/zipalign" -c -P 16 -v 4 "$OUTPUT_DIRECTORY/aligned-unsigned.apk"
    "$tools/apksigner" sign \
        --ks "$STORE" --ks-type PKCS12 --ks-key-alias "$ALIAS" \
        --out "$OUTPUT_DIRECTORY/signed.apk" \
        "$OUTPUT_DIRECTORY/aligned-unsigned.apk"
    "$tools/apksigner" verify --verbose --print-certs "$OUTPUT_DIRECTORY/signed.apk"
    "$tools/zipalign" -c -P 16 -v 4 "$OUTPUT_DIRECTORY/signed.apk"
    sha256sum -- "$OUTPUT_DIRECTORY/signed.apk"
    stat -c '%s' -- "$OUTPUT_DIRECTORY/signed.apk"
)
```

Only store paths, aliases, artifact paths and nonsecret profile/Java configuration are variables. Passwords are acquired solely by apksigner's hidden console prompts; for these PKCS12 keys it first tries the store password for the private-key entry. The Console probe never asks for or receives a password and exits2 without a real Java console. The chosen Java must match the SDK launcher's JAVA_HOME/default-java selection. If the console probe fails, if an unexpected password transport appears, or if the terminal is recorded, stop BEFORE starting apksigner. Do not repair that condition with a fake PTY or stdin pipe.

The input must already have been checked against the selected profile's exact package. The template does not claim to parse/validate package metadata automatically; that is a mandatory preceding human check. No `-f` alignment/overwrite option is used; outputs live in a newly created directory and never replace the unsigned input or an older verified APK. Never alter or zipalign an APK after signing.

## Acceptance after the future signing operation

- Every signing/alignment/verification command exits0. A password failure is a failure, not a reason to log/reuse/transport credentials differently.
- Compare the **complete actual certificate set** reported by apksigner with the already verified public fingerprint for the correct app/extensions key. Do not infer authenticity from an alias or a successful signature alone. Stop if the expected public fingerprint is not recorded or differs.
- Recheck package/version/SDK/ABI and exact source-to-artifact identity. Preserve source IDs, API compatibility and required extension packaging exclusions.
- Record only commit, tool version, manifest metadata, public certificate fingerprints/validity, APK hash/size and command outcomes. Never copy stores or passwords to reports/Git/CI.
- Repeat separately for each intended ABI/app APK and extension, with a fresh output directory each time. There is no automatic signing loop, release upload or device install.
- Finish and verify the second independent encrypted backup before claiming custody complete. The first backup's decryption/restoration/byte-equality checks are already human-confirmed PASS; do not repeat them as an unresolved first-backup gate. Existing development-signed installations are NOT automatically update-compatible with a new official signer; no uninstall/migration is authorized here.

## References and verification boundary

- [Android apksigner](https://developer.android.com/tools/apksigner): optional password transport/default prompt; verification and signature invalidation after modification.
- [Android zipalign](https://developer.android.com/tools/zipalign): alignment BEFORE signing, `-P 16`, check-only `-c`.
- [AOSP PasswordRetriever](https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/src/apksigner/java/com/android/apksigner/PasswordRetriever.java): `System.console()` / `readPassword` versus stdin fallback.
- [AOSP SignerParams](https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/src/apksigner/java/com/android/apksigner/SignerParams.java): default prompt and key-password fallback.

Documentation confirms the tool's console design, not that the current agent channel or an arbitrary future terminal is safe. The previous independent S39 verifier's `invalid_request` remains **NOT_RUN**, neither approval nor an established code defect. No earlier audit is repeated by this release-only configuration work.
