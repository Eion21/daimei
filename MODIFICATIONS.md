# Daimei modifications

Daimei is a modified Mihon 0.21.2 distribution. See [NOTICE](NOTICE) for
attribution and the unchanged [LICENSE](LICENSE) for Apache License 2.0.

## Baseline and scope

The baseline is the official Mihon v0.21.2 commit
`9612d955db3165e1578fdf9ff19ca9b7762c4a46`. The local imported root
`e053a115efea983dc85658068be9df5b9327357e` matches all 1,459 upstream files.
The inventory below records the 114 modified existing upstream files at the
accepted S37 commit `defd4c83cb258beb3f93817c1072fc45da93d644`, before this
attribution-only update. It supersedes the earlier 73-file inventory.

The same snapshot adds 201 paths and deletes two upstream files:
`app/google-services.json` and
`app/src/main/java/mihon/feature/support/SupportUsScreen.kt`.
New paths are not silently classified as modified upstream paths. Existing
copyright and attribution notices remain applicable, including in derived work.
For a reproducible local inventory of modified existing paths:

```sh
git diff --name-status e053a115efea983dc85658068be9df5b9327357e defd4c83cb258beb3f93817c1072fc45da93d644 --diff-filter=M
```

Paths are literal repository-relative identifiers, not a list of supported
services. The comparison and notice coverage are technical records, not legal
certification or a claim of ownership of upstream work.

## Change groups

- **Identity:** Daimei application identity, launcher artwork, strings and build configuration.
- **Reader:** text-reading integration, chapter synchronization and library/history navigation.
- **Extension runtime:** extension loading, source discovery and host integration.
- **Persistence:** chapter/content persistence integration and database bindings.
- **Downloads:** text-content queue, cache, storage and downloaded-state integration.
- **Backup:** content/progress serialization, validation and restore integration.
- **Tests:** synthetic-fixture and runtime/reader checks accompany the changes;
  added test files are not enumerated in this modified-existing-file inventory.

## Per-file modification notices

The owner authorized these attribution-only notices for first-publication
preparation. Each of the 114 modified inherited files below carries one
prominent notice stating it was modified for Daimei and pointing here.
The central inventory supplements, and does not replace, the per-file notices.
No new copyright claim is added and existing headers and credits are preserved.

The notice is `Modified for Daimei; see MODIFICATIONS.md.` using the file's
comment syntax: `//` for Kotlin/Gradle Kotlin; `#` for GraphQL, TOML, Git and
ProGuard configuration; and HTML/XML comments for Markdown and XML. XML notices
follow the declaration when present, so the declaration remains first.

This update changes notices and attribution documentation only: it does not
alter executable source, resource values, build/signing settings, credentials,
licenses or the accepted S37 fixes. Added files receive no gratuitous notice
claiming they were modified upstream files; deleted files cannot carry new
headers and are identified in the baseline section.

## S38 forward changes

S38 adds a read-only novel-catalog consumer, isolated future APK policy tests and a
separate metadata screen. It adds an overflow navigation action to inherited
`app/src/main/java/eu/kanade/tachiyomi/ui/browse/extension/ExtensionsTab.kt` and
English catalog copy to `i18n/src/commonMain/moko-resources/base/strings.xml`.
Both carry the per-file modification notice. Existing credits and licenses,
installed-extension behavior, signing/trust gates and reading data are preserved.
Remote APK download/install/update remains disabled; valid metadata does not
promote `hostCompatible: false`. See [S38 scope and limitations](docs/s38-novel-catalog.md).
The unified-repositories revision changes the Extensions tab, empty-state and Browse settings navigation, MainActivity legacy-link handling, base and Spanish catalog copy, and the S38 documentation. It adds a stateless navigation helper and focused tests. SettingsBrowseScreen.kt now carries its own modification notice; the earlier 114-file snapshot remains unchanged. Legacy storage, loaders, installation and trust infrastructure remain in place.

The 114-file inventory below remains the historical accepted S37 snapshot, not a
new inventory or a claim that S38 has been committed or published.

## Modified existing files (114)

