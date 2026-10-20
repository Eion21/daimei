# Daimei modifications

Daimei is a modified Mihon 0.21.2 distribution. See [NOTICE](NOTICE) for
attribution and the unchanged [LICENSE](LICENSE) for Apache License 2.0.

## Baseline and scope

The baseline is `e053a115efea983dc85658068be9df5b9327357e`, the local root
commit recorded as unmodified Mihon 0.21.2, not the already-customized master.
The inventory below records the 73 existing files with status `M` at reviewed
commit `acf77da`, before this documentation/comment update, using:

```sh
git diff --name-status e053a115efea983dc85658068be9df5b9327357e HEAD --diff-filter=M
```

Added files are outside this inventory. Paths are literal repository-relative
technical identifiers; their spelling is preserved, not a list of supported services.
This baseline comparison is not legal certification or a complete added-file inventory.

## Change groups

- **Identity:** Daimei application identity, launcher artwork, strings and build configuration.
- **Reader:** text-reading integration, chapter synchronization and library/history navigation.
- **Extension runtime:** extension loading, source discovery and host integration.
- **Persistence:** chapter/content persistence integration and database bindings.
- **Downloads:** text-content queue, cache, storage and downloaded-state integration.
- **Backup:** content/progress serialization, validation and restore integration.
- **Tests:** synthetic-fixture and runtime/reader checks accompany the changes;
  added test files are not enumerated in this modified-existing-file inventory.

## Proposed per-file notices: owner approval required

The proposed final target list for Apache License §4(b) notices is all 73 paths
below. Each modified file must carry a prominent notice stating it was changed;
this central inventory does **not** replace those per-file notices.
The owner must approve the notice format before application. No such notices
or new copyright headers are applied by this documentation update.
Example short header for a file that supports line comments, **proposal only**:

```text
// Modified for Daimei; see MODIFICATIONS.md.
```

Placement and syntax for XML, configuration and other formats remain subject
to owner approval, preserving existing upstream notices and valid file syntax.

## Modified existing files (73)

- `.gitattributes`
- `.gitignore`
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
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/updates/UpdatesTab.kt`
- `app/src/main/java/eu/kanade/tachiyomi/util/CrashLogUtil.kt`
- `app/src/main/java/mihon/app/di/AppGraph.kt`
- `app/src/main/res/drawable/ic_launcher_background.xml`
- `app/src/main/res/drawable/ic_launcher_foreground.xml`
- `app/src/main/res/drawable/ic_launcher_monochrome.xml`
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
- `i18n/src/commonMain/moko-resources/base/strings.xml`
- `i18n/src/commonMain/moko-resources/es/strings.xml`
- `settings.gradle.kts`
- `source-local/src/main/kotlin/tachiyomi/source/local/LocalSource.kt`
