# Beta 1 public installer (T14)

## Scope

Implements the public S39 installer activation (S3) and update/revocation controls (S4)
for Daimei Beta 1. It reuses the existing `NovelExecutionHoldStore`, coordinator and
`ExtensionManager` retraction; it does not change the loader signature/trust gate order.

## Activation

`NovelInstallActivation` is a real, explicit public activation. It is active only when the
running host package is `app.daimei` **and** its complete current signing identity is
exactly the pinned official host certificate:

```
e665aa10b5ad8c1d5c3f993a3e733687ff0ef066da465706a69c7635b0652d77
```

The gate requires modern complete signer facts whose current set equals that singleton —
not merely contains it. A multi-signer host that includes the pin, a host whose pin
survives only in its rotation history, and devices that expose no modern complete facts
(Android 26/27 legacy signatures, or an unavailable read) all fail closed; there is no
forged-complete fallback. Unsigned builds cannot claim that signer, so the controls fail
closed.

`NovelAndroidInstallBridge` now supports both the QA fixture policy and the public
activation. `NovelInteractiveInstallRuntime` uses the combined `forHost()` factory so the
right policy is selected automatically.

## Allowed packages

When public activation is active, installation is permitted only for the three official
extension packages signed with the pinned extensions certificate:

```
2782d342e313fe5cb3ca81996f084661a4eb21bbf11e139d8c3913edacf75408
```

- `app.novel.extension.matchanovels`
- `app.novel.extension.skynovels`
- `app.novel.extension.jabrascan`

The bridge delegates exact artifact and catalog validation to the existing coordinator,
inspector and hold-store flow.

## Updates

A catalog entry whose package is already installed with a lower versionCode is shown as an
update. An update proceeds only when the installed facts are for the exact requested
package, shared (not private), not split, observed with a complete current signer set
identical to the requested one, and at a strictly lower version; unknown, private, split,
mismatched or same/higher-version facts reject before any session or journal mutation.
Updates require a fresh execution lease, the old lease is not reused, and the hold store
refuses a higher-version enroll that carries a different signer set, leaving the managed
record and lease unchanged.

## Public identity and application updates

Beta 1 keeps the established public package `app.daimei` and uses version `0.1.0-beta.1` / code `2` (previous local code `1`). The anonymous public GitHub release/tag preflight found neither published releases nor tags; this is not a claim about unknown sideloads. Debug (`.dev`), isolated QA (`.dev.s39qa`), foss, nightly and benchmark suffixes and signing remain unchanged. Never replace a Debug installation with an official signature.

About now always exposes **Official Daimei releases** (`https://github.com/Eion21/daimei/releases`). This opens a browser only: it neither enables the automatic application updater nor mixes it with extension installation. The page may have no releases until separately authorized publication. The automatic updater remains disabled. The existing malformed/empty update URL suppression is unchanged. Base and Spanish labels are included.
