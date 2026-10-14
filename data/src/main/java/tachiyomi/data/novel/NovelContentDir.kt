package tachiyomi.data.novel

import java.io.File

/**
 * Directory where serialized chapter content is stored. Wrapping the path keeps it a distinct type for
 * dependency injection, so a bare [File] never has to be bound and the repositories stay testable off-device.
 */
@JvmInline
value class NovelContentDir(val path: File)
