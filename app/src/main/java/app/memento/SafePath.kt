package app.memento

/**
 * Paths read from a file's content (a note's `pdf:`/`attachments:`, an activity's `file`/`alt`, an event's or a
 * contact's attachments) arrive by sync from any device, so they are untrusted: same rules as the Windows app
 * (`core/store.py` `inside`) and the web app (`files.py` `resolve`/`owned`).
 */
object SafePath {
    /** Folders whose files a note owns: deleting the note deletes only those, never another note or contact it lists. */
    private val OWNED_DIRS = listOf("attachments/", "drawings/")

    /**
     * [rel] when it names a file inside the storage root, else null. Refused: NUL, backslash, absolute,
     * drive letter / URI scheme / NTFS stream (`:`), empty, `.` or `..` segments, and sync temporaries.
     */
    fun resolve(rel: String?): String? {
        if (rel.isNullOrEmpty() || '\u0000' in rel || '\\' in rel || ':' in rel) return null
        val parts = rel.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." || isTempName(it) }) return null
        return rel
    }

    /** A file a note may delete along with itself: directly under `attachments/` or `drawings/`. */
    fun owned(rel: String?): Boolean {
        val p = resolve(rel) ?: return false
        return OWNED_DIRS.any { p.startsWith(it) && '/' !in p.substring(it.length) }
    }
}
