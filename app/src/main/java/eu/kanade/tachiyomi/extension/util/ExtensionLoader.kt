package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import app.novel.source.api.NetworkedNovelSource
import app.novel.source.api.NovelNetwork
import app.novel.source.api.NovelSource
import app.novel.source.api.android.ContextAwareNovelSource
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.novel.NovelSourceAdapter
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.util.storage.copyAndSetReadOnlyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.data.dalvik.DelegateLastClassLoaderCompat
import mihon.domain.extension.model.ContentWarning
import mihon.domain.extension.model.ExtensionStore
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Class that handles the loading of the extensions. Supports two kinds of extensions:
 *
 * 1. Shared extension: This extension is installed to the system with package
 * installer, so other variants of Tachiyomi and its forks can also use this extension.
 *
 * 2. Private extension: This extension is put inside private data directory of the
 * running app, so this extension can only be used by the running app and not shared
 * with other apps.
 *
 * When both kinds of extensions are installed with a same package name, shared
 * extension will be used unless the version codes are different. In that case the
 * one with higher version code will be used.
 */
internal object ExtensionLoader {

    private const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
    private const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
    private const val METADATA_NSFW = "tachiyomi.extension.nsfw"

    private const val METADATA_NAME = "tachiyomix.name"
    private const val METADATA_EXTENSION_LIB = "tachiyomix.extensionLib"
    private const val METADATA_CONTENT_WARNING = "tachiyomix.contentWarning"

    private val SUPPORTED_LIB_VERSIONS = listOf(1.4, 1.6)