- `.gitattributes`
- `.gitignore`
- `CONTRIBUTING.md`
- `README.md`
- `app/build.gradle.kts`
- `app/proguard-rules.pro`
- `app/src/debug/res/drawable/ic_launcher_background.xml`
- `app/src/debug/res/drawable/ic_launcher_foreground.xml`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/graphql/mihon/graphql/anilist/AniListGetMangaDetails.graphql`
- `app/src/main/graphql/mihon/graphql/anilist/AniListSearchManga.graphql`
- `app/src/main/java/eu/kanade/domain/chapter/interactor/SyncChaptersWithSource.kt`
- `app/src/main/java/eu/kanade/domain/source/interactor/GetEnabledSources.kt`
- `app/src/main/java/eu/kanade/presentation/browse/ExtensionsScreen.kt`
- `app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt`
- `app/src/main/java/eu/kanade/presentation/more/MoreScreen.kt`
- `app/src/main/java/eu/kanade/presentation/more/onboarding/GuidesStep.kt`
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsDataScreen.kt`
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTrackingScreen.kt`
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/about/AboutScreen.kt`
- `app/src/main/java/eu/kanade/presentation/webview/WebViewScreenContent.kt`
- `app/src/main/java/eu/kanade/tachiyomi/App.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/BackupFileValidator.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/create/creators/MangaBackupCreator.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupChapter.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupManga.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/MangaRestorer.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadCache.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadManager.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadProvider.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadStore.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadWorker.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/download/model/Download.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateNotifier.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateWorker.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/notification/NotificationReceiver.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/track/bangumi/BangumiInterceptor.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/track/comick/ComickApi.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/track/mangabaka/MangaBakaApi.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/track/myanimelist/MyAnimeListApi.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt`
- `app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionManager.kt`
- `app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt`
- `app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/browse/migration/sources/MigrateSourceTab.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/SearchViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/deeplink/DeepLinkScreen.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/download/DownloadQueueScreen.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/download/DownloadQueueViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/history/HistoryTab.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/track/TrackInfoDialog.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/more/MoreTab.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/updates/UpdatesTab.kt`
- `app/src/main/java/eu/kanade/tachiyomi/util/CrashLogUtil.kt`
- `app/src/main/java/mihon/app/di/AppGraph.kt`
- `app/src/main/res/drawable/ic_launcher_background.xml`
- `app/src/main/res/drawable/ic_launcher_foreground.xml`
- `app/src/main/res/drawable/ic_launcher_monochrome.xml`
- `app/src/main/res/drawable/ic_mihon.xml`
- `core/common/src/main/kotlin/tachiyomi/core/common/Constants.kt`
- `data/build.gradle.kts`
- `data/src/main/java/tachiyomi/data/DatabaseBindings.kt`
- `data/src/main/java/tachiyomi/data/backup/RestoreRepositoryImpl.kt`
- `data/src/main/java/tachiyomi/data/chapter/ChapterRepositoryImpl.kt`
- `domain/build.gradle.kts`
- `domain/src/main/java/eu/kanade/tachiyomi/extension/model/Extension.kt`
- `domain/src/main/java/tachiyomi/domain/backup/model/RestoredManga.kt`
- `domain/src/main/java/tachiyomi/domain/release/interactor/GetApplicationRelease.kt`
- `gradle/build-logic/src/main/kotlin/mihon/gradle/BuildConfig.kt`
- `gradle/libs.versions.toml`
- `i18n/src/commonMain/moko-resources/ar/strings.xml`
- `i18n/src/commonMain/moko-resources/as/strings.xml`
- `i18n/src/commonMain/moko-resources/base/strings.xml`
- `i18n/src/commonMain/moko-resources/be/strings.xml`
- `i18n/src/commonMain/moko-resources/bn/strings.xml`
- `i18n/src/commonMain/moko-resources/ca/strings.xml`
- `i18n/src/commonMain/moko-resources/cs/strings.xml`
- `i18n/src/commonMain/moko-resources/de/strings.xml`
- `i18n/src/commonMain/moko-resources/el/strings.xml`
- `i18n/src/commonMain/moko-resources/eo/strings.xml`
- `i18n/src/commonMain/moko-resources/es/strings.xml`
- `i18n/src/commonMain/moko-resources/eu/strings.xml`
- `i18n/src/commonMain/moko-resources/fa/strings.xml`
- `i18n/src/commonMain/moko-resources/fil/strings.xml`
- `i18n/src/commonMain/moko-resources/fr/strings.xml`
- `i18n/src/commonMain/moko-resources/he/strings.xml`
- `i18n/src/commonMain/moko-resources/hi/strings.xml`
- `i18n/src/commonMain/moko-resources/hr/strings.xml`
- `i18n/src/commonMain/moko-resources/in/strings.xml`
- `i18n/src/commonMain/moko-resources/it/strings.xml`
- `i18n/src/commonMain/moko-resources/ja/strings.xml`
- `i18n/src/commonMain/moko-resources/kmr/strings.xml`
- `i18n/src/commonMain/moko-resources/ko/strings.xml`
- `i18n/src/commonMain/moko-resources/ml/strings.xml`
- `i18n/src/commonMain/moko-resources/ms/strings.xml`
- `i18n/src/commonMain/moko-resources/nl/strings.xml`
- `i18n/src/commonMain/moko-resources/pt-rBR/strings.xml`
- `i18n/src/commonMain/moko-resources/pt/strings.xml`
- `i18n/src/commonMain/moko-resources/ro/strings.xml`
- `i18n/src/commonMain/moko-resources/ru/strings.xml`
- `i18n/src/commonMain/moko-resources/sv/strings.xml`
- `i18n/src/commonMain/moko-resources/tr/strings.xml`
- `i18n/src/commonMain/moko-resources/uk/strings.xml`
- `i18n/src/commonMain/moko-resources/vi/strings.xml`
- `i18n/src/commonMain/moko-resources/zh-rCN/strings.xml`
- `i18n/src/commonMain/moko-resources/zh-rTW/strings.xml`
- `settings.gradle.kts`
- `source-local/src/main/kotlin/tachiyomi/source/local/LocalSource.kt`

## S33 identity documentation and resources

The S33 working changes update `README.md` and `CONTRIBUTING.md`, add
`DAIMEI-CHANGELOG.md`, remove stale localized donation resource entries, and
remove the inherited `app/google-services.json`. The upstream `CHANGELOG.md` and `LICENSE` remain unchanged. `NOTICE` retains
Daimei and upstream attribution; only its reference to the now-applied notices
is updated. The per-file notice authorization and coverage are recorded above.
