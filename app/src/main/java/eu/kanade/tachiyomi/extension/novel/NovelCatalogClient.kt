package eu.kanade.tachiyomi.extension.novel

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Read-only transport. No dependency on stores, trust, APK downloaders or installers. */
class NovelCatalogClient(private val client: OkHttpClient = readOnlyClient()) {
    suspend fun fetch(url: String): NovelCatalog = withContext(Dispatchers.IO) {
        val endpoint = catalogUrl(url)
        try {
            val request = Request.Builder().url(endpoint)
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "Daimei-NovelCatalog/1 (read-only)")
                .build()
            client.newCall(request).await().use { response ->
                // Reject even injected transports that followed a redirect. The production client never follows one.
                if (response.code != 200 || response.request.url != endpoint || response.priorResponse != null) {
                    throw NovelCatalogException(NovelCatalogError.HTTP)
                }
                val encoding = response.header("Content-Encoding")
                if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                    throw NovelCatalogException(NovelCatalogError.HTTP)
                }
                if (response.body.contentLength() > NovelCatalogCodec.MAX_BYTES) {
                    throw NovelCatalogException(NovelCatalogError.SIZE)
                }
                runInterruptible { NovelCatalogCodec.decode(response.body.byteStream()) }
            }
        } catch (_: IOException) {
            throw NovelCatalogException(NovelCatalogError.NETWORK)
        }
    }

    companion object {
        fun catalogUrl(raw: String): HttpUrl {
            val url = raw.takeIf { it.length <= 2048 }?.toHttpUrlOrNull()
                ?: throw NovelCatalogException(NovelCatalogError.URL)
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) {
                throw NovelCatalogException(NovelCatalogError.URL)
            }
            return url
        }

        private fun readOnlyClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
