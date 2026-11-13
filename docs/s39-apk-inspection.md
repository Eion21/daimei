# S39 inert APK observation

`NovelApkInspector(privateRoot, deviceSdk, metadataPort).inspect(artifact, expected)`
accepts a quarantine artifact and an entry returned by `NovelDistributionCatalogCodec`.
The Context constructor, `NovelApkInspector(context, privateRoot, deviceSdk)`, uses
`PackageManager.getPackageArchiveInfo` with metadata, configuration and signing flags.
Neither constructor loads classes, creates sources, installs an APK or writes trust.

The result is `NovelInspectionResult.Observed(metadata, layout, signatureVerification)` or
`Rejected(reason)`. An accepted `layout` is `NovelStructuralLayout.Base(packageName)`.
The verification status is always **PENDING**. Metadata and
archive signer arrays are untrusted declarations, not publisher authentication or
Stage1 evidence. Re-hashing immediately before and after metadata inspection detects
persistent changes; it does not remove all filesystem races. A later consumer must
recheck bytes when using them. The bounded binary-XML reader checks the actual manifest
and rejects malformed, split, contradictory, and unknown layouts. It accepts UTF-8
and UTF-16 string pools and only an explicit allowlist of framework resource-ID/name
pairs; unsupported or ambiguous mappings and chunks remain unknown. A regression
fixture embeds the exact compiled `novel-fixtures-extension` manifest (SHA-256
`637a7938fcbe176958860b9a7a6f83fe3d1d0e5d6b0dda525636f1e7d889190b`). Tests
also use synthetic binary XML and a metadata port; they do not demonstrate Android
manifest parsing or signature validity. Other legitimate APKs can still be rejected.

The reader `NovelInstalledPackageReader(context, sdk).read(packageName)` observes the
exact installed package, long version, split state and signers, or reports private,
absent or unavailable. It checks `filesDir/exts/<package>.ext` without loading it.
On API 30+ a not-found result is absence only with granted `QUERY_ALL_PACKAGES`;
otherwise it is unavailable. The injected lookup constructor permits isolated JVM
fixtures. API 26–27 signer observations remain `LEGACY_UNVERIFIED`; modern current
signers and history are separate. Observations are not source-ID attestations.

Android PackageManager and real APKs have not been exercised. The structural parser
is intentionally fail-closed for unsupported representations, and is not proof of
Android parser equivalence. Production signature verification and the installer commit
remain blocked independently.
