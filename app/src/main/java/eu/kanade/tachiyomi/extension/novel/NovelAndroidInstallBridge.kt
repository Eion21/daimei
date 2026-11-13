package eu.kanade.tachiyomi.extension.novel

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.InputStream

/**
 * Dedicated public Android transport. No Mihon preference, private extension or privileged installer.
 * There is intentionally NO invocation of PackageInstaller.Session.commit anywhere in this class.
 */
internal class NovelAndroidInstallBridge(
    private val context: Context,
    private val sdk: Int = Build.VERSION.SDK_INT,
) : NovelInstallSessionBridge {
    override val controlledTestActivation: Boolean get() = false

    override fun hasInstallPermission(): Boolean = context.packageManager.canRequestPackageInstalls()

    internal fun sessionParams(record: NovelInstallRecord): PackageInstaller.SessionParams {
        require(record.priorAbsent && record.explicitInstallConsent)
        return PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(record.packageName)
            setSize(record.artifactSize)
            if (requiresUserAction()) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
    }

    internal fun requiresUserAction(): Boolean = sdk >= 31

    override fun createSession(record: NovelInstallRecord): Int =
        context.packageManager.packageInstaller.createSession(sessionParams(record))

    override fun writeSession(
        sessionId: Int,
        source: InputStream,
        expectedSize: Long,
    ): NovelInstallStreamReceipt = context.packageManager.packageInstaller.openSession(sessionId).use { session ->
        session.openWrite("base.apk", 0, expectedSize).use { output ->
            val receipt = NovelInstallSessionStreaming.copy(source, output, expectedSize)
            session.fsync(output)
            receipt
        }
    }

    override fun ownsSession(sessionId: Int, packageName: String): Boolean =
        context.packageManager.packageInstaller.mySessions.any {
            it.sessionId == sessionId && it.appPackageName == packageName
        }

    /** Non-overridable cold barrier, even if someone calls the bridge without the coordinator. */
    override fun submit(record: NovelInstallRecord): NovelInstallDispatch = NovelInstallDispatch.DISABLED

    override fun abandonSession(sessionId: Int): Boolean = try {
        context.packageManager.packageInstaller.abandonSession(sessionId)
        true
    } catch (_: Exception) {
        false
    }

    internal fun callbackIntent(record: NovelInstallRecord): Intent {
        require(record.operationId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(record.nonce.matches(Regex("[0-9a-f]{64}")))
        return Intent(context, NovelInstallResultReceiver::class.java).apply {
            action = RESULT_ACTION
            data = Uri.parse("daimei-s39-install://result/${record.operationId}/${record.nonce}")
        }
    }

    internal companion object {
        const val RESULT_ACTION = "eu.kanade.tachiyomi.S39_INSTALL_RESULT"

        fun decodeCallback(context: Context, intent: Intent): NovelInstallCallback? {
            return try {
                val uri = intent.data
                if (intent.action != RESULT_ACTION || uri == null || uri.scheme != "daimei-s39-install" ||
                    uri.authority != "result" || uri.query != null || uri.fragment != null
                ) {
                    return null
                }
                val parts = uri.pathSegments
                if (parts.size != 2 || !parts[0].matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
                    !parts[1].matches(Regex("[0-9a-f]{64}"))
                ) {
                    return null
                }
                val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                val raw = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
                if (session < 0 || raw == Int.MIN_VALUE) return null
                val status = when (raw) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> NovelInstallCallbackStatus.PENDING_USER_ACTION
                    PackageInstaller.STATUS_SUCCESS -> NovelInstallCallbackStatus.SUCCESS
                    PackageInstaller.STATUS_FAILURE_ABORTED -> NovelInstallCallbackStatus.CANCELLED
                    else -> NovelInstallCallbackStatus.FAILURE
                }
                // API26-compatible public getter. Deprecation is not signature verification evidence.
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                val action = confirmation?.let { supplied ->
                    NovelInstallUserConfirmation {
                        val info = context.packageManager.resolveActivity(supplied, PackageManager.MATCH_DEFAULT_ONLY)
                            ?.activityInfo
                        val flags = info?.applicationInfo?.flags ?: 0
                        if (info == null || flags and
                            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                        ) {
                            false
                        } else {
                            // Correlated private receiver data is never launched automatically.
                            val copy = Intent(supplied).apply {
                                setClassName(info.packageName, info.name)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(copy)
                            true
                        }
                    }
                }
                NovelInstallCallback(parts[0], parts[1], session, status, action)
            } catch (_: Exception) {
                null
            }
        }
    }
}
