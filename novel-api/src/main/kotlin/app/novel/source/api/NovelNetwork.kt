package app.novel.source.api

/**
 * Network access provided by the host for an external novel extension.
 *
 * An extension APK ships no HTTP client of its own: the host owns the socket, the timeouts and the
 * `User-Agent`, and hands the extension the response body. What is left to the extension is the part
 * that is actually about the site — its selectors and its parsing. This is design B of the
 * real-novel-extension task, and it mirrors how Mihon's manga extensions use the host's client.
 *
 * The host's implementation does its blocking work off the main thread, so a [NovelSource] never has
 * to reach for `Dispatchers.IO` itself.
 */
interface NovelNetwork {
    /**
     * Fetches [url] and returns the response body as text.
     *
     * [headers] are additional request headers. The host may set some of its own (notably its
     * `User-Agent`), and a header named in both is the host's to decide.
     *
     * Throws when the request fails or the response is not successful, so that an extension can tell a
     * failure apart from an empty page. It never returns an empty string for a failure.
     */
    suspend fun fetch(url: String, headers: Map<String, String> = emptyMap()): String
}
