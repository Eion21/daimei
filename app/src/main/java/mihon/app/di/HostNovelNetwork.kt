package mihon.app.di

import app.novel.source.api.NovelNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The host's [NovelNetwork]: it owns the socket and the failure semantics for every novel extension.
 *
 * It lives beside [NovelNetworkBindings] rather than in a feature package because that binding is what
 * publishes it to the graph, and nothing outside the graph is meant to construct it.
 *
 * Two decisions are made here and nowhere else:
 *
 * 1. The offload to [Dispatchers.IO]. [app.novel.source.api.NovelSource] is reached through Mihon's
 *    `Source` api, which runs its work on the main dispatcher, and OkHttp's `execute` is blocking.
 *    Without this the first fetch on a device is a `NetworkOnMainThreadException`. Putting the offload
 *    in the one implementation instead of in every extension is what spares each author from having to
 *    remember it (decision D4).
 * 2. The failure contract: a transport error or a non-2xx response throws. A failed fetch must never
 *    come back as an empty string, because an empty body and a successful empty page are then the same
 *    value and the caller cannot tell them apart. A chapter that failed to fetch used to be cached as
 *    an empty chapter and left a black screen; throwing is what makes that impossible.
 */
class HostNovelNetwork(
    private val client: OkHttpClient = defaultClient(),
) : NovelNetwork {

    override suspend fun fetch(url: String, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            // The User-Agent is the host's, not the extension's: one known client string for every novel
            // extension, so a site sees one caller and an author never has to set it.
            .header(USER_AGENT_HEADER, USER_AGENT)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} fetching $url")
            }
            response.body.string()
        }
    }

    companion object {
        /** The host's `User-Agent`. Says what this is rather than pretending to be a browser. */
        const val USER_AGENT = "Mihon-NovelExtension/1.1 (host-provided network)"

        private const val USER_AGENT_HEADER = "User-Agent"
        private const val TIMEOUT_SECONDS = 30L

        /**
         * A client with explicit, bounded timeouts. OkHttp's defaults are long enough that a request
         * which will never be answered looks like a hang instead of a failure, which is exactly the
         * outcome the failure contract above exists to avoid.
         */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}