    @Suppress("DEPRECATION")
    private val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
        PackageManager.GET_META_DATA or
        PackageManager.GET_SIGNATURES or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)

    private const val PRIVATE_EXTENSION_EXTENSION = "ext"

    /**
     * The host network this loader injects into extension sources that ask for it, installed once at
     * startup by [eu.kanade.tachiyomi.extension.ExtensionManager] before any load can run.
     *
     * It lives here rather than being threaded through as a parameter because the install receiver
     * reaches the loader through `loadExtensionFromPkgName`, which no caller can hand a network to.
     * Holding the host's own network handle is not the static state decision D2 rules out: nothing
     * static is exposed to an extension, and the same apk loading twice shares one stateless client.
     */
    @Volatile
    private var novelNetwork: NovelNetwork? = null

    fun installNovelNetwork(network: NovelNetwork) {
        novelNetwork = network
    }

    private fun getPrivateExtensionDir(context: Context) = File(context.filesDir, "exts")

    fun installPrivateExtensionFile(context: Context, file: File, extension: PackageInfo) {
        check(isPackageAnExtension(extension)) { "${extension.packageName} isn't an extension" }
        val currentExtension = getExtensionPackageInfoFromPkgName(context, extension.packageName)

        if (currentExtension != null) {
            check(
                PackageInfoCompat.getLongVersionCode(extension) >=
                    PackageInfoCompat.getLongVersionCode(currentExtension),
            ) { "The installed version is newer, and downgrading isn't allowed" }

            val extensionSignatures = getSignatures(extension)
            check(!extensionSignatures.isNullOrEmpty()) { "The extension isn't signed" }
            check(extensionSignatures.containsAll(getSignatures(currentExtension)!!)) {
                "It isn't signed with the same key as the installed extension"
            }
        }

        val target = File(getPrivateExtensionDir(context), "${extension.packageName}.$PRIVATE_EXTENSION_EXTENSION")
        try {
            target.delete()
            file.copyAndSetReadOnlyTo(target, overwrite = true)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        if (currentExtension != null) {
            ExtensionInstallReceiver.notifyReplaced(context, extension.packageName)
        } else {
            ExtensionInstallReceiver.notifyAdded(context, extension.packageName)
        }
    }

    fun uninstallPrivateExtension(context: Context, pkgName: String) {
        File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION").delete()
    }

    fun getArchivePackageInfo(context: Context, file: File): PackageInfo? {
        return context.packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
    }

    /**
     * Return a list of all the available extensions initialized concurrently.
     *
     * @param context The application context.
     * @param alreadyLoaded Extensions loaded by an earlier call. Any of these whose apk is unchanged
     * and which still passes every check is returned as is, so its sources keep working and its
     * update status survives. Pass nothing to load every extension from scratch.
     */
    suspend fun loadExtensions(
        context: Context,
        alreadyLoaded: Map<String, Extension.Loaded> = emptyMap(),
    ): List<Extension.Installed> {
        val trustExtension = context.appGraph.trustExtension
        val sourcePreferences = context.appGraph.sourcePreferences
        val stores = context.appGraph.extensionStoreRepository.getAllAsFlow().first()
        val enabledContentWarnings = sourcePreferences.enabledContentWarnings.get()
        val applyContentWarningsToInstalled = sourcePreferences.applyContentWarningsToInstalled.get()

        val pkgManager = context.packageManager

        val installedPkgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pkgManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()))
        } else {
            pkgManager.getInstalledPackages(PACKAGE_FLAGS)
        }

        val sharedExtPkgs = installedPkgs
            .asSequence()
            .filter { isPackageAnExtension(it) }
            .map { ExtensionInfo(packageInfo = it, isShared = true) }

        val privateExtPkgs = getPrivateExtensionDir(context)
            .listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension == PRIVATE_EXTENSION_EXTENSION }
            ?.mapNotNull {
                // Just in case, since Android 14+ requires them to be read-only
                if (it.canWrite()) {
                    it.setReadOnly()
                }

                val path = it.absolutePath
                pkgManager.getPackageArchiveInfo(path, PACKAGE_FLAGS)
                    ?.also { pkg -> pkg.applicationInfo?.fixBasePaths(path) }
            }
            ?.filter { isPackageAnExtension(it) }
            ?.map { ExtensionInfo(packageInfo = it, isShared = false) }
            ?.toList()
            .orEmpty()
        val privateExtPkgsByName = privateExtPkgs.groupBy { it.packageInfo.packageName }

        val extPkgs = (sharedExtPkgs + privateExtPkgs)
            // Remove duplicates. Shared takes priority than private by default
            .distinctBy { it.packageInfo.packageName }
            // Compare version number
            .mapNotNull { sharedPkg ->
                val privatePkg = privateExtPkgsByName[sharedPkg.packageInfo.packageName]?.singleOrNull()
                selectExtensionPackage(sharedPkg, privatePkg)
            }
            .toList()

        if (extPkgs.isEmpty()) return emptyList()

        // Load each extension concurrently and wait for completion
        return withContext(Dispatchers.IO) {
            extPkgs
                .map {
                    async {
                        loadExtensionCatching(
                            context = context,
                            extensionInfo = it,
                            trustExtension = trustExtension,
                            stores = stores,
                            enabledContentWarnings = enabledContentWarnings,
                            applyContentWarningsToInstalled = applyContentWarningsToInstalled,
                            alreadyLoaded = alreadyLoaded[it.packageInfo.packageName],
                        )
                    }
                }
                .awaitAll()
        }
    }

    /**
     * Attempts to load an extension from the given package name. It checks if the extension
     * contains the required feature flag before trying to load it.
     */
    suspend fun loadExtensionFromPkgName(context: Context, pkgName: String): Extension.Installed? {
        val extensionPackage = getExtensionInfoFromPkgName(context, pkgName)
        if (extensionPackage == null) {
            logcat(LogPriority.ERROR) { "Extension package is not found ($pkgName)" }
            return null
        }

        val sourcePreferences = context.appGraph.sourcePreferences
        return loadExtensionCatching(
            context = context,
            extensionInfo = extensionPackage,
            trustExtension = context.appGraph.trustExtension,
            stores = context.appGraph.extensionStoreRepository.getAll(),
            enabledContentWarnings = sourcePreferences.enabledContentWarnings.get(),
            applyContentWarningsToInstalled = sourcePreferences.applyContentWarningsToInstalled.get(),
        )
    }

    fun getExtensionPackageInfoFromPkgName(context: Context, pkgName: String): PackageInfo? {
        return getExtensionInfoFromPkgName(context, pkgName)?.packageInfo
    }

    private fun getExtensionInfoFromPkgName(context: Context, pkgName: String): ExtensionInfo? {
        val privateExtensionFile = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
        val privatePkg = if (privateExtensionFile.isFile) {
            context.packageManager.getPackageArchiveInfo(privateExtensionFile.absolutePath, PACKAGE_FLAGS)
                ?.takeIf { isPackageAnExtension(it) }
                ?.let {
                    it.applicationInfo?.fixBasePaths(privateExtensionFile.absolutePath)
                    ExtensionInfo(
                        packageInfo = it,
                        isShared = false,
                    )
                }
        } else {
            null
        }

        val sharedPkg = try {
            context.packageManager.getPackageInfo(pkgName, PACKAGE_FLAGS)
                .takeIf { isPackageAnExtension(it) }
                ?.let {
                    ExtensionInfo(
                        packageInfo = it,
                        isShared = true,
                    )
                }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

        return selectExtensionPackage(sharedPkg, privatePkg)
    }

    /**
     * [loadExtension] reports the failures it knows how to name, but an apk can be malformed in
     * ways it doesn't check for. Keep anything unforeseen to the extension that caused it instead of
     * letting it take down the load of every other extension.
     */
    private suspend fun loadExtensionCatching(
        context: Context,
        extensionInfo: ExtensionInfo,
        trustExtension: TrustExtension,
        stores: List<ExtensionStore>,
        enabledContentWarnings: Set<ContentWarning>,
        applyContentWarningsToInstalled: Boolean,
        alreadyLoaded: Extension.Loaded? = null,
    ): Extension.Installed {
        return try {
            loadExtension(
                context = context,
                extensionInfo = extensionInfo,
                trustExtension = trustExtension,
                stores = stores,
                enabledContentWarnings = enabledContentWarnings,
                applyContentWarningsToInstalled = applyContentWarningsToInstalled,
                alreadyLoaded = alreadyLoaded,
            )
        } catch (e: Throwable) {
            val pkgInfo = extensionInfo.packageInfo
            logcat(LogPriority.ERROR, e) { "Extension load error: ${pkgInfo.packageName}" }
            val signatures = getSignatures(pkgInfo).orEmpty()
            Extension.NotLoaded(
                name = pkgInfo.packageName,
                pkgName = pkgInfo.packageName,
                versionName = pkgInfo.versionName.orEmpty(),
                versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo),
                isShared = extensionInfo.isShared,
                contentWarning = ContentWarning.SAFE,
                signatures = signatures,
                store = stores.firstOrNull { it.signingKey in signatures },
                reason = Extension.NotLoaded.Reason.Failed(e.rootMessage, e.stackTraceToString()),
            )
        }
    }

    /**
     * Loads an extension
     *
     * @param context The application context.
     * @param extensionInfo The extension to load.
     */
    private suspend fun loadExtension(
        context: Context,
        extensionInfo: ExtensionInfo,
        trustExtension: TrustExtension,
        stores: List<ExtensionStore>,
        enabledContentWarnings: Set<ContentWarning>,
        applyContentWarningsToInstalled: Boolean,
        alreadyLoaded: Extension.Loaded? = null,
    ): Extension.Installed {
        val pkgManager = context.packageManager
        val pkgInfo = extensionInfo.packageInfo
        val appInfo = pkgInfo.applicationInfo
        val metaData = appInfo?.metaData
        val pkgName = pkgInfo.packageName

        val extName = metaData?.getString(METADATA_NAME)
            ?: appInfo?.let { pkgManager.getApplicationLabel(it).toString().substringAfter("Tachiyomi: ") }
            ?: pkgName
        val versionName = pkgInfo.versionName
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)
        val signatures = getSignatures(pkgInfo).orEmpty()
        val store = stores.firstOrNull { it.signingKey in signatures }
        val contentWarning = when {
            metaData == null -> ContentWarning.SAFE
            metaData.containsKey(METADATA_CONTENT_WARNING) -> {
                when (metaData.getInt(METADATA_CONTENT_WARNING)) {
                    1 -> ContentWarning.MIXED
                    2 -> ContentWarning.NSFW
                    else -> ContentWarning.SAFE
                }
            }
            metaData.getInt(METADATA_NSFW) == 1 -> ContentWarning.NSFW
            else -> ContentWarning.SAFE
        }

        fun notLoaded(
            reason: Extension.NotLoaded.Reason,
            libVersion: Double? = null,
        ) = Extension.NotLoaded(
            name = extName,
            pkgName = pkgName,
            versionName = versionName.orEmpty(),
            versionCode = versionCode,
            isShared = extensionInfo.isShared,
            contentWarning = contentWarning,
            signatures = signatures,
            store = store,
            libVersion = libVersion,
            reason = reason,
        )

        if (appInfo == null || metaData == null) {
            logcat(LogPriority.WARN) { "Missing application info for extension $extName" }
            return notLoaded(Extension.NotLoaded.Reason.Malformed)
        }

        if (versionName.isNullOrEmpty()) {
            logcat(LogPriority.WARN) { "Missing versionName for extension $extName" }
            return notLoaded(Extension.NotLoaded.Reason.Malformed)
        }

        // Which contract this apk was built against decides both the version list and the source-class metadata key.
        // A package that reached here without a known feature is malformed rather than an extension.
        val kind = extensionKindOf(pkgInfo.reqFeatures.orEmpty().mapNotNull { it.name })
        if (kind == null) {
            logcat(LogPriority.WARN) { "Package $pkgName isn't a known extension type" }
            return notLoaded(Extension.NotLoaded.Reason.Malformed)
        }

        // Validate the contract version. A novel extension declares its own key against its own list, mirroring how
        // the manga key is read against SUPPORTED_LIB_VERSIONS; neither list can move the other.
        val libVersion = when (kind) {
            ExtensionKind.MANGA ->
                metaData.getFloat(METADATA_EXTENSION_LIB)
                    .takeUnless { it == 0.0f }
                    ?.toString()
                    ?.toDouble()
                    ?: versionName.substringBeforeLast('.').toDoubleOrNull()
            ExtensionKind.NOVEL ->
                contractVersionFromMetadata(metaData.getFloat(METADATA_NOVEL_API_VERSION))
        }
        val supportedVersions = when (kind) {
            ExtensionKind.MANGA -> SUPPORTED_LIB_VERSIONS
            ExtensionKind.NOVEL -> SUPPORTED_NOVEL_API_VERSIONS
        }
        val supportedVersion = libVersion != null && libVersion in supportedVersions

        val hasSignatures = signatures.isNotEmpty()
        val trusted = hasSignatures && trustExtension.isTrusted(pkgInfo, signatures, stores)
        val contentWarningAllowed = !applyContentWarningsToInstalled || contentWarning in enabledContentWarnings

        // The gate is decided in one place, in a fixed order, and it is the ONLY thing that lets the load continue
        // to the classloader below. The novel branch is added beside this gate, never before it and never instead of
        // it: nothing here instantiates a class, novel or manga, without a present and trusted signature.
        when (
            decideExtensionGate(
                supportedVersion = supportedVersion,
                hasSignatures = hasSignatures,
                isTrusted = trusted,
                contentWarningAllowed = contentWarningAllowed,
            )
        ) {
            ExtensionGateDecision.UNSUPPORTED_VERSION -> {
                logcat(LogPriority.WARN) {
                    "Lib version is $libVersion, while only version(s) ${supportedVersions.joinToString()} are supported"
                }
                return notLoaded(Extension.NotLoaded.Reason.UnsupportedLibVersion, libVersion)
            }
            ExtensionGateDecision.UNSIGNED -> {
                logcat(LogPriority.WARN) { "Package $pkgName isn't signed" }
                return notLoaded(Extension.NotLoaded.Reason.Unsigned, libVersion)
            }
            ExtensionGateDecision.UNTRUSTED -> {
                logcat(LogPriority.WARN) { "Extension $pkgName isn't trusted" }
                return notLoaded(Extension.NotLoaded.Reason.Untrusted(signatures.last()), libVersion)
            }
            ExtensionGateDecision.FILTERED -> {
                logcat(LogPriority.WARN) { "Extension $pkgName with $contentWarning not allowed" }
                return notLoaded(Extension.NotLoaded.Reason.Filtered, libVersion)
            }
            ExtensionGateDecision.ALLOWED -> Unit
        }

        // The gate refuses to continue without a version it supports, so the parse above cannot have been null here.
        val extensionLibVersion = requireNotNull(libVersion)

        // Everything above is cheap to check again, everything below isn't. Nothing about this apk
        // changed and it still passes, so keep the sources that are already registered for it.
        if (alreadyLoaded != null &&
            alreadyLoaded.versionCode == versionCode &&
            alreadyLoaded.isShared == extensionInfo.isShared
        ) {
            return alreadyLoaded.copy(store = store)
        }

        val classLoader = try {
            DelegateLastClassLoaderCompat(appInfo.sourceDir, null, context.classLoader)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Extension load error: $extName ($pkgName)" }
            return notLoaded(Extension.NotLoaded.Reason.Failed(e.rootMessage, e.stackTraceToString()), libVersion)
        }

        // Each kind carries its source class under its own key, so a novel apk cannot be read as a manga one.
        val sourceClassMetadata = when (kind) {
            ExtensionKind.MANGA -> METADATA_SOURCE_CLASS
            ExtensionKind.NOVEL -> METADATA_NOVEL_SOURCE_CLASS
        }
        val sourceClasses = metaData.getString(sourceClassMetadata)
        if (sourceClasses.isNullOrBlank()) {
            logcat(LogPriority.WARN) { "Missing source class for extension $extName" }
            return notLoaded(Extension.NotLoaded.Reason.Malformed, libVersion)
        }

        val sources = sourceClasses
            .split(";")
            .map {
                val sourceClass = it.trim()
                if (sourceClass.startsWith(".")) {
                    pkgInfo.packageName + sourceClass
                } else {
                    sourceClass
                }
            }
            .flatMap {
                try {
                    val obj = Class.forName(it, false, classLoader).getDeclaredConstructor().newInstance()

                    // Bound here on purpose: after construction and before the object is wrapped, returned and
                    // registered in SourceRegistry. A NetworkedNovelSource has no network until bindNetwork is
                    // called, so anything that reached it earlier — the first popular() of a source registered in
                    // that gap — would fail. Binding at the single instantiation site is what makes every later
                    // path safe, and it is reached only after the gate above proved the apk signed and trusted.
                    // A source that does not implement the interface (a 1.0 apk, or one that brings its own stack)
                    // is left exactly as it is — decision D5.
                    if (obj is NetworkedNovelSource) {
                        val network = novelNetwork
                            ?: error("The host network was not installed before loading $it")
                        obj.bindNetwork(network)
                    }

                    // A novel extension that needs the Android context declares ContextAwareNovelSource, which the host
                    // detects with `is` against its own copy of the interface. The interface ships on the host
                    // classpath, so an extension must depend on :novel-api-android as compileOnly; an apk that bundled
                    // its own copy would implement a different class, be missed by `is`, and silently never receive a
                    // context. Detect that and name it here, rather than letting it surface later as an unexplained
                    // null far from its cause. The extension still loads — only its optional context is missing — so
                    // this reports instead of rejecting: rejection is for a load that cannot work at all.
                    val declaredContextInterface = try {
                        Class.forName(ContextAwareNovelSource::class.java.name, false, classLoader)
                    } catch (_: Throwable) {
                        null
                    }
                    if (declaredContextInterface != null &&
                        declaredContextInterface !== ContextAwareNovelSource::class.java
                    ) {
                        logcat(LogPriority.ERROR) {
                            "Extension $extName ($pkgName) bundles its own copy of the context interface; " +
                                "the host will not inject a context. Depend on :novel-api-android as compileOnly."
                        }
                    }

                    // Bound here beside bindNetwork, and for the same reason: after construction and before the object
                    // is wrapped, returned and registered. The context is the extension's own package, never the host's,
                    // so an extension cannot reach into the app that loaded it, and the wrapper makes it usable by a
                    // library that asks for the application context (PdfBox among them). Injected only when the
                    // extension asks for it: a 1.0–1.2 apk, or one that does not implement the interface, is left
                    // exactly as it is, the same way bindNetwork leaves it.
                    if (obj is ContextAwareNovelSource) {
                        val extensionContext = try {
                            ExtensionPackageContext(context.createPackageContext(pkgName, 0))
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e) {
                                "Extension $extName ($pkgName) needs an Android context, and the host could not " +
                                    "create one for this apk"
                            }
                            return notLoaded(contextUnavailableReason(), libVersion)
                        }
                        obj.bindContext(extensionContext)
                    }

                    when (obj) {
                        // A novel extension declares a NovelSource, which is neither a Source nor a SourceFactory.
                        // The adapter turns it into the Source everything downstream already consumes, and it is
                        // built only here, after the signature gate: the third-party class is instantiated strictly
                        // after it has been proven signed and trusted.
                        is NovelSource -> listOf(NovelSourceAdapter(obj))
                        is Source -> listOf(obj)
                        is SourceFactory -> obj.createSources()
                        else -> throw Exception("Unknown source class type: ${obj.javaClass}")
                    }
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "Extension load error: $extName ($it)" }
                    return notLoaded(
                        Extension.NotLoaded.Reason.Failed(e.rootMessage, e.stackTraceToString()),
                        libVersion,
                    )
                }
            }

        val langs = sources.map { it.lang }.toSet()
        val lang = when (langs.size) {
            0 -> ""
            1 -> langs.first()
            else -> "all"
        }

        return Extension.Loaded(
            name = extName,
            pkgName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            libVersion = extensionLibVersion,
            lang = lang,
            contentWarning = contentWarning,
            sources = sources,
            pkgFactory = metaData.getString(METADATA_SOURCE_FACTORY),
            isShared = extensionInfo.isShared,
            signatures = signatures,
            store = store,
        )
    }

    /**
     * Choose which extension package to use based on version code
     *
     * @param shared extension installed to system
     * @param private extension installed to data directory
     */
    private fun selectExtensionPackage(shared: ExtensionInfo?, private: ExtensionInfo?): ExtensionInfo? {
        when {
            private == null && shared != null -> return shared
            shared == null && private != null -> return private
            shared == null && private == null -> return null
        }

        return if (PackageInfoCompat.getLongVersionCode(shared!!.packageInfo) >=
            PackageInfoCompat.getLongVersionCode(private!!.packageInfo)
        ) {
            shared
        } else {
            private
        }
    }

    /**
     * Returns true if the given package is an extension.
     *
     * @param pkgInfo The package info of the application.
     */
    private fun isPackageAnExtension(pkgInfo: PackageInfo): Boolean {
        return isEligibleExtensionForDiscovery(pkgInfo.reqFeatures.orEmpty().map { it.name })
    }

    /**
     * Returns the signatures of the package or null if it's not signed.
     *
     * @param pkgInfo The package info of the application.
     * @return List SHA256 digest of the signatures
     */
    fun getSignatures(pkgInfo: PackageInfo): List<String>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo
            when {
                signingInfo == null -> null
                signingInfo.hasMultipleSigners() -> signingInfo.apkContentsSigners
                else -> signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        }
            ?.map { Hash.sha256(it.toByteArray()) }
            ?.toList()
    }

    /**
     * On Android 13+ the ApplicationInfo generated by getPackageArchiveInfo doesn't
     * have sourceDir which breaks assets loading (used for getting icon here).
     */
    private fun ApplicationInfo.fixBasePaths(apkPath: String) {
        if (sourceDir == null) {
            sourceDir = apkPath
        }
        if (publicSourceDir == null) {
            publicSourceDir = apkPath
        }
    }

    private data class ExtensionInfo(
        val packageInfo: PackageInfo,
        val isShared: Boolean,
    )
}

