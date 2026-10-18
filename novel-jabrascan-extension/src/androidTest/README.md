# The synthetic PDF fixture

`synthetic_spanish_novel.pdf` is **self-authored text** laid out by hand to reproduce every shape the
JabraScan reader has to survive. It stands in for the third-party jabrascan.net chapter samples, which
are real published novels and no longer live in this repository. None of its prose is copied from any
real novel: the paragraphs, the running header (`Cuaderno de Pruebas`) and the page numbers were all
written for this fixture.

## Committed copies

The same bytes live in exactly two places, and a JVM test asserts they stay byte-identical:

- `app/src/androidTest/assets/synthetic_spanish_novel.pdf`
- `novel-jabrascan-extension/src/androidTest/assets/synthetic_spanish_novel.pdf`

`SyntheticFixtureIntegrityTest` (JVM, in `:novel-jabrascan-extension`) is what compares those two copies
against each other and pins the fixture's exact bytes. There is no third copy: the old `:novel-pdf-spike`
module that used to carry one was deleted.

Committed fixture, for reference:

- size: **10236 bytes**
- SHA-256: `7080729ec9d889c51b9a4844f52889654fbecf127398371962a3308489392dbe`

## Regenerating it (out-of-band; NOT automated)

The generator is `src/androidTest/kotlin/app/novel/extension/jabrascan/SyntheticPdfGeneratorTest.kt`. It is
**skipped by default** and only runs when the `generateFixture` instrumentation argument is `true`, so it
never fires as a side effect of the normal suites.

One caveat when reading a run's report: AGP's connected-test XML does not render an assumption failure as
*skipped* — it writes a `<failure>` element, and it does the same for a plain `@Ignore`d test
(`skipped="0"` in both cases, verified with a control test). The behaviour is right — the instrumentation
run exits 0, the build stays green, and nothing is written without the argument — only that XML label is
wrong.

1. **Run the generator on a device/emulator.** Either through Gradle:

   ```bash
   ./gradlew :novel-jabrascan-extension:connectedDebugAndroidTest \
     -Pandroid.testInstrumentationRunnerArguments.generateFixture=true
   ```

   or through `adb` directly:

   ```bash
   adb shell am instrument -w -e generateFixture true \
     app.novel.extension.jabrascan.test/androidx.test.runner.AndroidJUnitRunner
   ```

   It writes the **raw** output to the device's external cache dir and logs the absolute path:

   ```
   SyntheticPdf: WROTE /storage/emulated/0/Android/data/app.novel.extension.jabrascan/cache/synthetic_spanish_novel.raw.pdf (… bytes)
   ```

   Note the `.raw.` in the name: this is the generator's output, **not** the committed fixture. The
   generator writes only to that device cache and is never wired to overwrite the fixture in the repo.

2. **Pull the raw output.** Use the path the test logged:

   ```bash
   adb pull /storage/emulated/0/Android/data/app.novel.extension.jabrascan/cache/synthetic_spanish_novel.raw.pdf entrada.pdf
   ```

3. **Post-process it with qpdf** so the cross-reference table and the indirect objects are wrapped as
   object streams — the shape the real samples have:

   ```bash
   qpdf --object-streams=generate entrada.pdf salida.pdf
   ```

   (qpdf **12.4.1** was the version used to produce the committed fixture.)

4. **Copy the result, byte-for-byte, into both committed locations** listed above. Confirm the copy
   landed: `10236` bytes and SHA-256 `7080729e…`.

5. **Re-run the suites.** The JVM test (`:novel-jabrascan-extension:testDebugUnitTest`) re-hashes the two
   copies without executing qpdf, and the instrumented tests
   (`:novel-jabrascan-extension:connectedDebugAndroidTest`) parse the fixture with PdfBox and pin its font
   shape. If the fixture changed on purpose, the size and SHA-256 above — and the assertions that pin them
   — have to be updated in the same commit.

Tests never execute qpdf. They only read the committed fixture.
