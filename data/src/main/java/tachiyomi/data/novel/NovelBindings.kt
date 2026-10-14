package tachiyomi.data.novel

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import java.io.File

@ContributesTo(AppScope::class)
@BindingContainer
object NovelBindings {

    /**
     * Chapter content is stored on disk as JSON and only its path goes in the database, so it lives in the
     * cache directory where the platform can reclaim it. Downloads are re-fetchable from the source.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun providesNovelContentDir(context: Context): NovelContentDir =
        NovelContentDir(File(context.cacheDir, "novel_content"))
}