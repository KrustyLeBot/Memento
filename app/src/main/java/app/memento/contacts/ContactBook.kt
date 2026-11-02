package app.memento.contacts

import app.memento.Fs
import app.memento.NoteFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What an import wrote, so "Annuler l'import" can take exactly that back. */
data class ImportOutcome(
    val importId: String,
    val created: List<String>,
    /** Files an UPDATE import changed, with their text before the import. */
    val previous: Map<String, String>,
    val skipped: Int,
    val unchanged: Int,
    val bad: Int,
    val empty: Int,
    val ignored: Map<String, Int>,
) {
    val updated: Int get() = previous.size
}

/**
 * The `contacts/` folder: [contacts] is an in-memory index rebuilt by [scan]; the files are the truth.
 * Blocking I/O, no Android: call from an I/O dispatcher (the JVM tests use an in-memory [Fs]).
 */
class ContactBook(private val fs: () -> Fs) {
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts

    private val parsed = HashMap<String, Pair<Long, Contact>>()

    @Synchronized
    fun clear() {
        parsed.clear()
        _contacts.value = emptyList()
    }

    /** Re-reads the files whose modification time changed. */
    @Synchronized
    fun scan() {
        val f = fs()
        val out = ArrayList<Contact>()
        val seen = HashSet<String>()
        for (e in f.list(ContactFormat.DIR)) {
            if (e.isDir || e.name.startsWith(".") || !(e.name.endsWith(".md") || CONFLICT_COPY.matches(e.name))) continue
            val path = "${ContactFormat.DIR}/${e.name}"
            seen.add(path)
            val cached = parsed[path]
            val c = if (cached != null && cached.first == e.lastModified) cached.second else {
                val text = f.readText(path) ?: continue
                ContactFormat.fromNote(NoteFormat.parse(path, text, e.lastModified)).also { parsed[path] = e.lastModified to it }
            }
            out.add(c)
        }
        parsed.keys.retainAll(seen)
        _contacts.value = out
    }

    fun byId(id: String): Contact? = _contacts.value.firstOrNull { it.id == id && !it.conflict }

    private fun newId(): String {
        val ids = _contacts.value.map { it.id }.toSet()
        var id = NoteFormat.generateId()
        while (id in ids) id = NoteFormat.generateId()
        return id
    }

    private fun write(c: Contact): Contact {
        val f = fs()
        val text = NoteFormat.serialize(ContactFormat.toNote(c))
        f.writeText(c.path, text)
        val modified = f.lastModified(c.path)
        // what a re-read of the file gives, so the index never differs from the folder
        val saved = ContactFormat.fromNote(NoteFormat.parse(c.path, text, modified))
        parsed[c.path] = modified to saved
        _contacts.value = _contacts.value.filter { it.path != c.path } + saved
        return saved
    }

    /** Writes [c]; a new contact (no path) gets its id and file name here, fixed for good. */
    @Synchronized
    fun save(c: Contact, now: Long = System.currentTimeMillis()): Contact {
        var x = c.copy(updated = now, created = if (c.created == 0L) now else c.created)
        if (x.path.isEmpty()) {
            val id = x.id.takeIf { it.isNotEmpty() && byId(it) == null } ?: newId()
            x = x.copy(id = id, path = ContactFormat.fileNameFor(x, id))
        }
        return write(x)
    }

    /** Deletes the file; returns its text so the deletion can be undone with [restore]. */
    @Synchronized
    fun delete(c: Contact): String? {
        val f = fs()
        val raw = f.readText(c.path)
        f.delete(c.path)
        parsed.remove(c.path)
        _contacts.value = _contacts.value.filter { it.path != c.path }
        return raw
    }

    /** Puts a deleted file back exactly as it was. */
    @Synchronized
    fun restore(path: String, raw: String) {
        fs().writeText(path, raw)
        scan()
    }

    /** Keeps [keep] merged with [others] and deletes the others; returns the texts to undo it. */
    @Synchronized
    fun merge(keep: Contact, others: List<Contact>): Map<String, String> {
        val f = fs()
        val before = (listOf(keep) + others).mapNotNull { c -> f.readText(c.path)?.let { c.path to it } }.toMap()
        save(Duplicates.merge(keep, others))
        others.forEach { delete(it) }
        return before
    }

    /** Undoes [merge]: the merged files come back, the kept one returns to its old text. */
    @Synchronized
    fun restoreAll(texts: Map<String, String>) {
        val f = fs()
        texts.forEach { (path, raw) -> f.writeText(path, raw) }
        scan()
    }

    /** Writes an import plan; [progress] gets (done, total). */
    @Synchronized
    fun apply(plan: ImportPlan, importId: String, result: VCardResult, progress: (Int, Int) -> Unit = { _, _ -> }): ImportOutcome {
        val f = fs()
        val total = plan.creates.size + plan.updates.size
        val created = ArrayList<String>()
        val previous = LinkedHashMap<String, String>()
        var done = 0
        for (c in plan.creates) {
            write(c)
            created.add(c.path)
            progress(++done, total)
        }
        for ((before, after) in plan.updates) {
            f.readText(before.path)?.let { previous[before.path] = it }
            write(after)
            progress(++done, total)
        }
        return ImportOutcome(importId, created, previous, plan.skipped, plan.unchanged, result.bad, result.empty, result.ignored)
    }

    /**
     * "Annuler l'import": deletes the files the import created (only those still marked with its id) and puts the
     * contacts it updated back as they were. Returns the number of files touched.
     */
    @Synchronized
    fun undo(outcome: ImportOutcome): Int {
        val f = fs()
        var n = 0
        for (path in outcome.created) {
            val text = f.readText(path) ?: continue
            val c = ContactFormat.fromNote(NoteFormat.parse(path, text, 0))
            if (c.importId != outcome.importId) continue
            f.delete(path)
            n++
        }
        outcome.previous.forEach { (path, raw) -> f.writeText(path, raw); n++ }
        scan()
        return n
    }

    companion object {
        private val CONFLICT_COPY = Regex(""".+\.md\.conflict\d+""")

        fun newImportId(now: Long): String =
            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date(now)) + "-" + NoteFormat.generateId()
    }
}
