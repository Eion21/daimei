# Novel source integrity (S30)

MatchaNovels must not report a successful empty or partial chapter list when its flight payload is damaged. SkyNovels and JabraScan must preserve prose while removing HTML markup. These fixes are local to the three extensions; the reader, backup pipeline and novel API are unchanged.

## MatchaNovels

- A page without a `capitulos` payload retains the legacy rendered-link path.
- A present payload is authoritative. Decode the flight string once, parse its chapter object as JSON, and validate every row before returning chapters.
- The numeric `total` must equal the number of rows. Missing/invalid totals, malformed JSON, invalid chapter numbers, invalid row shapes and duplicate chapter IDs raise `IllegalStateException` with the prefix `MatchaNovels: invalid chapter payload` and the novel ID. There is no fallback to the truncated rendered links after a payload error.
- A valid zero-total, empty-array payload remains a genuinely empty list.
- Existing complete plain/escaped fixtures and the 3,216-chapter case remain covered offline.

## SkyNovels and JabraScan

Each APK owns its own `ProseHtml` tokenizer, with no new dependencies or shared runtime module. It removes only complete, recognized HTML tags with syntactically valid attributes. Attribute quotes protect embedded `>` characters; incomplete tags, unrecognized tags and comparisons remain literal text. Entities are decoded after markup recognition, never reinterpreted as tags.

SkyNovels preserves headings, paragraph/list boundaries, breaks, images and dividers, and continues to pass the result through its existing markdown reader. Plain markdown retains its previous entity handling. JabraScan continues to unwrap CDATA, preserve whitespace and decode the five XML entities in one pass.

This is text extraction, not an HTML rendering or sanitization boundary. Unknown markup is intentionally preserved instead of guessing and deleting prose.

## Verification

From the repository root:

```sh
./gradlew --offline -Pandroid.builder.sdkDownload=false \
  :novel-matchanovels-extension:testDebugUnitTest \
  :novel-skynovels-extension:testDebugUnitTest \
  :novel-jabrascan-extension:testDebugUnitTest
```

The tests use saved response fixtures and real parser/source methods with a stub network: no live-site requests. Regression coverage includes source-API error propagation and exact prose returned by the source APIs. S30 raw logs and copied JUnit XML live in `app/build/s30-source-integrity/`; the task/evidence log is `../odd/tasks/source-integrity-s30.md`.

All three extension APK version codes advance to 2 / version name 1.0.1 for installable updates. APK builds, device installation, live-site verification, the application-wide suite and the existing C5 reader changes are outside this focused verification.