/** The feature a manga extension apk declares. */
internal const val MANGA_EXTENSION_FEATURE = "tachiyomi.extension"

/** The feature a novel extension apk declares. */
internal const val NOVEL_EXTENSION_FEATURE = "app.novel.extension"

/** Metadata key holding the fully-qualified name of the class implementing [NovelSource]. */
internal const val METADATA_NOVEL_SOURCE_CLASS = "app.novel.extension.class"

/** Metadata key holding the novel contract version the apk was built against. */
internal const val METADATA_NOVEL_API_VERSION = "app.novel.extensionApi"

/**
 * The novel contract versions this app can run, the counterpart of [ExtensionLoader.SUPPORTED_LIB_VERSIONS] but its
 * own list under its own key, so an incompatible change to `:novel-api` can raise this version without that being a
 * manga extension change. Built the same way: whatever is listed here is what the loader accepts.
 *
 * Every earlier version stays in the list beside the current one because each raise has been additive (decision D3):
 * the host network at 1.1, the byte fetch at 1.2, and the optional Android context at 1.3 were all added beside what
 * already existed, so an extension already installed at 1.0, 1.1, or 1.2 must keep loading. Only remove a version
 * when an apk built against it no longer links.
 */
internal val SUPPORTED_NOVEL_API_VERSIONS = listOf(1.0, 1.1, 1.2, 1.3)

