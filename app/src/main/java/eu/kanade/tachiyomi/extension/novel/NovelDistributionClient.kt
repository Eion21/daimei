package eu.kanade.tachiyomi.extension.novel

import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Bounded, anonymous schema-2 metadata only. Values never confer installation or execution authority. */
internal class NovelDistributionClient(client: OkHttpClient = OkHttpClient()) {
    private val publicAddresses = NovelArtifactOriginPolicy.publicHttps(emptySet())
    private val transport = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .proxy(Proxy.NO_PROXY)
        .connectionPool(ConnectionPool())
        .dns { host ->
            Dns.SYSTEM.lookup(host).also { addresses ->
                if (addresses.isEmpty() || addresses.any { !publicAddresses.permits(it) }) {
                    throw UnknownHostException("Distribution metadata requires public addresses")
                }
            }
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Blocking: the caller must use an IO dispatcher, never Android's main thread. */
    fun fetch(raw: String): NovelDistributionCatalog {
        val url = requireNotNull(raw.takeIf { it.length <= 2048 }?.toHttpUrlOrNull())
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "Daimei-NovelDistribution/2")
            .build()
        transport.newCall(request).execute().use { response ->
            if (response.code != 200 || response.request.url != url || response.priorResponse != null) {
                throw IOException("Distribution metadata response rejected")
            }
            val encoding = response.header("Content-Encoding")
            if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                throw IOException("Encoded distribution metadata rejected")
            }
            if (response.body.contentLength() > NovelDistributionCatalogCodec.MAX_BYTES) {
                throw NovelDistributionException(NovelDistributionError.SIZE)
            }
            return NovelDistributionCatalogCodec.decode(response.body.byteStream())
        }
    }
}
