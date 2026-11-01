package app.scriptorium

import java.io.File
import java.io.IOException

/**
 * One-time move of the notes from the folder of the app's former name (Documents/Griffonote) to
 * Documents/Scriptorium. A copy first, checked file by file; deleting the old folder is a separate,
 * explicit step because a sync tool still pointed at it would propagate the deletion.
 */
object LegacyFolder {
    data class Summary(val files: Int, val bytes: Long)

    /** Sync temporaries and conflict-free leftovers that must not travel. */
    private fun skipped(f: File) = f.name.contains(".synctmp-") || f.name.endsWith(".tmp")

    fun summary(dir: File): Summary {
        var files = 0
        var bytes = 0L
        dir.walkTopDown().filter { it.isFile && !skipped(it) }.forEach { files++; bytes += it.length() }
        return Summary(files, bytes)
    }

    /** True when [old] holds files and [target] holds none yet (missing or empty). */
    fun needsImport(old: File, target: File): Boolean =
        old.isDirectory && summary(old).files > 0 && (!target.exists() || summary(target).files == 0)

    /**
     * Copies every file of [from] into [to] (same relative paths, modification times kept) and checks
     * that each copy has the size of its source. Existing files of [to] are never overwritten.
     */
    fun copy(from: File, to: File, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): Summary {
        val sources = from.walkTopDown().filter { it.isFile && !skipped(it) }.toList()
        var bytes = 0L
        sources.forEachIndexed { i, src ->
            val dst = File(to, src.relativeTo(from).path)
            if (!dst.exists()) {
                dst.parentFile?.mkdirs()
                src.copyTo(dst)
                dst.setLastModified(src.lastModified())
            }
            if (dst.length() != src.length()) throw IOException("Copie incomplète : ${src.relativeTo(from).path}")
            bytes += src.length()
            progress(i + 1, sources.size)
        }
        return Summary(sources.size, bytes)
    }

    /** Deletes [old] only if every one of its files has a same-size copy in [target]. */
    fun deleteIfCopied(old: File, target: File) {
        val missing = old.walkTopDown().filter { it.isFile && !skipped(it) }.firstOrNull {
            val dst = File(target, it.relativeTo(old).path)
            !dst.isFile || dst.length() != it.length()
        }
        if (missing != null) throw IOException("Fichier absent du nouveau dossier : ${missing.relativeTo(old).path}")
        if (!old.deleteRecursively()) throw IOException("Suppression incomplète de ${old.path}")
    }
}