/** The two kinds of extension apk this app can load. */
internal enum class ExtensionKind { MANGA, NOVEL }

/**
 * Which kind of extension [featureNames] describes, or null when it is not an extension at all.
 *
 * A package declaring both features is treated as a manga extension: the manga path existed first, and preferring it
 * is what keeps that path exactly as it was when the novel feature is added beside it.
 */
internal fun extensionKindOf(featureNames: Collection<String>): ExtensionKind? = when {
    MANGA_EXTENSION_FEATURE in featureNames -> ExtensionKind.MANGA
    NOVEL_EXTENSION_FEATURE in featureNames -> ExtensionKind.NOVEL
    else -> null
}

/**
 * Whether [name] is one of the extension features this app knows.
 *
 * [name] is nullable on purpose. `FeatureInfo.name` is a platform type that can be absent, and the comparison this
 * replaced treated an absent name as "not an extension". Requiring non-null here once crashed the whole extension
 * list on a device that carried a feature with no name: one malformed feature took down every extension, manga
 * included, because the failure happened while filtering the list rather than while examining one package.
 */
internal fun isExtensionFeature(name: String?): Boolean =
    name == MANGA_EXTENSION_FEATURE || name == NOVEL_EXTENSION_FEATURE

/** Feature eligibility shared by package discovery and package-name lookup. */
internal fun isEligibleExtensionForDiscovery(featureNames: Collection<String?>): Boolean =
    extensionKindOf(featureNames.filterNotNull()) == ExtensionKind.NOVEL

