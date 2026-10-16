package eu.kanade.tachiyomi.data.download

import android.content.ContentResolver
import android.content.Context
import app.novel.source.api.ChapterContent
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.novel.ChapterOpener
import eu.kanade.tachiyomi.util.lang.Hash.md5
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import java.io.IOException

/**
 * This class is used to provide the directories where the downloads should be saved.
 * It uses the following path scheme: /<root downloads dir>/<source name>/<manga>/<chapter>
 *
 * @param context the application context.
 */
@Inject
@SingleIn(AppScope::class)
class DownloadProvider(
    private val context: Context,
    private val storageManager: StorageManager,
    private val libraryPreferences: LibraryPreferences,
) {

    private val downloadsDir: UniFile?
        get() = storageManager.getDownloadsDirectory()

    /**
     * Returns the download directory for a manga. For internal use only.
     *
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the manga.
     */
    internal suspend fun getMangaDir(mangaTitle: String, source: Source): Result<UniFile> =
        withContext(Dispatchers.IO) { createMangaDir(mangaTitle, source) }

    private fun createMangaDir(mangaTitle: String, source: Source): Result<UniFile> {
        val downloadsDir = downloadsDir
        if (downloadsDir == null) {
            logcat(LogPriority.ERROR) { "Failed to create download directory" }
            return Result.failure(
                IOException(context.stringResource(MR.strings.storage_failed_to_create_download_directory)),
            )
        }

        val sourceDirName = getSourceDirName(source)
        val sourceDir = downloadsDir.createDirectory(sourceDirName)
        if (sourceDir == null) {
            val displayablePath = downloadsDir.displayablePath + "/$sourceDirName"
            logcat(LogPriority.ERROR) { "Failed to create source download directory: $displayablePath" }
            return Result.failure(
                IOException(context.stringResource(MR.strings.storage_failed_to_create_directory, displayablePath)),
            )
        }

        val mangaDirName = getMangaDirName(mangaTitle)
        val mangaDir = sourceDir.createDirectory(mangaDirName)
        if (mangaDir == null) {
            val displayablePath = sourceDir.displayablePath + "/$mangaDirName"
            logcat(LogPriority.ERROR) { "Failed to create manga download directory: $displayablePath" }
            return Result.failure(
                IOException(context.stringResource(MR.strings.storage_failed_to_create_directory, displayablePath)),
            )
        }

        return Result.success(mangaDir)
    }

    /**
     * Returns the download directory for a source if it exists.
     *
     * @param source the source to query.
     */
    suspend fun findSourceDir(source: Source): UniFile? = withContext(Dispatchers.IO) {
        downloadsDir?.findFile(getSourceDirName(source))
    }

    /**
     * Returns the download directory for a manga if it exists.
     *
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the manga.
     */
    suspend fun findMangaDir(mangaTitle: String, source: Source): UniFile? = withContext(Dispatchers.IO) {
        findSourceDir(source)?.findFile(getMangaDirName(mangaTitle))
    }

    /**
     * Returns the download directory for a chapter if it exists.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the chapter.
     */
    suspend fun findChapterDir(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        source: Source,
    ): UniFile? = withContext(Dispatchers.IO) {
        val mangaDir = findMangaDir(mangaTitle, source)
        getValidChapterDirNames(chapterName, chapterScanlator, chapterUrl).asSequence()
            .mapNotNull { mangaDir?.findFile(it) }
            .firstOrNull()
    }

    /**
     * The text of a downloaded novel chapter, read back out of the file the downloader wrote, or null when there is
     * no such file or nothing readable in it.
     *
     * This is the other half of [Downloader.downloadNovelChapter]: that writes `<chapterDirName>.txt` into the
     * downloads tree, this reads it. Until it existed the file was decoration — `DownloadCache` indexed it, so the
     * badge said "downloaded", and no reader ever opened it — and a chapter could only be read with the network up
     * even though its whole text was sitting on disk. It mattered most exactly when the cache was gone, because
     * `ChapterContent` lives under `cache/` and clearing the app cache is a thing a user does without touching a
     * single download.
     *
     * It is here, in `:app`, rather than in the read chain `:domain` owns. `GetChapterContent` deliberately knows
     * nothing about folders, and `:domain` is not learning what a downloads directory is: the tree is a Mihon
     * concept, [DownloadProvider] is where that concept already lives, and the lookup belongs beside it rather than
     * contaminating a module that has to stay free of it. `:domain` still answers the cache and the source, so the
     * whole order — file, then cache, then source — is assembled by the one caller allowed to know all three.
     *
     * A file that cannot be read, or that holds nothing this format can turn back into blocks, is a miss and not a
     * failure: the caller goes on to the cache and the source, which is strictly better than refusing to open a
     * chapter over a truncated download.
     *
     * @param chapter the chapter to read.
     * @param manga the manga the chapter belongs to, whose title names its folder.
     * @param source the source the chapter was downloaded from, which names the source folder.
     */
    internal suspend fun findDownloadedNovelContent(
        chapter: Chapter,
        manga: Manga,
        source: Source,
    ): ChapterContent? = withContext(Dispatchers.IO) {
        // The one discriminator, asked once: the same rule that decides where the chapter is read and the downloader
        // writes it. A manga chapter is a page folder or a CBZ and is never prose, so it never comes through here.
        if (!ChapterOpener.shouldOpenNovelReader(chapter)) return@withContext null

        val mangaDir = findMangaDir(manga.title, source) ?: return@withContext null

        // Only the text names, and only the ones that are files. The bare chapter name is in the list too, and for a
        // novel chapter it is the name of nothing — but a stale `.cbz` beside the `.txt` would answer first if the
        // list were walked whole, and an unreadable CBZ is not a chapter of prose.
        val novelSuffix = ".${Downloader.NOVEL_CHAPTER_EXTENSION}"
        val file = getValidChapterDirNames(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            isNovelChapter = true,
        )
            .filter { it.endsWith(novelSuffix) }
            .firstNotNullOfOrNull { name -> mangaDir.findFile(name)?.takeIf { it.isFile } }
            ?: return@withContext null

        runCatching { file.openInputStream().use { it.readBytes().toString(Charsets.UTF_8).toChapterContent() } }
            .onFailure {
                logcat(LogPriority.ERROR, it) {
                    "[novel] The downloaded file for chapter ${chapter.id} could not be read as text"
                }
            }
            .getOrNull()
    }

    /**
     * Returns a list of downloaded directories for the chapters that exist.
     *
     * @param chapters the chapters to query.
     * @param manga the manga of the chapter.
     * @param source the source of the chapter.
     */
    suspend fun findChapterDirs(
        chapters: List<Chapter>,
        manga: Manga,
        source: Source,
    ): Pair<UniFile?, List<UniFile>> = withContext(Dispatchers.IO) {
        val mangaDir = findMangaDir(manga.title, source) ?: return@withContext null to emptyList()
        mangaDir to findChapterDirs(chapters, mangaDir).values.toList()
    }

    /**
     * Returns the ids of the chapters whose download directory exists.
     *
     * @param chapters the chapters to query.
     * @param manga the manga of the chapters.
     * @param source the source of the chapters.
     */
    suspend fun findDownloadedChapterIds(
        chapters: List<Chapter>,
        manga: Manga,
        source: Source,
    ): Set<Long> = withContext(Dispatchers.IO) {
        val mangaDir = findMangaDir(manga.title, source) ?: return@withContext emptySet()
        findChapterDirs(chapters, mangaDir).keys
    }

    /**
     * Returns the download directory of each chapter that has one, keyed by chapter id.
     *
     * A chapter is looked for under every name it may have been downloaded with, with the chapters looked up in
     * parallel. On a storage access framework directory each lookup is a content resolver query, so from
     * [LIST_CHAPTER_DIRS_MIN_CHAPTERS] chapters on the directory is listed once instead and the names are matched
     * against the listing, ignoring case as the lookups do. Plain file directories are always looked up by name, as
     * their listing can leave out files another app created.
     *
     * @param chapters the chapters to query.
     * @param mangaDir the download directory of the chapters' manga.
     */
    private suspend fun findChapterDirs(chapters: List<Chapter>, mangaDir: UniFile): Map<Long, UniFile> {
        val isDocumentTree = mangaDir.uri.scheme == ContentResolver.SCHEME_CONTENT
        if (isDocumentTree && chapters.size >= LIST_CHAPTER_DIRS_MIN_CHAPTERS) {
            val chapterDirs = mangaDir.listFiles().orEmpty()
                .filter { it.name != null }
                .associateByTo(sortedMapOf(String.CASE_INSENSITIVE_ORDER)) { it.name!! }
            return chapters
                .mapNotNull { chapter -> chapter.findDir { chapterDirs[it] }?.let { chapter.id to it } }
                .toMap()
        }
        return coroutineScope {
            chapters
                .map { chapter ->
                    async(chapterDirLookupDispatcher) { chapter.findDir(mangaDir::findFile)?.let { chapter.id to it } }
                }
                .awaitAll()
                .filterNotNull()
                .toMap()
        }
    }

    private fun Chapter.findDir(find: (String) -> UniFile?): UniFile? {
        // The chapter is in hand here, so this is the one lookup that can tell a novel's text file apart from a
        // manga's page folder instead of asking for both and hoping only one exists
        return getValidChapterDirNames(name, scanlator, url, ChapterOpener.shouldOpenNovelReader(this))
            .firstNotNullOfOrNull(find)
    }

    /**
     * Returns the download directory name for a source.
     *
     * `HttpSource` overrides `toString()` with its name and language, which is what every manga download folder in
     * the wild is named after. A `Source` that does not override it is named after its own identity instead, and
     * an identity hash changes on every launch: a novel's folder would be written somewhere nothing can find
     * again, so the download would never be indexed a second time nor deleted. So the fallback spells out the same
     * string `HttpSource` would have produced, and the two formats stay identical.
     *
     * @param source the source to query.
     */
    fun getSourceDirName(source: Source): String {
        return DiskUtil.buildValidFilename(
            if (source is HttpSource) source.toString() else "${source.name} (${source.lang.uppercase()})",
            disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames.get(),
        )
    }

    /**
     * Returns the download directory name for a manga.
     *
     * @param mangaTitle the title of the manga to query.
     */
    fun getMangaDirName(mangaTitle: String): String {
        return DiskUtil.buildValidFilename(
            mangaTitle,
            disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames.get(),
        )
    }

    /**
     * Returns the chapter directory name for a chapter.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query.
     * @param chapterUrl url of the chapter to query.
     */
    fun getChapterDirName(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        disallowNonAsciiFilenames: Boolean = libraryPreferences.disallowNonAsciiFilenames.get(),
        enableChapterNameHash: Boolean = libraryPreferences.enableChapterNameHash.get(),
    ): String {
        return ChapterDirNameParts(chapterName, chapterScanlator, chapterUrl)
            .dirName(disallowNonAsciiFilenames, enableChapterNameHash)
    }

    /**
     * Returns list of names that might have been previously used as
     * the directory name for a chapter.
     * Add to this list if naming pattern ever changes.
     */
    private fun getLegacyChapterDirNames(parts: ChapterDirNameParts): Set<String> {
        val chapterNameV1 = DiskUtil.buildValidFilename(
            when {
                !parts.chapterScanlator.isNullOrBlank() -> "${parts.chapterScanlator}_${parts.chapterName}"
                else -> parts.chapterName
            },
        )

        // Generate all possible legacy directory name variations by combining
        // different states of non-ASCII filenames and chapter name hash settings.
        // This ensures that chapters downloaded under any past configuration
        // combination can still be successfully found.
        val booleanPairPermutation = listOf(false to false, false to true, true to false, true to true)
        val othersChapterDirNames = booleanPairPermutation
            .map { (disallowNonAsciiFilenames, enableChapterNameHash) ->
                parts.dirName(disallowNonAsciiFilenames, enableChapterNameHash)
            }
        val splitChapterDirNames = booleanPairPermutation
            .map { (disallowNonAsciiFilenames, enableChapterNameHash) ->
                parts.splitDirName(disallowNonAsciiFilenames, enableChapterNameHash, sanitizeScanlator = true)
            }

        // Scanlators weren't sanitized for a while. One with a "/" nested its folder, so it was never found anyway.
        val scanlator = parts.chapterScanlator
        val unsanitizedScanlatorChapterDirNames = if (scanlator != null && '/' !in scanlator) {
            booleanPairPermutation.map { (disallowNonAsciiFilenames, enableChapterNameHash) ->
                parts.splitDirName(disallowNonAsciiFilenames, enableChapterNameHash, sanitizeScanlator = false)
            }
        } else {
            emptyList()
        }

        return buildSet {
            // Chapter name without hash (unable to handle duplicate
            // chapter names)
            add(chapterNameV1)
            addAll(othersChapterDirNames)
            addAll(splitChapterDirNames)
            addAll(unsanitizedScanlatorChapterDirNames)
        }
    }

    fun isChapterDirNameChanged(oldChapter: Chapter, newChapter: Chapter): Boolean {
        return getChapterDirName(oldChapter.name, oldChapter.scanlator, oldChapter.url) !=
            getChapterDirName(newChapter.name, newChapter.scanlator, newChapter.url)
    }

    /**
     * Returns valid downloaded chapter directory names.
     *
     * [isNovelChapter] is what keeps the novel text file out of a manga's answers. A name here is asked of a chapter
     * by name alone — nothing in "Capítulo 1" says whether the entry is prose or pixels — so accepting `.txt` for
     * every chapter would let a text file that happens to sit where a manga's chapter should be answer for a
     * chapter that was never downloaded, and the download that never happened could never be asked for again. It
     * cannot be read off the name, so the caller that is holding the chapter states it with the same rule
     * [eu.kanade.tachiyomi.ui.reader.novel.ChapterOpener.shouldOpenNovelReader] that decides where the chapter is
     * read and the downloader writes it. One rule, asked once, is what keeps writer and reader from disagreeing.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator the scanlator of the chapter to query.
     * @param chapterUrl the url of the chapter to query.
     * @param isNovelChapter whether this chapter is downloaded as one text file rather than as pages.
     */
    fun getValidChapterDirNames(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        isNovelChapter: Boolean = false,
    ): List<String> {
        val parts = ChapterDirNameParts(chapterName, chapterScanlator, chapterUrl)
        val chapterDirName = parts.dirName(
            disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames.get(),
            enableHash = libraryPreferences.enableChapterNameHash.get(),
        )
        val legacyChapterDirNames = getLegacyChapterDirNames(parts)
        val novelSuffix = ".${Downloader.NOVEL_CHAPTER_EXTENSION}"

        return buildList {
            // Folder of images
            add(chapterDirName)
            // Archived chapters
            add("$chapterDirName.cbz")
            // A novel chapter, downloaded as one file of prose
            if (isNovelChapter) add(chapterDirName + novelSuffix)

            // any legacy names
            legacyChapterDirNames.forEach {
                add(it)
                add("$it.cbz")
                if (isNovelChapter) add(it + novelSuffix)
            }
        }
    }
}

