# S37 — functional closure and local integration

## Status

S37 is functionally closed on the owner's approval. Local Git commits are
explicitly authorized; publication and release are not. This record describes
validated behavior and evidence, not an agent-run Android installation. The
historical protocol is in `docs/s37-manual-validation.md`.

| Owner-reported Android surface | Result |
| --- | --- |
| Popular | PASS |
| Recent | PASS |
| Search | PASS |
| Details | PASS |
| Library | PASS |
| PDF | PASS |
| Progress | PASS |

The owner also accepted the earlier extension separation, ascending reading
sequence, VIP restriction, PDF routing and PDFBox validations. These are owner
reports, not a new device run by the integration agent.

## Defects and integrated source areas

- Discovery filters manga first; a manga catalog is rejected as a novel catalog.
  Existing repository configuration is not deleted. See `app/src/main/java/eu/kanade/tachiyomi/extension/`.
- About removes empty destinations while preserving Mihon attribution and licenses:
  `app/src/main/java/eu/kanade/presentation/more/settings/screen/about/AboutScreen.kt`.
- SOURCE novels read ascending through shared Start, Next and Resume ordering,
  including filters, without changing saved progress. See
  `app/src/main/java/eu/kanade/tachiyomi/util/chapter/NovelChapterReadingOrder.kt`,
  `app/src/main/java/eu/kanade/tachiyomi/ui/reader/novel/` and
  `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaViewModel.kt`.
- Matcha's exact membership-card guard rejects restricted text, not ordinary VIP
  mentions; it does not bypass access. See `novel-matchanovels-extension/`.
- JabraScan resolves metadata and PDFs through the corrected routes; PDFBox uses
  process-default scratch while font resources retain their resource context.
  The active image CDN uses its responsive constructor for covers. See
  `novel-jabrascan-extension/`.
- Existing library metadata refresh updates covers without clearing data. Host
  tests cover adapter surfaces and refresh/sync without chapter writes; mocked
  repositories do not prove actual database behavior.

## Technical evidence

These are phase-specific results; overlapping suites must not be added as a
new unique grand total. They are reused evidence, not reruns for integration.

| Phase | Reported result and boundary |
| --- | --- |
| Discovery | 46 JVM tests; independent 34 overlap; About 2 static checks |
| Navigation | Historical full selected fixture: 111 passed, 0 failed |
| Matcha and Sky | 68 passed, 0 failed |
| Jabra | Latest suite: 121 passed, 0 failed in each repository, same suite |
| Host | 20 passed, 0 failed; four adapter surfaces and metadata refresh/sync with mocked repositories |
| Independent check | `mw2m5b41-9-q2rm`: PASS |
| Formatting | Final 10 Kotlin files, six phases, idempotent; earlier 23 Kotlin files and discovery five-file read-only check |

MockK is test-only and is not an APK runtime dependency. Source-byte audit at
workspace `s37/local-git/evidence/inventory.json` covered every Kotlin reference;
all 13 changed module Kotlin mirrors were byte-identical. Workspace evidence is
retained locally under `s37/evidence/`, `s37/reading-sequence/`, `s37/pdf/` and
`s37/covers/`; archives and private reports are not committed.

Latest unsigned technical artifact SHA-256 values (not distribution artifacts):

- Daimei: `0c6d03b205672014ea8f86f7cdcedfdbc36a132843b2c1d185ce7a6245a488db`
- Matcha: `03ff70f4d518ecf3900025b1459d43e3a78af60a9db7471392391b7210ec8420`
- Jabra Covers: `85640bbc472123b61ed7a9a0a876b0aa8d9679561b968dd4a16a74f2c807069b`

Historical APKs are not the current candidate. No rebuild during Git integration
avoids changing the Git-generated app `versionName`.

## Commits and worktree

| Repository | Responsibility | Full source commit ID |
| --- | --- | --- |
| main | discovery-about | `a0234446821fdc09fa26d73b42b9034a1b25dd8a` |
| main | navigation | `b5301b9ca4cbe54c4b5f3cbaec84f2a1ed63835e` |
| main | matcha-vip | `2b3de7f10fc2b028410701d1cd8ae25da3c1214c` |
| main | jabra-stabilization | `1e09da8716652ac333a56e72e66cf129de0a7d53` |
| extensions | matcha-vip | `57f3ceed1de41c75860f3aeecfe4521be5820214` |
| extensions | jabra-stabilization | `a1484761622dde25c19ac9999616885626f3a93b` |

The six source commit IDs above are materialized local commits. All commit IDs,
including the documentation commit IDs, are recorded in workspace
`s37/local-git/closure.md` after commits; this file cannot contain its own future ID. Final included files and actual Git status are recorded there after
verification. Expected main exclusions remain intact: `AGENTS.md`,
`docs/novel-reader-state.md` and `graphify-out/`; the main tree is not expected
to be clean. The canonical tree is expected clean after commit, subject to the
parent's final check.

## Warnings and boundaries

- Broad app formatting has seven unrelated historical baseline violations; not fixed.
- AST evidence was partial: 135 partial, 227 no-symbol, stale community labels,
  and no API pass. JVM ByteBuddy, Java 25 and Gradle 10 deprecation warnings remain.
- Native ASSESS was unavailable for untracked scopes; there is no approval receipt.
- Versions, package IDs, certificates, licenses, backups and progress are retained.
  Phone certificate pins are owner-reported only, not key inspection. No compatible
  signing or ELF 16 KiB proof is claimed; S35 release is not approved.
- Do not stage APKs, keys, caches, phone logs or generated private reports. No push,
  release, publication, catalog changes, S38 work or phone operation is authorized.