/**
 * The contract version [raw] names, or null when the metadata did not carry one. Android reports an absent float
 * metadata value as 0, so 0 is "missing" rather than a version anyone can declare.
 */
internal fun contractVersionFromMetadata(raw: Float): Double? = raw.takeUnless { it == 0.0f }?.toString()?.toDouble()

/** Whether [version] is a novel contract version this app can run. */
internal fun isSupportedNovelApiVersion(version: Double?): Boolean =
    version != null && version in SUPPORTED_NOVEL_API_VERSIONS

/**
 * The loader's decision, before it builds a classloader or instantiates any third-party class.
 *
 * The order is the contract and must not change:
 *
 * 1. an unsupported contract version is refused first — an apk built against an interface this app cannot run is
 *    not worth any further work;
 * 2. a missing signature is refused next, and a present-but-untrusted one after it. These two are the boundary
 *    between running third-party code and not running it, so nothing past them — in particular the novel branch —
 *    may be reached without passing through them.
 *
 * The novel case supplies its feature and its version key earlier, but it flows through this same gate: there is no
 * value of [ExtensionKind] that lets a package reach [ALLOWED] without `hasSignatures && isTrusted`.
 */
internal enum class ExtensionGateDecision { UNSUPPORTED_VERSION, UNSIGNED, UNTRUSTED, FILTERED, ALLOWED }

