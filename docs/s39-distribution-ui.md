# S39 distribution UI — controlled integration

## Where to find it

Explore → Extensions → overflow → **Novel distribution (S39)**. The existing S38 read-only catalog remains a separate unchanged route; manga stores are not enrolled as novel repositories.

## Explicit decisions

1. Add/query an HTTPS schema-2 repository. Its URL is saved only after explicit consent. No saved URL is fetched automatically on restart. Metadata uses a separate bounded, anonymous, public-address-only HTTPS client with no redirects, cookies, credentials or proxy.
2. Select an entry and approve its exact artifact origin. The existing downloader performs bounded HTTPS download, SHA-256 and byte-count checks in owned private quarantine. Cancel closes the active transfer; download consent does not submit a session.
3. Request installation separately. Only the exact `app.novel.extension.fixtures` APK SHA-256 and full signer set compiled into an active `s39Qa` host are eligible. Ordinary builds, absent pins and the three real extension packages cannot reach this experimental action. The coordinator re-inspects the archive, verifies expectations, persists DENY and correlates the owned PackageInstaller session and streamed bytes.
4. If necessary, open installation permission settings deliberately, then explicitly recheck. No permission is granted programmatically. API31+ requires Android user action.
5. Refresh correlated status and explicitly open the normal Android confirmation only when its correlated pending callback is present. Receivers and refresh never launch it automatically. A recorded SUCCESS and matching fresh installed facts are required; visibility alone is not installation proof.
6. Review execution separately for the journal-bound operation/package/version/full signer set. The coordinator grants only a process-local scoped lease. Existing package/version/fingerprint trust is reused, followed by an explicit guarded manager reload even if the persisted preference did not change. Loader gates and full-signer DENY holds remain authoritative. Recognition means an actual loaded NovelSourceAdapter, not a successful install or grant.

## Persistence and restart

Repository URLs and the last opaque operation ID may be saved. No execution grant is serialized. The runtime reads expected journal metadata and revalidates actual installed facts; restoring an operation pointer never grants trust. The recognition snapshot only observes existing manager results and never loads code itself.

The trusted framework files directory is canonicalized before appending the fixed quarantine/journal leaf. An untrusted quarantine leaf is never canonicalized through a symlink: existing downloader/inspector NOFOLLOW and ownership checks must reject it.

## Current limits and evidence

- First integrated `:app:compileS39QaKotlin` passed. Focused Gradle release JVM tests passed43/43 (backend28, metadata/enrollment/execution14, private-root alias1), with no failures, errors or skips. Base/es XML and33 S39 keys match.
- These are compilation and JVM simulation results, not Android UI or installation proof. Raw commands/results and copied JUnit XML are under `../odd/s39/distribution-readiness/`.
- Compose wiring has no meaningful headless behavioral RED without an Android UI runtime; its controller rules were tested first, and compilation/structural checks are required. Native UI/confirmation/recognition must be observed in the separately authorized disposable-package gate.
- Public installation remains disabled. No Samsung operation is authorized by these code changes.
- Updates remain ineligible until the native NEW gate and actual source-ID continuity for all three real extensions. This screen does not pretend the NEW-only coordinator supports UPDATE.
- Selected public GitHub repositories currently expose no release assets. No default asset URL or redirect destination is invented. Artifact redirect rejection is unchanged.
- Package/version/signature observations and archive signature declarations are not preinstallation cryptographic evidence issuers.
- This is not a public release candidate or a claim of end-to-end Android success. Stable signing, real APK/catalog preparation and the remaining feature tasks still apply.
