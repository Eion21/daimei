package tachiyomi.data.novel

import java.security.MessageDigest

object NovelSourceHelper {
    fun generateSourceId(infoId: String): Long {
        val key = "novel/$infoId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }
}
