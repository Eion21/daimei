# Beta1 signed publication candidate — no publication authorization

The five official human-signed APKs are fixed at source `72ecd6dadb15a80c56de1d51e56fda2101a6cd37`. Existing Android SDK verification returned `SIGNED_SET_VERIFIED`, with exact official certificates and API26–36 signature checks. No rebuild, signing, realignment or CI artifact substitution is permitted. Risk2 and Risk5 remain closed; this preparation does not rerun them. Public-app runtime smoke is NOT_RUN and requires separate explicit device authorization before publication.

## Local commits and immutable paths

- Extension source head: `6968489a430fd4d09ed4d0148b777000e1fd0811`; source-content provenance `4fe7e9835e5cac98e458290c320177175de451ed`.
- Signed extension APK commit: `3522dc91256f8ebf20b87cedf200bcaafffd556d`, containing only three official APKs plus artifact documentation.
- Subsequent schema2 catalog commit: `40ec9e6a8c0754f703670947fca38140b06b9e2c`, containing catalog plus candidate documentation.
- APK base URL: `https://raw.githubusercontent.com/Daimei-Extensions/extensions-repo/3522dc91256f8ebf20b87cedf200bcaafffd556d/distribution/official/s39/beta1/`.
- APK names: `matchanovels-v1.0.2.apk`, `skynovels-v1.0.2.apk`, `jabrascan-v1.0.3.apk`.
- Stable catalog after authorized publication: `https://raw.githubusercontent.com/Daimei-Extensions/extensions-repo/main/official/s39/catalog.json`.
- Pinned catalog: `https://raw.githubusercontent.com/Daimei-Extensions/extensions-repo/40ec9e6a8c0754f703670947fca38140b06b9e2c/official/s39/catalog.json`.

These URLs derive from actual local Git objects, not hypothetical commits. Local Git blob bytes match the signed hashes and sizes. They are NOT a claim of remote availability. The official channel is not `qa-s39-distribution`; no redirects are allowed.

## Identity and CI observations

At preparation, `gh api user` returns Eiones. Eiones is an active administrator of Daimei-Extensions and has push/admin on both organization repositories. It has pull but NO push/admin on Eion21/daimei. Git author identity is not GitHub authentication; SSH identity has not been proved. Do not substitute Eiones for Eion21.

The app workflows are remotely active. `build.yml` builds on main push/selected PRs and uploads unsigned CI artifacts; these are not official release assets. `release.yml` is restricted by upstream `github.repository == 'mihonapp/mihon'` job guards, so its fork jobs skip. `update_website.yml` is NOT fork-gated and reacts to published/deleted/edited Release events, posting to the upstream website with an upstream-token secret if available. A tag push alone does NOT trigger that website workflow. No corresponding workflows exist in either organization checkout. App Actions policy read was HTTP403: do not infer the policy is disabled or that Eiones can change it.

Before future authorized publication, Eion21 must disable the three app workflows for this frozen-artifact release window (repository settings, not workflow edits). This prevents rebuilds, accidentally activated release logic and upstream dispatch. No workflow disable has been executed. Any later workflow re-enable is a separate owner decision, not an automatic step.

## Human-only Eion21 login (no publishing)

In a private terminal, use browser OAuth; never send tokens in chat:

```sh
gh auth login --hostname github.com --git-protocol https --web
gh auth switch --hostname github.com --user Eion21
gh api user --jq .login
gh api repos/Eion21/daimei --jq '.permissions'
```

Stop if browser identity is not Eion21, push permission is absent or secure credential storage is unavailable. Do not fall back to plaintext token files or credentials in URLs. The existing SSH remote is unchanged. A later explicitly approved push may use HTTPS with the GitHub CLI credential helper per command (`git -c credential.helper= -c 'credential.helper=!gh auth git-credential' push https://github.com/Eion21/daimei.git <approved-commit>:refs/heads/main`) without changing global config or relying on unproved SSH identity. This is an example of a future write, NOT permission to execute it now.

## Exact future remote-operation sequence requiring one publication approval

Only after the single isolated official-app smoke PASS and correct-account readiness:

1. As Eion21, disable `build.yml`, `release.yml`, `update_website.yml` in Eion21/daimei. For example, each requires authorized `gh workflow disable <file> --repo Eion21/daimei`. Confirm disabled state before any app main/tag/Release operation; stop if remote protections or policy prevent the approved sequence. No bypass/force push.
2. As Eiones, push exact extension-source head `6968489a430fd4d09ed4d0148b777000e1fd0811` to extensions-source main, fast-forward only.
3. As Eiones, push APK commit `3522dc91256f8ebf20b87cedf200bcaafffd556d` to extensions-repo main, fast-forward only. Do NOT activate catalog yet.
4. GET each of the three full-commit APK URLs directly with strict HTTPS/no redirects. Require HTTP200, complete binary bytes, exact signed SHA256/size, not an LFS pointer or HTML. Reuse the trusted SDK checks on downloaded bytes if needed; do not sign/rebuild. Any mismatch stops before catalog activation.
5. As Eiones, advance extensions-repo main to catalog commit `40ec9e6a8c0754f703670947fca38140b06b9e2c`. GET the stable and pinned catalog without redirects and compare exact catalog SHA256 `3667ec129f74649435ffaa99fdc87ca5f12fb697fd685387c34c7bf6a6856d64`, size2366, schema2 and three pinned artifact facts. This is the official catalog activation.
6. As Eion21, push the exact prepared app documentation/source head recorded in the final handoff to main, fast-forward only. No source changes or builds at publication.
7. Create/push annotated unsigned tag `v0.1.0-beta.1` pointing at actual APK source `72ecd6dadb15a80c56de1d51e56fda2101a6cd37`, not at later documentation HEAD. Abort if tag exists unexpectedly; no overwrite. Tag creation is NOT performed by preparation.
8. Create Daimei Beta1 GitHub prerelease with the two frozen official app APKs, their signed SHA256SUMS and Spanish notes. Upload only those exact prepared assets; preserve source attribution/licenses. Verify uploaded/downloaded asset hashes before calling the Release ready. No Actions rebuild, Debug/QA/unsigned APK or .idsig substitution.

Remote observations (three public repos/default main, zero tags/Releases) must still match at execution. Correct identity and branch movement are checked as part of this bounded publication transaction, not as a new general audit. On unexpected movement, conflicts or denied permissions stop; never invent hashes, retry signing or force history.

## Remaining proof and custody

Actual official app smoke has not run; the experimental QA cycle does not substitute for it. See the final handoff's one-smoke proposal. Before any install obtain explicit target device/profile permission and prove no existing global installation of `app.daimei` could be updated in another user. Prefer a clean secondary physical device; no habitual user/data, replacement, uninstall or clear-data.

Two official keys are human-verified; first encrypted backup restoration and byte comparison passed. Second independent physical copy remains human-owned custody risk. Do not open/retest/copy private keys or ask for passwords. This warning is not an extra local artifact-preparation task.
