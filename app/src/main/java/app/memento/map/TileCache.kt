package app.memento.map

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The browsing cache: tiles seen while looking at activities, kept so a map reopens instantly. Least
 * recently used files go first once it passes [maxBytes]. Pure Java file IO, safe from several threads:
 * files are written to a temporary name then renamed, so a reader never sees half a tile. Reads and
 * writes never walk the folder: the size is counted as tiles arrive and the trims run on [trimmer].
 */
class TileCache(private val dir: File, private val maxBytes: Long, private val trimmer: Executor = TRIMMER) {
    private val lock = Any()
    // -1 until a walk has counted the folder; then kept up to date by every put
    private val total = AtomicLong(-1L)
    private val trimQueued = AtomicBoolean(false)

    fun get(rel: String): File? {
        val f = File(dir, rel)
        val attrs = try {
            Files.readAttributes(f.toPath(), BasicFileAttributes::class.java)
        } catch (_: IOException) {
            return null
        }
        // an empty file is what an unsynced write looks like after a power cut: a miss, not a tile
        if (!attrs.isRegularFile || attrs.size() == 0L) return null
        val now = System.currentTimeMillis()
        // an hour is precise enough for least recently used, and spares a disk write per tile shown
        if (now - attrs.lastModifiedTime().toMillis() > TOUCH_MS) f.setLastModified(now)
        return f
    }

    fun put(rel: String, bytes: ByteArray) {
        writeAtomic(File(dir, rel), bytes)
        val t = total.updateAndGet { if (it < 0) it else it + bytes.size }
        if (t < 0 || t > maxBytes) trimLater()
    }

    /** Counts the folder (the first time) and trims it if needed, on [trimmer]: one job queued at a time. */
    fun trimLater() {
        if (!trimQueued.compareAndSet(false, true)) return
        trimmer.execute {
            try {
                trim()
            } catch (_: Exception) {
                // thrown on this thread it would kill the app; the next put past the limit tries again
            } finally {
                trimQueued.set(false)
            }
        }
    }

    /** Down to 80 % of the limit, oldest first, so trimming does not run on every tile. */
    fun trim() {
        synchronized(lock) {
            val files = scan()
            var sum = files.sumOf { it.size }
            if (sum > maxBytes) {
                files.sortBy { it.modified }
                for (e in files) {
                    if (sum <= maxBytes * 8 / 10) break
                    if (e.file.delete()) sum -= e.size
                }
            }
            total.set(sum)
        }
    }

    private class Entry(val file: File, val size: Long, val modified: Long)

    /** Every file with its size and date, one stat each. */
    private fun scan(): MutableList<Entry> {
        val files = ArrayList<Entry>()
        if (!dir.isDirectory) return files
        Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(p: Path, a: BasicFileAttributes): FileVisitResult {
                if (a.isRegularFile && p != marker) files.add(Entry(p.toFile(), a.size(), a.lastModifiedTime().toMillis()))
                return FileVisitResult.CONTINUE
            }

            // a file deleted while walking (a trim, a cleared cache): skipped, not an error
            override fun visitFileFailed(p: Path, e: IOException) = FileVisitResult.CONTINUE

            override fun postVisitDirectory(p: Path, e: IOException?) = FileVisitResult.CONTINUE
        })
        return files
    }

    private fun size(): Long = scan().sumOf { it.size }

    /** Bytes on disk now (walks the folder: off the main thread). */
    fun sizeBytes(): Long = synchronized(lock) { size().also { total.set(it) } }

    /** Deletes every cached file (tiles and peaks answers); the relief version marker stays true of what comes next. */
    fun clear() {
        synchronized(lock) {
            dir.listFiles()?.forEach { if (it.toPath() != marker) it.deleteRecursively() }
            total.set(size())
        }
    }

    private val marker: Path get() = File(dir, DEM_VERSION_FILE).toPath()

    /**
     * Deletes every cached relief tile (single and metatile entries share `dem/`) when they come from another relief
     * version than [version], then records it. Blocking: off the main thread. True when the version changed. A cache
     * without the marker predates it and holds version 1 (AWS Terrarium, directly or through Tuileries).
     */
    fun dropStaleDem(version: Int): Boolean {
        return synchronized(lock) {
            val m = marker.toFile()
            val recorded = if (m.isFile) m.readText(Charsets.US_ASCII).trim() else "1"
            if (recorded == version.toString()) return false
            val dem = File(dir, TileKey.KIND_DEM)
            if (dem.exists()) {
                // renamed first: relief tiles of the new version written meanwhile land in a fresh dem/
                val doomed = File(dir, ".dem-stale-${UUID.randomUUID()}").takeIf { dem.renameTo(it) } ?: dem
                doomed.deleteRecursively()
                if (doomed == dem && dem.exists()) return true  // not all gone: the marker stays, the next check tries again
            }
            writeAtomic(m, version.toString().toByteArray(Charsets.US_ASCII))
            total.set(size())
            true
        }
    }

    companion object {
        private const val TOUCH_MS = 60 * 60 * 1000L
        /** Next to the tile folders: the relief version the cached dem tiles come from. */
        const val DEM_VERSION_FILE = "dem-version.txt"

        /** One low-priority thread for every cache's trims, so a walk never runs on a WebView request thread. */
        private val TRIMMER: Executor by lazy {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "tile-trim").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }
        }

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
