package app.memento

const val DEFAULT_COLOR = "default"

data class Note(
    /** Path relative to the storage root, e.g. `courses-k3f9a2.md`. */
    val path: String,
    val id: String,
    /** False for `.md` files created elsewhere (no frontmatter id yet). */
    val hasId: Boolean = true,
    val title: String,
    val color: String = DEFAULT_COLOR,
    val pinned: Boolean = false,
    val labels: List<String> = emptyList(),
    /** Epoch millis, preserved untouched until the reminders milestone. */
    val reminder: Long? = null,
    val created: Long,
    val updated: Long,
    val body: String = "",
    /** Scanned document attached to the note, relative to the storage root (`attachments/<name>.pdf`). */
    val pdf: String? = null,
    /** Files attached to the note (photos, documents…), relative to the storage root (`attachments/<name>`). */
    val attachments: List<String> = emptyList(),
    /** Set for the notes of the "Activités" folder: what the list shows without reading the track file. */
    val activity: app.memento.activity.ActivityInfo? = null,
    /** Unknown frontmatter lines, kept verbatim for Obsidian compatibility. */
    val extra: List<String> = emptyList(),
    val conflict: Boolean = false,
) {
    val fileName: String get() = path.substringAfterLast('/')

    /** Plain-text version used for sharing and previews: checklists as ☐ / ☑. */
    fun plainBody(): String = body.lines().joinToString("\n") { line ->
        val m = CHECK_REGEX.matchEntire(line)
        if (m == null) line else (if (m.groupValues[1] == " ") "☐ " else "☑ ") + m.groupValues[2]
    }

    fun shareText(): String = listOf(title, plainBody()).filter { it.isNotBlank() }.joinToString("\n\n")
}

val CHECK_REGEX = Regex("""^\s*[-*+] \[( |x|X)\] ?(.*)$""")
