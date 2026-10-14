package eu.kanade.tachiyomi.ui.reader.novel

import android.content.Context
import android.content.Intent
import android.os.Bundle
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.view.setComposeContent

/**
 * The text reader as a real destination, reachable from every place a chapter can be tapped.
 *
 * There is nothing here but the chapter id and [NovelReaderHost]. It mirrors the debug harness minus the
 * seeding: the harness exists to prove the reader works on a device, while this one is production wiring, and
 * production must not invent content. The chapter is loaded by [NovelReaderViewModel] from what the real
 * pipeline already stored, so what opens here is exactly what a library update wrote.
 */
class NovelReaderActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read once: the host keys its state on this id, and a rotation recreates the activity, so the value
        // cannot change while the instance lives.
        val chapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, NO_CHAPTER_ID)

        // The app's own extension, not `androidx.activity.compose.setContent`: only this one provides
        // `LocalMetroViewModelFactory`, which `metroViewModel()` needs to resolve [NovelReaderViewModel].
        setComposeContent {
            NovelReaderHost(chapterId = chapterId)
        }
    }

    companion object {

        /** Mirrors the single id the image reader passes, so the two activities take the same shape. */
        const val EXTRA_CHAPTER_ID = "chapterId"

        /**
         * What [NovelReaderHost] is given when the extra is missing. The view model then finds no stored
         * content and the reader renders its own empty state, which beats crashing on a null unboxing.
         */
        private const val NO_CHAPTER_ID = -1L

        fun newIntent(context: Context, chapterId: Long): Intent =
            Intent(context, NovelReaderActivity::class.java).apply {
                putExtra(EXTRA_CHAPTER_ID, chapterId)
                // Same flag `ReaderActivity.newIntent` uses: a reader already in the stack is replaced instead
                // of stacked again. Unlike `ReaderActivity`, this activity is not `singleTask`, so the
                // replacement is a fresh instance and therefore picks up this chapter id instead of redisplaying
                // the old chapter through an `onNewIntent` nobody handles.
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }
}
