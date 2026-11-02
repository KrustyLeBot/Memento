package app.memento.contacts

import java.io.File

/** A contact file deleted because its contact was deleted on the phone, kept so it can be put back. */
data class TrashEntry(val key: String, val path: String, val name: String, val deletedAt: Long, val announced: Boolean)

/**
 * The mirror's own memory, in app-private storage (never in the synced folder: it describes this phone's
 * provider): what was agreed per contact at the last run ([Synced]), the raw contacts whose file was created
 * by a run that did not finish, and the trash of contacts deleted on the phone (7 days). Plain files, no Android.
 */
class MirrorStore(private val dir: File) {
    private val stateFile = File(dir, "state.tsv")
    private val trashDir = File(dir, "trash")
    private val trashIndex = File(dir, "trash.tsv")

    class State(val synced: Map<String, Synced>, val pending: Map<Long, String>)

    @Synchronized
    fun read(): State {
        val synced = HashMap<String, Synced>()
        val pending = HashMap<Long, String>()
        if (stateFile.isFile) {
            for (line in stateFile.readLines()) {
                val f = line.split('\t')
                when {
                    f.size == 4 && f[0] == "S" -> f[3].toLongOrNull()?.let { synced[f[1]] = Synced(f[2], it) }
                    f.size == 3 && f[0] == "P" -> f[1].toLongOrNull()?.let { pending[it] = f[2] }
                }
            }
        }
        return State(synced, pending)
    }

    @Synchronized
    fun write(state: State) {
        val text = buildString {
            state.synced.toSortedMap().forEach { (id, s) -> append("S\t").append(id).append('\t').append(s.hash).append('\t').append(s.at).append('\n') }
            state.pending.toSortedMap().forEach { (raw, id) -> append("P\t").append(raw).append('\t').append(id).append('\n') }
        }
        atomic(stateFile, text)
    }

    /** Remembers that the files of [creates] (raw id → contact id) are being written. */
    @Synchronized
    fun addPending(creates: Map<Long, String>) {
        if (creates.isEmpty()) return
        val s = read()
        write(State(s.synced, s.pending + creates))
    }

    // ---- trash ----

    @Synchronized
    fun trash(path: String, name: String, text: String, now: Long): TrashEntry {
        trashDir.mkdirs()
        var key = "$now-" + path.substringAfterLast('/').removeSuffix(".md").filter { it.isLetterOrDigit() || it == '-' }
        var n = 1
        while (File(trashDir, "$key.md").exists()) key = "$now-${n++}"
        atomic(File(trashDir, "$key.md"), text)
        val e = TrashEntry(key, path, name.replace(Regex("[\\t\\n\\r]"), " "), now, false)
        writeTrash(entries() + e)
        return e
    }

    @Synchronized
    fun entries(): List<TrashEntry> {
        if (!trashIndex.isFile) return emptyList()
        return trashIndex.readLines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size != 5) null else f[3].toLongOrNull()?.let { TrashEntry(f[0], f[1], f[2], it, f[4] == "1") }
        }
    }

    fun text(e: TrashEntry): String? = File(trashDir, "${e.key}.md").takeIf { it.isFile }?.readText()

    /**
     * The entries not shown yet, marked shown now: their [keepMs] start now, so a deletion made while the app was
     * not opened (the sync adapter) is never purged before the user was offered to undo it.
     */
    @Synchronized
    fun announce(now: Long, keepMs: Long = KEEP_MS): List<TrashEntry> {
        purge(now, keepMs)
        val all = entries()
        val fresh = all.filter { !it.announced }
        if (fresh.isNotEmpty()) writeTrash(all.map { if (it.announced) it else it.copy(announced = true, deletedAt = now) })
        return fresh
    }

    @Synchronized
    fun remove(e: TrashEntry) {
        File(trashDir, "${e.key}.md").delete()
        writeTrash(entries().filter { it.key != e.key })
    }

    @Synchronized
    fun purge(now: Long, keepMs: Long = KEEP_MS) {
        val (old, kept) = entries().partition { it.announced && now - it.deletedAt > keepMs }
        if (old.isEmpty()) return
        old.forEach { File(trashDir, "${it.key}.md").delete() }
        writeTrash(kept)
    }

    private fun writeTrash(list: List<TrashEntry>) =
        atomic(trashIndex, list.joinToString("") { "${it.key}\t${it.path}\t${it.name}\t${it.deletedAt}\t${if (it.announced) 1 else 0}\n" })

    private fun atomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("Écriture impossible : ${target.path}")
        }
    }

    companion object {
        const val KEEP_MS = 7L * 24 * 3600 * 1000
    }
}
