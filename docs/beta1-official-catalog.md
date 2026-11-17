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