private const val LIST_CHAPTER_DIRS_MIN_CHAPTERS = 50

private const val CHAPTER_DIR_LOOKUP_PARALLELISM = 8

private val chapterDirLookupDispatcher = Dispatchers.IO.limitedParallelism(CHAPTER_DIR_LOOKUP_PARALLELISM)

/**
 * The parts of a chapter's directory name, each computed once however many name variations are built from them.
 */
private class ChapterDirNameParts(chapterName: String, val chapterScanlator: String?, private val chapterUrl: String) {

    /** The chapter name, or a placeholder when it's blank. */
    val chapterName = chapterName.ifBlank { "Chapter" }

    private val hashSuffix by lazy(LazyThreadSafetyMode.NONE) { "_${md5(chapterUrl).take(6)}" }

    private val sanitizedScanlator by lazy(LazyThreadSafetyMode.NONE) {
        chapterScanlator?.let { DiskUtil.buildValidFilename(it) }
    }

    private val validDirNames = arrayOfNulls<String>(2)

    private val validChapterNames = arrayOfNulls<String>(2)

    fun dirName(disallowNonAscii: Boolean, enableHash: Boolean): String {
        val index = if (disallowNonAscii) 1 else 0
        val dirName = validDirNames[index]
            // The scanlator and the name are made valid together, as every version before 0.21.0 did
            ?: DiskUtil.buildValidFilename(
                if (chapterScanlator.isNullOrBlank()) chapterName else "${chapterScanlator}_$chapterName",
                // Subtract 7 bytes for hash and underscore, 4 bytes for .cbz
                DiskUtil.MAX_FILE_NAME_BYTES - 11,
                disallowNonAscii,
            )
                .also { validDirNames[index] = it }
        return if (enableHash) dirName + hashSuffix else dirName
    }

    /**
     * The name 0.21.0 and 0.21.1 gave a chapter, which made only the chapter name valid and so differs for names
     * starting with a space or dot, long names, and non-ASCII scanlators.
     */
    fun splitDirName(disallowNonAscii: Boolean, enableHash: Boolean, sanitizeScanlator: Boolean): String {
        return buildString {
            if (!chapterScanlator.isNullOrBlank()) {
                // A "/" in the scanlator would otherwise nest the chapter in a folder of its own
                append(if (sanitizeScanlator) sanitizedScanlator else chapterScanlator)
                append("_")
            }
            append(validChapterName(disallowNonAscii))
            if (enableHash) append(hashSuffix)
        }
    }

    private fun validChapterName(disallowNonAscii: Boolean): String {
        val index = if (disallowNonAscii) 1 else 0
        return validChapterNames[index]
            // Subtract 7 bytes for hash and underscore, 4 bytes for .cbz
            ?: DiskUtil.buildValidFilename(chapterName, DiskUtil.MAX_FILE_NAME_BYTES - 11, disallowNonAscii)
                .also { validChapterNames[index] = it }
    }
}
