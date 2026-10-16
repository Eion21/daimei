package app.novel.source.api

/**
 * The single source of truth for the `:novel-api` contract version.
 *
 * Three things have to agree on "which contract is this?", and before this constant each carried its own hand-written
 * copy that could drift silently:
 *
 * 1. the published `app.novel:novel-api` artifact — its Maven version is read back from this file by the module's
 *    `build.gradle.kts`, so an author's dependency coordinate names the exact contract they compiled against;
 * 2. the host's extension loader — [eu.kanade.tachiyomi.extension.util.SUPPORTED_NOVEL_API_VERSIONS] lists the
 *    contract versions the app will run, and an apk's `app.novel.extensionApi` manifest metadata is checked against
 *    that list;
 * 3. an extension author's manifest metadata, which declares this same value.
 *
 * `NovelApiContractVersionTest` in `:app` fails if the loader's supported list stops containing this value. That test
 * is the guard: raising the contract without raising what the loader accepts would otherwise stop every installed
 * novel extension from loading, with no build error anywhere to warn anyone.
 *
 * Raise this only for an incompatible change to the interfaces in this module. It is a promise that an apk built
 * against the previous value no longer links.
 */
const val NOVEL_API_VERSION: Double = 1.0
