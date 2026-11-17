# Beta1 publication sequence — prepared, not authorized

No push, remote tag, Release, official APK upload, catalog.json or CI signing is executed. Signed APKs must first come from the [human operation](beta1-human-signing.md) and pass the [read-only checker](../tools/signing/README.md). Do not publish the unsigned input hashes.

## Observed identities and tag selection

Recovered app HEAD was `43fcd05696e66aa3def662d4df5946d8e811fc6a`. The built source stays `72ecd6dadb15a80c56de1d51e56fda2101a6cd37`; later preparation commits add local tools/docs only. Proposed Release/tag: `v0.1.0-beta.1`, targeting that exact built source. Fresh anonymous GitHub observations returned zero app tags and zero Releases (HTTP200); this proves no observed conflict at that time, not a reservation or permission to create it. Recheck for a conflict immediately before separately authorized publication.

All three expected public repositories were observed. The app remote is `git@github.com:Eion21/daimei.git`; organization source/catalog remotes remain their existing URLs. Active/configured gh account is **Eiones**, not Eion21. No account was switched and no SSH push identity/permission was established. Auth is a future app-publication gate, not a blocker for preparing local signing inputs.

## Human authorization of Eion21, only in your own terminal

If Eion21 is not configured, authenticate in a browser as Eion21, never send tokens to chat:

```sh
gh auth login --hostname github.com --git-protocol https --web
gh auth switch --hostname github.com --user Eion21
gh api user --jq .login
```

The last line must say `Eion21`; otherwise stop. If gh warns that secure credential storage is unavailable, stop and arrange secure human-owned credential storage rather than accepting a plaintext fallback. Do not use token files, plaintext credential storage or token-bearing remote URLs. Browser OAuth does not by itself prove the existing SSH remote's push identity: before a later authorized push, deliberately validate the correct SSH identity or separately authorize an HTTPS remote/credential setup. Do not generate/upload SSH keys or modify remotes as a preparation shortcut. No login/switch/setup command above was run by the agent.

## Ordered future publication, separate explicit permission required

1. **Extension source:** publish the deliberate `Daimei-Extensions/extensions-source` branch/snapshot with95-file parent-source parity and its provenance/licenses. Existing source content commit is `4fe7e9835e5cac98e458290c320177175de451ed`; later README-only preparation may be published with it. Do not include caches, APKs, stores, backups or credentials.
2. **Immutable official extension APKs:** after real five-APK verification, select only the three signed extension files and copy them to `Daimei-Extensions/extensions-repo` at the fixed paths below. Commit those actual bytes locally, record its real full commit, and only after upload authorization push it. No future commit SHA is invented here.
3. **Real RawHTTPS URLs:** use that actual complete artifact commit in raw.githubusercontent.com URLs for those paths; require no redirects and exact remotely served signed SHA-256/size/certificate/package facts. No Git LFS pointer, guessed hash or Release-asset redirect is acceptable.
4. **Stable official S39 catalog:** only after step3, use the existing `prepare-official-catalog.py` with the checked-in official plan, actual signed APK mappings, actual artifact commit and base path `distribution/official/s39/beta1`. Choose a fresh output `official/s39/catalog.json`, run the real schema2 host decoder, then commit/publish the stable catalog in a subsequent commit. Keep the plan repository fixed, canonical arguments with no newlines and a fresh non-symlink output on a hard-link-capable filesystem; approved informational finalizer caveats remain documented. Its entries use the already immutable APK commit, not the later catalog commit.
5. **Daimei source and Release:** publish the definite source/provenance/docs under Eion21, deliberately create the nonconflicting source tag only when authorized, then attach the two verified signed app APKs, actual signed-only SHA256SUMS and [Spanish notes](releases/v0.1.0-beta.1.es.md) to its Release. No QA/Debug/experimental APKs and no claim that other-signer installations update in place. Signing inputs are not rebuilt from a newer preparation HEAD silently.

| Source | Fixed repository-relative future APK path |
|---|---|
| Matcha | `distribution/official/s39/beta1/matchanovels-v1.0.2.apk` |
| Sky | `distribution/official/s39/beta1/skynovels-v1.0.2.apk` |
| Jabra | `distribution/official/s39/beta1/jabrascan-v1.0.3.apk` |

Planned stable catalog URL: `https://raw.githubusercontent.com/Daimei-Extensions/extensions-repo/main/official/s39/catalog.json`. It is NOT live/verified or installable during preparation. Actual APK URLs, signed hashes/sizes and artifact commit remain null until known. Preserve all S39 QA branches/channels, S38 fixtures and their existing bytes/ref history.

## User installation and limits

When the official Release/catalog actually exist, use the **Extensions** tab overflow → **Novel distribution (S39)** → HTTPS schema2 catalog URL → **Add and query repository**, then review origin consent. This route persists the repository. The S38 read-only preview does not subscribe or enable remote installation; do not point manga-index instructions at the S39 schema2 JSON. Install confirmation and explicit session execution authorization are separate steps. Android minimum26/target36; public S39 activation fails closed on26–27; host API1.0–1.3 includes Jabra1.3.

The first encrypted backup restoration is already PASS. The second independent physical copy is human-owned custody pending. No private backup/key inspection, new Risk2 verifier/RDD/general suite, Samsung install or habitual/QA app operation belongs to this preparation. Real signed verification and hosted/catalog success remain pending, not inferred from mock SDK unit tests. Existing independent runtime-security proof remains closed and untouched.
