# S37 — Manual validation of discovery, reading sequence and source corrections

**Final status:** the owner reported Android PASS for all seven surfaces: Popular,
Recent, Search, Details, Library, PDF and Progress. The owner also accepted the
prior extension separation, ascending navigation, VIP, PDF routing and PDFBox
validations, and authorized functional closure and local Git commits only. See
`s37-closure.md` for evidence boundaries and outstanding integration records.

The checklist below is the historical validation protocol, not open follow-up work
or a claim that an agent installed or ran an APK. Historical APKs, including the
original S37 build, predate later corrections and are not the current candidate.
Unsigned technical builds are not installable updates or distribution artifacts.

No ADB, phone changes, package removal, data clearing, trust reset, backup changes
or persisted reading-data edits are authorized by local Git integration. Signing
keys must not be read, replaced or regenerated. Any future installation requires
separate authorization and independently verified compatible signing.

## Before installation

- Record the candidate SHA-256, applicationId, versionCode and signing status from
  `s37/reading-sequence/evidence/` for the correction, or `s37/evidence/` for the
  original build. Do not confuse their results or artifacts. A later externally
  signed artifact has a different hash and needs its own public signature check.
- A compatible update requires the same existing package identity and signer.
  Use only independently verified, compatibly signed APKs after separate manual
  authorization. An unsigned build proves compilation, not signature compatibility.
  Existing extension signers must be checked independently; do not assume that
  they match Daimei's signer or change trust to accept a different key.
- The normal debug applicationId is `app.daimei.dev`. Do not uninstall an existing
  app, clear its data or change signing/trust settings to make a signature mismatch
  disappear. Report an installation/signature mismatch instead.
- Keep installed Mihon manga extension packages untouched. Capture their existing
  package names/versions if you can, without changing the packages.

## Discovery and warnings

1. Open the novel extensions list with at least one installed manga extension.
   It must not be registered as a novel extension or appear as Loaded/Not loaded/
   Untrusted in Daimei. It must remain installed and usable in Mihon.
2. With MatchaNovels, SkyNovels and JabraScan already installed, confirm their
   novel identities are detected. A source may load only after its existing
   supported-version/signature/trust/content-warning requirements pass.
3. Do not grant new trust just to make the test pass. A valid but untrusted novel
   extension must retain its warning; an unsupported novel contract must retain
   its incompatible-version warning. Neither should silently disappear as manga.
4. Relaunch Daimei and confirm the same result after initial discovery. If you
   independently perform an authorized extension install/update, confirm the
   package-change notification path applies the same eligibility policy.
5. JabraScan requires its installed package context. Private contextless `.ext`
   loading is not claimed supported. No production API/trust change is permitted.

## About

- Open About and confirm there are no empty website/X/Facebook/Reddit/GitHub
  destinations or empty privacy-policy action.
- Confirm the configured Discord action still opens its existing destination.
- Confirm the Mihon attribution remains, mentions Apache 2.0 and opens the upstream
  repository; the open-source license screen must remain reachable.
- Check layout and accessibility labels on the real screen. Static/JVM checks
  cannot prove Android rendering, navigation, browser resolution or external uptime.

## Novel start, sequence and resume

Run these checks only after the prerequisites above are met. Do not reset a real
novel's progress to manufacture a clean state; use an already unread novel instead.

1. In SOURCE order, select an unread novel whose list runs from chapter 1 upward.
   Press Start without manually selecting a chapter. It must open chapter 1, not
   the last chapter. Record the title and chapter count.
2. Next must advance 1 → 2 → 3. Toggle the list's ascending/descending display and
   repeat without clearing progress: visual direction must not reverse narration.
3. Pause in a readable chapter with existing progress. Record the chapter and
   logical reading position; close and reopen it through the existing resume path.
   It must retain that chapter/position, rather than restart or jump to the end.
   Existing history, library entries and bookmarks must remain intact.
4. On an accessible public novel with at least 405 chapters, verify 404 → 405 and
   no next chapter after 405. JabraScan's measured public PDFs are suitable when
   that work is available. Do not treat access to Matcha's VIP chapters as a
   prerequisite or attempt to bypass membership to exercise the boundary.
5. Decimal/special/unknown numbers and duplicate/nonfinite cases have focused JVM
   coverage; record any discrepancy encountered on real content. Numeric sort and
   image manga behavior must not change merely because SOURCE novels were fixed.

## MatchaNovels access restrictions

- Open a known restricted chapter through the normal source path. The explicit
  membership card must fail with `MatchaNovels: chapter requires VIP membership`,
  rather than appear as complete narrative. Record how the reader presents the
  error. No login automation, alternate endpoint or access bypass is part of S37.
- Ordinary story text that happens to mention VIP, or a list-only blocked flag,
  must not by itself make an otherwise readable chapter fail.
- Preserve existing progress when a restricted load fails; do not mark the notice
  as a completed chapter or clear saved reading state to dismiss the error.

## JabraScan metadata and PDFs

- Refresh the affected work's chapter list. JSON belongs to the content repository,
  not the removed GitHub PDF repository. The work and chapter identities must stay
  stable so existing history/progress can resolve them.
- Open chapter 1 and an accessible late chapter. Check actual Android PDF text
  extraction and reader rendering, not only a successful HTTP status. Public
  probes of chapters 1, 404 and 405 returned HTTP 206 and `%PDF-1.7` from
  `https://books.jabrascan.net/<work>/<filename>` (no `/books` prefix).
- Close/reopen the app and load a chapter before refreshing its list, exercising
  the fresh-process metadata path. Local-server routing and unsupported-server
  errors must retain their previous behavior.
- The routing tests' byte-fetch sentinel proves URL selection before PDFBox, not
  Android PDF extraction. JVM extraction fixtures likewise do not replace this
  installed-package/context check.

## Covers

The active image CDN uses a responsive image constructor. Cover handling follows
that constructor across source surfaces; the existing library metadata refresh
updates cover metadata without clearing data. The PDF fix keeps process-default
scratch handling separate from the resource context used for fonts.

## Scope boundary

S38 remote novel catalog integration remains separate and disabled/unimplemented.
The existing manga-shaped remote catalog protocol is rejected at ingestion: its
entries cannot declare novel kind/API compatibility. Its stored repository
configuration is not deleted. Until S38 provides a supported novel consumer,
that protocol will not populate available novel extensions or novel update data.
Do not reinterpret a manga catalog's `extensionLib` as novel API compatibility,
configure the S36 local-test snapshot as a live store, or change installed APKs
as part of this historical checklist. Local Git integration is authorized; production
publication is not.
