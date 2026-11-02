package app.scriptorium.map

import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * The browsing cache: tiles seen while looking at activities, kept so a map reopens instantly. Least
 * recently used files go first once it passes [maxBytes]. Pure java.io, safe from several threads:
 * files are written to a temporary name then renamed, so a reader never sees half a tile.
 */
class TileCache(private val dir: File, private val maxBytes: Long) {
    private val lock = Any()
    private var total = -1L

    fun get(rel: String): File? {
        val f = File(dir, rel)
        // an empty file is what an unsynced write looks like after a power cut: a miss, not a tile
        if (!f.isFile || f.length() == 0L) return null
        f.setLastModified(System.currentTimeMillis())
        return f
    }

    fun put(rel: String, bytes: ByteArray) {
        val f = File(dir, rel)
        writeAtomic(f, bytes)
        val over = synchronized(lock) {
            if (total < 0) total = size()
            total += bytes.size
            total > maxBytes
        }
        if (over) trim()
    }

    /** Down to 80 % of the limit, oldest first, so trimming does not run on every tile. */
    fun trim() {
        synchronized(lock) {
            val files = dir.walkTopDown().filter { it.isFile }.toMutableList()
            var sum = files.sumOf { it.length() }
            if (sum > maxBytes) {
                files.sortBy { it.lastModified() }
                for (f in files) {
                    if (sum <= maxBytes * 8 / 10) break
                    val len = f.length()
                    if (f.delete()) sum -= len
                }
            }
            total = sum
        }
    }

    private fun size(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Bytes on disk now (walks the folder: off the main thread). */
    fun sizeBytes(): Long = synchronized(lock) { size().also { total = it } }

    /** Deletes every cached file (tiles and peaks answers). */
    fun clear() {
        synchronized(lock) {
            dir.listFiles()?.forEach { it.deleteRecursively() }
            total = size()
        }
    }

    companion object {
        /** Writes then renames, so a killed process or a full disk never leaves a truncated file under the real name. */
        fun writeAtomic(f: File, bytes: ByteArray) {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, ".${f.name}.${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(tmp).use { it.write(bytes) }
                if (!tmp.renameTo(f)) throw java.io.IOException("Renommage impossible : ${f.name}")
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
    }
}
