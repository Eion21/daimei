package app.novel.source.api.android

import android.content.Context
import app.novel.source.api.NovelSource

/**
 * Implemented by an Android extension that needs its own [Context].
 *
 * The host checks for this interface right after it instantiates the extension class and, when it is present,
 * calls [bindContext] *before* the source is registered or otherwise reachable. Until that call the instance has
 * no context, so nothing may touch it in between.
 *
 * The [Context] handed over is created from the extension's own package, not the host's: the host performs the
 * equivalent of `createPackageContext` for the apk that declares the source. It therefore exposes only that
 * extension's resources and assets, and none of the host's files, preferences, or databases. An extension cannot
 * use it to reach into the app that loaded it, and one extension cannot see another.
 *
 * The context arrives through a method rather than a constructor parameter on purpose. The host creates the
 * extension by reflection with a public no-argument constructor, so a required constructor parameter would make
 * the class uninstantiable. Injecting the context after construction also keeps it out of any global or static
 * state, so the same apk can be loaded more than once without the instances sharing anything.
 *
 * This interface is Android-only — [Context] is an `android.content` type — which is why it lives in
 * `:novel-api-android` beside [NovelSource] rather than inside the plain-JVM `:novel-api` artifact that the
 * non-Android consumers depend on. It is optional and additive: an extension that does not implement it keeps
 * loading exactly as before, the same way [app.novel.source.api.NetworkedNovelSource] is optional.
 */
interface ContextAwareNovelSource : NovelSource {
    fun bindContext(context: Context)
}
