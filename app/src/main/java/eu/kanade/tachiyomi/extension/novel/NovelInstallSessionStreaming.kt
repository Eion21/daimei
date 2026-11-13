package eu.kanade.tachiyomi.extension.novel

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Hash only bytes successfully written; callers must fsync and close before accepting the receipt. */
internal object NovelInstallSessionStreaming {
    fun copy(source: InputStream, output: OutputStream, expectedSize: Long): NovelInstallStreamReceipt {
        require(expectedSize in 1..NovelDistributionCatalogCodec.MAX_APK_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            if (count == 0) throw IOException("Installer input made no progress")
            if (count > expectedSize - total) throw IOException("Installer input exceeds expected length")
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            total += count
        }
        if (total != expectedSize) throw IOException("Installer input is shorter than expected")
        return NovelInstallStreamReceipt(total, digest.digest().joinToString("") { "%02x".format(it) })
    }
}
