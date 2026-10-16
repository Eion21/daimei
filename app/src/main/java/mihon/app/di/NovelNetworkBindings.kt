package mihon.app.di

import app.novel.source.api.NovelNetwork
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Publishes the host's [NovelNetwork] so it can be injected where an extension is loaded.
 *
 * The load runs in [eu.kanade.tachiyomi.extension.ExtensionManager], which is a graph node. Giving the
 * network a binding here is what lets the manager receive it and hand it to the loader instead of the
 * loader reaching for a global singleton.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object NovelNetworkBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun novelNetwork(): NovelNetwork = HostNovelNetwork()
}
