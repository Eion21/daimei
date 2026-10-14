package app.novel.runtime

import java.security.MessageDigest

/**
 * Derivation of the numeric id Mihon stores in `manga.source_id` from a novel source's string id.
 *
 * It lives here, in a pure Kotlin module, because both ends of the mapping need it and neither end could see the
 * other: `:data` writes the id through [tachiyomi.data.novel.NovelMapper] while `:domain` has to resolve the same
 * id back to a [app.novel.source.api.NovelSource] without an Android dependency or a DI graph in the way. Duplicating
 * the hash would let the two copies drift, and the drift would only show up as novels that could not be found again.
 *
 * MD5 is deliberate and must not be "improved". The value is not recomputed from scratch on any device: it is
 * already written into every `manga` row of every library this app has touched, and external tooling is keyed on it
 * too. A different algorithm would not migrate anything, it would make existing rows unreachable. See
 * [eu.kanade.tachiyomi.source.online.HttpSource.generateId], which this mirrors.
 */
object NovelSourceId {

    /**
     * The id [infoId] maps to, always positive.
     *
     * MD5 is truncated to the low 63 bits, so the result can never collide with the -1 Mihon uses for "this row has
     * no source", and a different id is overwhelmingly likely to land somewhere else.
     */
    fun generate(infoId: String): Long {
        val bytes = MessageDigest.getInstance("MD5").digest("novel/$infoId".toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }
}
