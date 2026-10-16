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
 * Raising this value does not by itself strand an installed extension: the loader keeps every previous
 * version in its supported list, so a raise may be additive (as 1.0 → 1.1 was, when the host network
 * was added). The only hard rule is that whichever value is declared here is also listed there, and
 * `NovelApiContractVersionTest` is what enforces it. Remove a previous version from that list only
 * when an apk built against it genuinely no longer links.
 */
const val NOVEL_API_VERSION: Double = 1.1
