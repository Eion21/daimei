# Local EPUB text import: design proposal

**Design only; not implemented.** This is not a shipping guarantee. It proposes
no source API, schema or API changes. Recommendations below require owner approval;
this document does not establish implemented defaults or current restore support.

## First-version boundary

Recommend local, unencrypted, reflowable text EPUB 2/3 only. Read the package
spine in reading order and convert headings and paragraphs to plain Unicode text.
The minimum flow is select, validate, import, read, close and reopen with progress.
No DRM decryption or bypass is allowed. Font obfuscation alone is not DRM;
classify it separately and do not decode fonts for this plain-text flow.

Exclude fixed layout, interactive/scripted content, audio, remote content and
image-based books. Cover images and CSS fidelity are also out of V1.
Do not run JavaScript, fetch network resources or load external entities.
Reject unsupported required content rather than silently importing an empty book.
For otherwise supported text, discard presentation assets without loading them;
classification rules for optional assets versus required content need approval.

## Existing boundaries to evaluate

`core/archive` has `EpubReader` for package/spine lookup and image extraction.
The source-local metadata helper fills existing manga/chapter metadata.
Neither is a complete Novel text importer or a verified hostile-input boundary.
Evaluate reuse of package/metadata discovery only after security review; do not
assume its path resolution or parser settings satisfy the constraints below.
Map accepted text to existing `ChapterContent` blocks and logical
`ReadingPosition` values, not screen pixels. The stable book/chapter identity,
spine-to-block/position mapping and cache versioning remain owner decisions.
No new module, database schema or source interface is specified here.

## Validate before publishing

Treat the selected file, ZIP directory, package XML and XHTML as untrusted.
Use secure XML parsing: reject DTDs and disable external entities, external
resource resolution and network access for every document, not just the package.
Check required package/manifest/spine entries, references and supported media types.
Reject missing entries, invalid XML, broken spine references and unsupported formats.

Normalize ZIP entry names and resolved package/content references consistently,
including slash variants and encoded path components, before any lookup or write.
Reject absolute paths, drive-qualified paths, `../` segments, normalized traversal,
duplicate/colliding normalized entries, symlinks and symlink escapes. Keep all
resolved destinations inside the host-owned staging directory; never write to
archive-supplied absolute destinations or arbitrary caller paths.
Stream and bound actual decompressed bytes, entry counts and expansion ratios;
do not trust ZIP headers or declared sizes. Bound input bytes, per-entry and total
output, document depth, text/block counts and processing time; abort on overrun.

Proposed conservative limits below are **pending owner decision**, not defaults:

| Resource | Candidate ceiling |
|---|---:|
| Original EPUB input | 50 MiB |
| Archive entries | 2,000 |
| Actual decompressed bytes per entry / total | 10 MiB / 100 MiB |
| Actual expansion ratio per entry / total | 100:1 |
| XML nesting depth | 64 |
| Extracted text / content blocks | 20 MiB / 100,000 |
| Validation and conversion elapsed time | 30 seconds |

Enforce ratio limits while streaming, including zero compressed-size anomalies.
Huge inputs and decompression bombs must fail before exhausting memory or disk.
Exact accounting, timeouts and limits require validation on synthetic fixtures.

## Storage and rejection contract

Recommend a host-controlled directory under Android `Context.filesDir`
(persistent internal storage), **not cache**. Its directory layout is pending approval.
Recommend copying rather than retaining a reference to a user-selected location;
copy-versus-reference and storage policy are still pending owner approval.
Validation may use bounded host-owned staging. Only after validation succeeds,
retain the original EPUB as an owned copy and atomically publish accepted content.
Clean up owned staging on failure, cancellation and interrupted-import recovery;
publication/recovery details must be agreed before implementation.
Rejection must not write arbitrary paths or change the library database, history,
reading positions or published content. No partly imported library record may remain.

Proposed clear messages (not existing UI strings):

- DRM: “This EPUB is DRM-protected. Daimei cannot decrypt or import it.”
- Fixed layout: “Fixed-layout EPUB is not supported. Choose a reflowable text EPUB.”
- Invalid: “This EPUB is invalid or incomplete. No book was imported.”
- Unsafe: “This EPUB contains unsafe archive paths. No book was imported.”
- Oversized/bomb: “This EPUB exceeds safe import limits. No book was imported.”
- Other unsupported content: “This EPUB requires unsupported content. No book was imported.”
- Missing original: “The local EPUB file is missing. Reimport the original file to read it.”

## Backup proposal, not an existing restore guarantee

Recommend excluding the original EPUB binary from the existing logical backup;
byte-level backup/storage policy remains an owner decision. Users would need to
reimport the original after restore or loss of the local copy. A missing file must
produce the clear message above, not a claim that text has been restored.
Plan metadata/progress backup only after stable identity and restore contracts
are verified, including matching a reimport to logical reading positions.
Do not claim current EPUB import or restore support from existing backup code.

## Decisions and verification before implementation

Owner approval is pending for limits, supported-content classification, storage,
copy/reference policy, publication recovery, byte backup and metadata/progress restore.
Also settle stable identity, spine/chapter mapping, logical positions and cache versions.
Plan only self-authored synthetic fixtures: valid EPUB 2/3, Unicode text/spine order,
close/reopen progress, boundaries, malformed XML, traversal, duplicates, symlinks,
huge files, bombs and unsupported content. Verify every rejection leaves persistent
state unchanged and removes owned staging. Plan positive backup/reimport semantics
and missing-file checks separately; these are proposed tests, not implemented proof.
