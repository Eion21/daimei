package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.ContextWrapper

/**
 * The context an extension receives from the host: a package context for the extension's own apk, made safe to hand
 * to a library that asks it for its Application.
 *
 * `createPackageContext(pkgName, 0)` already scopes the context to the extension's own apk — its resources and assets
 * and none of the host's files, preferences, or databases. What it does not provide is a usable application context:
 * the value it returns wraps a `LoadedApk` with no `Application`, so `getApplicationContext()` is null here (and
 * throws when dereferenced). PdfBox is the concrete casualty: `PDFBoxResourceLoader.init(Context)` is
 * `ASSET_MANAGER = context.getApplicationContext().getAssets()`, so it reaches for that null application context the
 * moment an extension loads a bundled font or cmap.
 *
 * Overriding [getApplicationContext] to return `this` is the whole fix. The wrapped base is already the extension's
 * own package context, so `this` is exactly the value a caller wanted back, and its `assets` are the extension's own.
 * Nothing widens: the base stays a package context, and the host's application context is never handed to third-party
 * code. A wrapper that returned the host's context instead would expose the app's own files, preferences, and
 * databases to every extension, which is the leak this type exists to prevent.
 */
internal class ExtensionPackageContext(base: Context) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this
}
