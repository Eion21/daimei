# Beta 1 official novel catalog

Status: **PREPARATION ONLY**. This file describes the planned official catalog
for the three Beta 1 novel extensions. It is intentionally noninstallable
until the APKs are signed and the catalog is finalized with real artifact hashes
and immutable Raw HTTPS URLs.

## Why the plan is separate from the installable catalog

`distribution/official/s39/catalog-plan.json` is a schemaVersion 2 catalog
structure where every entry has `"artifact": null`. This keeps the
noninstallable plan in source control while preventing any consumer from
treating it as an installable catalog. Only after three real signed APKs are
inspected with `aapt2` and `apksigner` may the plan be finalized into a catalog
with actual `url`, `size`, and `sha256` values.

## Supported sources

| Package | API | Source ID | Entry class |
|---|---|---|---|
| `app.novel.extension.matchanovels` | `1.1` | `matchanovels` | `app.novel.extension.matchanovels.MatchaNovelsSource` |
| `app.novel.extension.skynovels` | `1.1` | `skynovels` | `app.novel.extension.skynovels.SkyNovelsSource` |
| `app.novel.extension.jabrascan` | `1.3` | `jabrascan` | `app.novel.extension.jabrascan.JabraScanSource` |

JabraScan declares novel-api `1.3` because it uses `ContextAwareNovelSource`.
Daimei Beta 1 supports `1.0`, `1.1`, `1.2`, and `1.3`; the catalog, install
policy, and APK inspector all reject unknown or future versions.

## Expected signer

All three extensions are signed with the same official extensions certificate:

```
2782d342e313fe5cb3ca81996f084661a4eb21bbf11e139d8c3913edacf75408
```

This is public metadata, not a secret. The finalizer pins this certificate: an
edited plan cannot substitute a different one, and any APK whose signer set does
not equal exactly this digest is rejected before the catalog is written.

## Why Release assets are rejected

GitHub Release asset URLs (for example, anything under
`https://github.com/Daimei-Extensions/extensions-repo/releases/download/...`)
return short-lived redirects (`3xx`). The Daimei downloader is configured to
reject redirects for extension artifacts, so Release asset links are not usable
as catalog URLs. The official catalog must use immutable Raw HTTPS paths.

## Immutable Raw HTTPS route

After the signed APKs are committed to the distribution repository, the catalog
points at raw.githubusercontent.com using the **full commit hash**. For a commit
`P` and file `FILENAME`:

```
https://raw.githubusercontent.com/Daimei-Extensions/extensions-repo/P/distribution/official/s39/FILENAME
```

This route is stable as long as the commit exists, and it does not redirect. The
finalizer never invents a URL or a commit: abbreviated or fake commits such as
`abc123...` are rejected, and there is no default URL. The `--commit` argument
must be a full 40-hex (or 64-hex, for SHA-256 repositories) lowercase Git commit
hash, and `--base-path` must be a canonical repository-relative path with no
`..`, URI components, backslashes, or encoded traversal. All argument validation
happens before any SDK tool runs and before any output side effect.

## Tool usage

`tools/novel-distribution/prepare-official-catalog.py` finalizes the plan:

```sh
python3 tools/novel-distribution/prepare-official-catalog.py \
    --plan distribution/official/s39/catalog-plan.json \
    --commit <full 40-hex commit of the distribution repository> \
    --apk app.novel.extension.matchanovels=<signed matchanovels APK> \
    --apk app.novel.extension.skynovels=<signed skynovels APK> \
    --apk app.novel.extension.jabrascan=<signed jabrascan APK> \
    --out <new catalog path> \
    --aapt2 <SDK aapt2> --apksigner <SDK apksigner>
```

Properties enforced by the tool:

- **Pinned official facts.** The plan must contain exactly the three official
  packages with their pinned source IDs, novel APIs, entry classes, versions,
  and the official extensions certificate. An edited plan cannot substitute a
  package, source ID, API, entry class, or certificate.
- **Validation before side effects.** Commit, base path, APK mappings (exactly
  three, distinct official packages, distinct clean `.apk` basenames), and the
  plan are all validated before `aapt2`/`apksigner` run or anything is written.
- **Real evidence only.** Manifest metadata comes from `aapt2`, signer
  certificates from `apksigner verify --print-certs`; the SHA-256 and size come
  from the actual APK bytes. Unsigned APKs, wrong packages, unsupported APIs,
  and version mismatches are rejected with exit 1 and no output.
- **No overwrite, atomic output.** An existing catalog is never overwritten
  (the tool refuses with an error), and output is written via a temporary file
  and an atomic link, so no partial or empty catalog is ever left behind.
- **Codec-compatible output.** Optional plan fields that are null (such as
  `abis`) are omitted from the catalog — the real Kotlin codec rejects an
  explicit `"abis": null` — and `sourceIds`, `entryClasses`, and `novelApi` are
  preserved for every entry.

On any rejection the tool prints `error: <reason>` to stderr, prints nothing to
stdout, exits 1, and leaves no output file. On success it prints nothing and
exits 0.

## Testing status

- Refusal paths, argument validation, plan pinning, output safety, and the
  positive end-to-end output test are covered by
  `tools/novel-distribution/test_prepare_official_catalog.py` (39 tests). The
  real public pass5 unsigned APK is used with the real `apksigner` for an
  honest cryptographic refusal; the positive end-to-end test uses fake
  aapt2/apksigner scripts that embed representative lines captured from the
  public pass5 SDK dumps. **Those fakes verify format only: no signature is
  verified, no signing key exists or is generated, and no cryptographic success
  is claimed.**
- The exact field layout the tool emits is pinned on the Kotlin side:
  `NovelDistributionCatalogTest` decodes the three-entry official structure
  through the real `NovelDistributionCatalogCodec` (including a test that the
  legacy `"abis": null` spread is rejected).
- The **real signed happy path is pending**: until the human signs the three
  APKs from a private terminal and supplies them with the real full commit,
  no installable official catalog exists.

## Source/binary provenance

The unsigned Release APKs used during preparation were built from commit
`141033df` and recorded in
`../odd/s39/public-release-readiness/unsigned-apks-141033df-pass5`. The final
signed APKs must be built from the chosen commit, signed with the official
extensions key, and then processed by the finalize tool. No invented hash, URL,
or test key is ever written.

## Operator constraints and non-blocking review follow-ups

Use the checked-in plan with its official repository value and canonical arguments without trailing newlines. The current validator can accept a trailing newline in commit/base-path arguments, and it does not pin the plan repository field (artifact URLs themselves remain pinned). These were informational native-review findings, not approval blockers or a reason to reopen either reviewed slice.

Write to a new path on a filesystem supporting hard links. A racing output creator, dangling output symlink or a hardlink-less filesystem may produce a traceback instead of the ordinary error contract; no successful signed-catalog run is claimed yet. Track these boundary refinements separately. The final39-test suite includes a format-only successful write that was absent from the intermediate nine-test review slice; it does not establish real signed cryptographic success.
