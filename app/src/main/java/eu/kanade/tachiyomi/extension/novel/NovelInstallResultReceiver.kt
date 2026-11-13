package eu.kanade.tachiyomi.extension.novel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Explicit, non-exported Android callback endpoint; decoding never executes an APK or grants trust. */
class NovelInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callback = NovelAndroidInstallBridge.decodeCallback(context, intent) ?: return
        NovelInteractiveInstallRuntime.get(context).onCallback(callback)
    }
}
