package mihon.app.di

import app.novel.runtime.SourceRegistry
import app.novel.source.api.NovelSource
import app.novel.source.demo.DemoSource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Publishes the novel sources this build ships with.
 *
 * The novel modules stay free of Android and of any DI framework so they can be tested on a plain JVM. That means
 * the registry is assembled here, in the one module where Metro is already proven to work, instead of being
 * contributed from `novel-demo-source`.
 *
 * Adding a source is one more entry in [novelSources]; nothing else in the graph changes.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object NovelSourceBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun novelSources(): Set<NovelSource> = setOf(DemoSource())

    /**
     * Built eagerly from [novelSources] and scoped, so the registry is fully populated before anything that
     * injects it runs. `AndroidSourceManager` is also a singleton and takes this as a constructor parameter,
     * which is what forces the ordering.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun sourceRegistry(novelSources: Set<NovelSource>): SourceRegistry =
        SourceRegistry().apply { novelSources.forEach(::register) }
}
