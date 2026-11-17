# Beta1 human signing and read-only verification

These tools do not publish or install APKs. App security code and its accepted independent verification are unchanged. Never run signing through an agent, CI, fake PTY, pipe or a recorded terminal. Never provide passwords in chat, arguments, environment variables or files.

## Read-only signed APK checks

```sh
python3 -B tools/signing/verify_beta1_signed.py \
  --manifest '../odd/s39/public-release-readiness/human-signing-beta1-72ecd6d/manifest.json' \
  --signed-dir '../odd/s39/public-release-readiness/human-signing-beta1-72ecd6d/signed' \
  --build-tools '/home/jervis/Proyectos/Desarrollo/Android/Sdk/build-tools/36.0.0'
```

Help: `python3 -B tools/signing/verify_beta1_signed.py --help`.
Exit0 prints one complete public JSON report; exit1 prints `error: ...` to stderr and no success JSON; argparse misuse exits2. The tool never writes files or opens signing stores. Use trusted SDK binaries; synthetic SDK outputs in tests establish format handling only, not signature validity.

The manifest pins exactly five profiles, source `72ecd6dadb15a80c56de1d51e56fda2101a6cd37`, public package/version/API/ABI identities and unsigned input hashes. ZIP payload equality binds all DEX/resources/manifests/permissions/source IDs to those inputs despite alignment/signature changes. Only v1 signature metadata entries are excluded from that comparison. SDK verification requires the exact singleton app/extensions certificate, signature validity over API26–36, minSdk26/targetSdk36, matching permissions/ABI, non-debuggable metadata and16KiB alignment. Final hash/size are calculated from the actual signed file; unsigned hashes are never distribution hashes. Inputs changing during verification, symlinks, missing or extra APKs are refused. Preserve files unchanged after verification.

Signature-range checks are not a new runtime test on every Android version. Public S39 activation on Android26–27 still fails closed; host API1.0–1.3 supports Jabra1.3. Source-ID/package continuity and session behavior retain their existing proof.

## Scope of preparation checks

`python3 -B tools/signing/test_verify_beta1_signed.py` exercises only this new CLI with synthetic APK ZIPs/fake SDK formatting. It does not generate keys or sign anything. The real signed positive remains PENDING until the human provides the five APKs. No new `gentle-ai-verify`, RDD or general suite run is part of this explicitly excluded candidate.

For the existing protected console procedure, profiles, public pins and key custody boundary see [official-release-signing.md](../../docs/official-release-signing.md). No first-backup test is repeated; the second independent copy remains human-owned pending.