internal fun decideExtensionGate(
    supportedVersion: Boolean,
    hasSignatures: Boolean,
    isTrusted: Boolean,
    contentWarningAllowed: Boolean,
): ExtensionGateDecision = when {
    !supportedVersion -> ExtensionGateDecision.UNSUPPORTED_VERSION
    !hasSignatures -> ExtensionGateDecision.UNSIGNED
    !isTrusted -> ExtensionGateDecision.UNTRUSTED
    !contentWarningAllowed -> ExtensionGateDecision.FILTERED
    else -> ExtensionGateDecision.ALLOWED
}

/**
 * The reason a context-capable extension is reported with when the host could not create a context for it.
 *
 * Contract: a context failure is reported as [Extension.NotLoaded.Reason.ContextUnavailable] and never as the generic
 * [Extension.NotLoaded.Reason.Failed]. `Failed` means the extension's own code threw while loading; a context the host
 * could not create is a finding about the apk, and it needs its own message and its own fix. Keeping the mapping in one
 * reachable function is what stops the two from quietly merging back together.
 *
 * Nothing about the failure rides on the reason, which is why it takes no parameter: what `createPackageContext` threw
 * — `NameNotFoundException` for an apk the host never registered, most importantly a private `.ext` file — does not
 * change the fix, and [Extension.NotLoaded.Reason.ContextUnavailable] carries no message of its own.
 */
internal fun contextUnavailableReason(): Extension.NotLoaded.Reason =
    Extension.NotLoaded.Reason.ContextUnavailable

/**
 * The message of the deepest cause, which is the one that actually says what went wrong.
 */
private val Throwable.rootMessage: String
    get() {
        val root = generateSequence(this) { it.cause }.last()
        return listOfNotNull(root::class.simpleName, root.message).joinToString(": ")
    }
