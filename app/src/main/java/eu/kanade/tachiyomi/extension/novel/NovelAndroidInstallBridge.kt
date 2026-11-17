package eu.kanade.tachiyomi.extension.novel

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import eu.kanade.tachiyomi.BuildConfig
import java.io.InputStream

/** Default construction is cold; only pinned QA or official public activation can dispatch. */
internal class NovelAndroidInstallBridge private constructor(
    private val context: Context,
    private val sdk: Int,
    private val experiment: NovelInstallExperiment?,
    private val activation: NovelInstallActivation?,
) : NovelInstallSessionBridge {
    constructor(context: Context, sdk: Int = Build.VERSION.SDK_INT) : this(context, sdk, null, null)

    private data class Binding(
        val record: NovelInstallRecord,
        val receipt: NovelInstallStreamReceipt? = null,
        val committed: Boolean = false,
    )
    private val bindings = mutableMapOf<Int, Binding>()
    override val controlledTestActivation: Boolean get() = experiment?.active == true || activation?.active == true

    /** True only when the official public activation is active for this host. */
    val publicActivationActive: Boolean get() = activation?.active == true

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

    @Synchronized
    override fun createSession(record: NovelInstallRecord): Int {
        require(experiment?.permits(record) == true || activation?.permits(record) == true)
        val id = context.packageManager.packageInstaller.createSession(sessionParams(record))
        require(id >= 0 && id !in bindings)
        bindings[id] = Binding(record.copy(currentSigners = record.currentSigners.toSet()))
        return id
    }

    override fun writeSession(
        sessionId: Int,
        source: InputStream,
        expectedSize: Long,
    ): NovelInstallStreamReceipt {
        val binding = if (controlledTestActivation) synchronized(this) {
            requireNotNull(bindings[sessionId]).also { require(!it.committed && it.receipt == null && it.record.artifactSize == expectedSize) }
        } else {
            null
        }
        val receipt = context.packageManager.packageInstaller.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, expectedSize).use { output ->
                val written = NovelInstallSessionStreaming.copy(source, output, expectedSize)
                session.fsync(output)
                written
            }
        }
        if (binding != null) synchronized(this) {
            require(bindings[sessionId] == binding && receipt.size == binding.record.artifactSize &&
                receipt.sha256 == binding.record.artifactSha256)
            bindings[sessionId] = binding.copy(receipt = receipt)
        }
        return receipt
    }

    override fun ownsSession(sessionId: Int, packageName: String): Boolean =
        synchronized(this) {
            bindings[sessionId]?.record?.packageName == packageName &&
                context.packageManager.packageInstaller.mySessions.any {
                    it.sessionId == sessionId && it.appPackageName == packageName
                }
        }

    @Synchronized
    override fun submit(record: NovelInstallRecord): NovelInstallDispatch {
        if (!controlledTestActivation) return NovelInstallDispatch.DISABLED
        val id = record.sessionId ?: return NovelInstallDispatch.DISABLED
        val binding = bindings[id] ?: return NovelInstallDispatch.DISABLED
        val original = binding.record
        val valid = (experiment?.permits(record) == true || activation?.permits(record) == true) && record.commitRequested &&
            record.phase == NovelInstallPhase.COMMITTING && record.operationId == original.operationId &&
            record.nonce == original.nonce && record.packageName == original.packageName &&
            record.versionCode == original.versionCode && record.currentSigners == original.currentSigners &&
            record.artifactPath == original.artifactPath && record.artifactSize == original.artifactSize &&
            record.artifactSha256 == original.artifactSha256 && record.streamedSha256 == record.artifactSha256 &&
            !binding.committed && binding.receipt == NovelInstallStreamReceipt(record.artifactSize, record.artifactSha256) &&
            ownsSession(id, record.packageName)
        if (!valid) return NovelInstallDispatch.DISABLED
        // Consume the receipt before touching Android, retaining only process-local cleanup ownership.
        bindings[id] = binding.copy(receipt = null, committed = true)
        return try {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (sdk >= 31) PendingIntent.FLAG_MUTABLE else 0
            val callback = PendingIntent.getBroadcast(context, 0, callbackIntent(record), flags)
            context.packageManager.packageInstaller.openSession(id).use { it.commit(callback.intentSender) }
            NovelInstallDispatch.SUBMITTED
        } catch (_: Exception) {
            abandonSession(id)
            NovelInstallDispatch.DISABLED
        }
    }

    @Synchronized
    override fun abandonSession(sessionId: Int): Boolean {
        val binding = bindings[sessionId] ?: return false
        return try {
            val installer = context.packageManager.packageInstaller
            val session = installer.mySessions.firstOrNull { it.sessionId == sessionId }
            if (session == null) {
                // Only a previously submitted, process-owned session can already be terminal.
                if (!binding.committed) return false
            } else {
                if (session.appPackageName != binding.record.packageName) return false
                installer.abandonSession(sessionId)
            }
            bindings.remove(sessionId)
            true
        } catch (_: Exception) {
            false
        }
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

        fun forQaExperiment(context: Context): NovelAndroidInstallBridge {
            val app = context.applicationContext ?: context
            val pins = BuildConfig.S39_FIXTURE_SIGNERS.split(',').filter { it.isNotEmpty() }.toSet()
            val policy = NovelInstallExperiment(
                BuildConfig.BUILD_TYPE, app.packageName, BuildConfig.S39_INSTALL_EXPERIMENT,
                BuildConfig.S39_FIXTURE_APK_SHA256, pins,
            )
            return NovelAndroidInstallBridge(app, Build.VERSION.SDK_INT, policy, null)
        }

        fun forPublicActivation(context: Context): NovelAndroidInstallBridge {
            val app = context.applicationContext ?: context
            return NovelAndroidInstallBridge(app, Build.VERSION.SDK_INT, null, NovelInstallActivation.official(app))
        }

        /** Selects QA or public activation based on the actual host identity; both cannot be active. */
        fun forHost(context: Context): NovelAndroidInstallBridge {
            val app = context.applicationContext ?: context
            return NovelAndroidInstallBridge(
                app,
                Build.VERSION.SDK_INT,
                NovelInstallExperiment(
                    BuildConfig.BUILD_TYPE,
                    app.packageName,
                    BuildConfig.S39_INSTALL_EXPERIMENT,
                    BuildConfig.S39_FIXTURE_APK_SHA256,
                    BuildConfig.S39_FIXTURE_SIGNERS.split(',').filter { it.isNotEmpty() }.toSet(),
                ),
                NovelInstallActivation.official(app),
            )
        }

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
