# Contributing to Daimei

Daimei is a light novel reader fork of Mihon 0.21.2. Discuss fork-specific changes and reports with the Daimei maintainer through your existing channel; the upstream Mihon project is not Daimei support. Keep changes focused and preserve inherited compatibility and attribution.

## Build locally

1. Use a local checkout, Android Studio or the command line, an Android SDK matching the project's build configuration, and JDK 21 as specified in [`.github/.java-version`](.github/.java-version). An emulator or Android device is useful for UI changes.
2. Use the project's Gradle wrapper, for example `./gradlew :app:assembleDebug`. The default build does not require a Firebase configuration file: telemetry is disabled and the no-op implementation is selected.
3. Run focused tests appropriate to your change. For the identity documentation contract, run:

   ```sh
   python3 -B -m unittest discover -s scripts/tests -p test_daimei_identity.py -k readme_and_contributing -v
   ```

Report the commands and results actually observed; a focused test is not evidence that all project-wide gates pass. Seven known legacy app/data Spotless violations are outside the current identity scope.

## Scope and history

For novel extension work, consult the [novel API guide](novel-api/README.md). Avoid changing package identifiers, extension trust or compatibility behavior as a side effect of branding. Kitsu retains its inherited runtime and Apollo introspection User-Agent values for compatibility; see the [README](README.md#reading-and-development).

Record Daimei changes in [Daimei history](DAIMEI-CHANGELOG.md), not in the preserved [upstream Mihon history](CHANGELOG.md). Retain existing copyright and attribution notices; see [LICENSE](LICENSE), [NOTICE](NOTICE), and the [modifications inventory](MODIFICATIONS.md). Per-file notice format remains subject to owner approval. Follow the [code of conduct](CODE_OF_CONDUCT.md).
