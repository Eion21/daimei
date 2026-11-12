package eu.kanade.tachiyomi.extension.novel

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Host-owned configuration, never populated from distribution-catalog origin lists. */
class NovelArtifactOriginPolicy private constructor(
    origins: Set<String>,
    private val fixtureLoopback: Boolean,
) {
    private val origins = origins.map { origin ->
        val url = requireNotNull(origin.toHttpUrlOrNull())
        require(url.isHttps && url.encodedPath == "/" && url.query == null && url.fragment == null)
        require(url.username.isEmpty() && url.password.isEmpty() && !url.host.endsWith('.'))
        if (fixtureLoopback) require(url.host == "localhost" || url.host == "127.0.0.1")
        originKey(url)
    }.toSet()

    internal fun permits(url: HttpUrl): Boolean = url.isHttps && url.username.isEmpty() &&
        url.password.isEmpty() && url.fragment == null && !url.host.endsWith('.') && originKey(url) in origins

    internal fun permits(address: InetAddress): Boolean {
        val bytes = address.address.map { it.toInt() and 255 }
        if (fixtureLoopback) return bytes == listOf(127, 0, 0, 1)
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return false
        }
        if (address is Inet4Address) {
            val a = bytes[0]
            val b = bytes[1]
            val c = bytes[2]
            return a !in setOf(0, 10, 127) && a < 224 &&
                !(a == 100 && b in 64..127) && !(a == 169 && b == 254) &&
                !(a == 172 && b in 16..31) && !(a == 192 && b == 168) &&
                !(a == 192 && b == 0 && c in setOf(0, 2)) &&
                !(a == 192 && b == 88 && c == 99) &&
                !(a == 198 && b in 18..19) && !(a == 198 && b == 51 && c == 100) &&
                !(a == 203 && b == 0 && c == 113) && bytes != listOf(168, 63, 129, 16)
        }
        if (address is Inet6Address) {
            // Only native global unicast; no mapped/NAT64/Teredo/6to4/ORCHID/documentation paths.
            return address.scopeId == 0 && bytes[0] and 224 == 32 &&
                !(bytes[0] == 32 && bytes[1] == 1 && bytes[2] and 254 == 0) &&
                !(bytes.take(4) == listOf(32, 1, 13, 184)) &&
                !(bytes.take(2) == listOf(32, 2)) &&
                !(bytes[0] == 63 && bytes[1] == 255 && bytes[2] and 240 == 0)
        }
        return false
    }

    companion object {
        fun publicHttps(origins: Set<String>) = NovelArtifactOriginPolicy(origins.toSet(), false)

        /** Internal fixture enrollment, never selected by a catalog or enabled by public enrollment. */
        internal fun controlledLoopback(origin: String) = NovelArtifactOriginPolicy(setOf(origin), true)

        private fun originKey(url: HttpUrl) = "https://${url.host}:${url.port}"
    }
}
