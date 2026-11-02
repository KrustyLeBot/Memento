package app.scriptorium.contacts

import app.scriptorium.Note
import app.scriptorium.NoteFormat
import app.scriptorium.fold
import java.time.LocalDate
import java.time.Year

/** Labels of a phone number, as stored in parentheses after it (`+33 6 12 34 56 78 (mobile)`). */
object PhoneLabels {
    const val MOBILE = "mobile"
    const val HOME = "domicile"
    const val WORK = "travail"
    const val OTHER = "autre"
    val standard = listOf(MOBILE, HOME, WORK, OTHER)

    /**
     * A label as it can be stored after a number: without parentheses, and with a letter in it, or `(2)` would
     * read back as part of the number. Null when nothing is left.
     */
    fun clean(label: String?): String? =
        label?.replace("(", " ")?.replace(")", " ")?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { l -> l.any { it.isLetter() } }
}

/** A phone as typed by the user; [label] is the optional text in parentheses. The number is never rewritten. */
data class Phone(val number: String, val label: String? = null) {
    val normalized: String get() = Phones.normalize(number)

    fun encode(): String = PhoneLabels.clean(label)?.let { "${number.trim()} ($it)" } ?: number.trim()

    companion object {
        private val LABEL = Regex("""^(.*?)\s*\(([^()]*\p{L}[^()]*)\)\s*$""")

        fun decode(raw: String): Phone {
            val m = LABEL.matchEntire(raw.trim()) ?: return Phone(raw.trim())
            return Phone(m.groupValues[1].trim(), m.groupValues[2].trim())
        }
    }
}

/** A birthday; [year] is null when unknown (`--05-14` in the file). */
data class Birthday(val year: Int?, val month: Int, val day: Int) {
    fun encode(): String = if (year == null) "--%02d-%02d".format(month, day) else "%04d-%02d-%02d".format(year, month, day)

    /** The date it falls on in [inYear]; a 29 February falls on the 28th in other years. */
    fun occurrence(inYear: Int): LocalDate =
        if (month == 2 && day == 29 && !Year.isLeap(inYear.toLong())) LocalDate.of(inYear, 2, 28) else LocalDate.of(inYear, month, day)

    /** Age reached on the birthday of [inYear], when the year of birth is known. */
    fun ageIn(inYear: Int): Int? = year?.let { inYear - it }?.takeIf { it >= 0 }

    companion object {
        private val FULL = Regex("""^(\d{4})-?(\d{2})-?(\d{2})(?:[T ].*)?$""")
        private val NO_YEAR = Regex("""^--(\d{2})-?(\d{2})$""")

        /** `1990-05-14`, `19900514`, `--05-14`, `--0514` (a time part is ignored); null if not a valid date. */
        fun parse(raw: String?): Birthday? {
            val s = raw?.trim().orEmpty()
            FULL.matchEntire(s)?.let { m ->
                val y = m.groupValues[1].toInt()
                // Apple writes 1604 for "year unknown"
                return of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), if (y == 1604 || y == 0) null else y)
            }
            NO_YEAR.matchEntire(s)?.let { m -> return of(m.groupValues[2].toInt(), m.groupValues[1].toInt(), null) }
            return null
        }

        fun of(day: Int, month: Int, year: Int?): Birthday? {
            if (month !in 1..12 || day < 1) return null
            if (year != null && year !in 1..9999) return null
            val max = java.time.Month.of(month).length(year?.let { Year.isLeap(it.toLong()) } ?: true)
            return if (day <= max) Birthday(year, month, day) else null
        }
    }
}

/**
 * One contact = one note of `contacts/` (`contacts/<lastname>-<firstname>-<id6>.md`). The contact fields are
 * frontmatter keys the note format keeps as unknown lines; [base] carries everything else of the note
 * (unknown keys, labels, attachments…) so a save never loses it.
 */
data class Contact(
    val id: String = "",
    val path: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val phones: List<Phone> = emptyList(),
    val emails: List<String> = emptyList(),
    val address: String? = null,
    val birthday: Birthday? = null,
    val favorite: Boolean = false,
    val uid: String? = null,
    /** Set on the files created by an import, so the import can be undone. */
    val importId: String? = null,
    /** The body of the note: free text shown as "Notes". */
    val notes: String = "",
    val created: Long = 0,
    val updated: Long = 0,
    val conflict: Boolean = false,
    val base: Note? = null,
) {
    /** "Prénom Nom"; what is filled when one is empty. */
    val name: String get() = listOf(firstName.trim(), lastName.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    /** [name], or the first number or e-mail for a contact without a name. */
    val displayName: String get() = name.ifEmpty { phones.firstOrNull()?.number ?: emails.firstOrNull() ?: "(Sans nom)" }

    /** Nothing at all to keep. */
    val isEmpty: Boolean
        get() = name.isEmpty() && phones.isEmpty() && emails.isEmpty() && address.isNullOrBlank() && birthday == null && notes.isBlank()
}

/** Contact <-> note (frontmatter keys of Part C §2). Pure Kotlin. */
object ContactFormat {
    const val DIR = "contacts"

    private val KEYS = setOf("first_name", "last_name", "phones", "emails", "address", "birthday", "favorite", "uid", "import")
    private val KEY = Regex("""^([A-Za-z_][\w-]*):[ \t]*(.*)$""")

    fun isContactPath(path: String) = path.startsWith("$DIR/")

    /** `contacts/dupont-marie-p4x8q1.md`; fixed at creation, never renamed. */
    fun fileNameFor(c: Contact, id: String): String {
        val parts = listOf(c.lastName, c.firstName).map { slugOrEmpty(it) }.filter { it.isNotEmpty() }
        val stem = parts.joinToString("-").take(40).trim('-').ifEmpty { "contact" }
        return "$DIR/$stem-$id.md"
    }

    private fun slugOrEmpty(s: String) = if (s.fold().none { it in 'a'..'z' || it in '0'..'9' }) "" else NoteFormat.slug(s)

    fun fromNote(note: Note): Contact {
        val blocks = blocks(note.extra)
        fun scalar(key: String): String? = blocks[key]?.let { lines ->
            val first = KEY.matchEntire(lines[0])!!.groupValues[2].trim()
            if (first.isEmpty()) null else NoteFormat.unquote(first)
        }
        fun list(key: String): List<String> = blocks[key]?.let { lines ->
            val first = KEY.matchEntire(lines[0])!!.groupValues[2].trim()
            if (first.isNotEmpty()) NoteFormat.parseList(first)
            else lines.drop(1).map { it.trim() }.filter { it.startsWith("-") }
                .map { NoteFormat.unquote(it.removePrefix("-").trim()) }.filter { it.isNotEmpty() }
        }.orEmpty()
        return Contact(
            id = note.id,
            path = note.path,
            firstName = scalar("first_name").orEmpty(),
            lastName = scalar("last_name").orEmpty(),
            phones = list("phones").map { Phone.decode(it) }.filter { it.number.isNotEmpty() },
            emails = list("emails"),
            address = scalar("address")?.ifBlank { null },
            birthday = Birthday.parse(scalar("birthday")),
            favorite = scalar("favorite")?.equals("true", ignoreCase = true) == true,
            uid = scalar("uid")?.ifBlank { null },
            importId = scalar("import")?.ifBlank { null },
            notes = note.body,
            created = note.created,
            updated = note.updated,
            conflict = note.conflict,
            base = note,
        )
    }

    fun toNote(c: Contact): Note {
        val own = ArrayList<String>()
        if (c.firstName.isNotBlank()) own.add("first_name: " + NoteFormat.yamlQuote(c.firstName.trim()))
        if (c.lastName.isNotBlank()) own.add("last_name: " + NoteFormat.yamlQuote(c.lastName.trim()))
        if (c.phones.isNotEmpty()) {
            own.add("phones:")
            c.phones.forEach { own.add("  - " + NoteFormat.yamlQuote(it.encode())) }
        }
        if (c.emails.isNotEmpty()) {
            own.add("emails:")
            c.emails.forEach { own.add("  - " + NoteFormat.yamlQuote(it.trim())) }
        }
        c.address?.takeIf { it.isNotBlank() }?.let { own.add("address: " + NoteFormat.yamlQuote(it.replace("\n", ", ").trim())) }
        c.birthday?.let { own.add("birthday: " + NoteFormat.yamlQuote(it.encode())) }
        if (c.favorite) own.add("favorite: true")
        c.uid?.let { own.add("uid: " + NoteFormat.yamlQuote(it)) }
        c.importId?.let { own.add("import: " + NoteFormat.yamlQuote(it)) }
        val base = c.base ?: Note(path = c.path, id = c.id, title = "", created = c.created, updated = c.updated)
        val others = blocks(base.extra).filterKeys { it !in KEYS }.values.flatten() + loose(base.extra)
        return base.copy(
            path = c.path, id = c.id, hasId = true, title = c.name.ifEmpty { c.phones.firstOrNull()?.number ?: c.emails.firstOrNull().orEmpty() },
            body = c.notes.trim('\n'), created = c.created, updated = c.updated, extra = own + others,
        )
    }

    /** Frontmatter lines grouped by key (key line + its indented / list lines), in file order. */
    private fun blocks(lines: List<String>): LinkedHashMap<String, MutableList<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (line in lines) {
            val m = KEY.matchEntire(line)
            if (m != null && !line.startsWith(" ") && !line.startsWith("-")) {
                current = mutableListOf(line).also { out[m.groupValues[1]] = it }
            } else {
                current?.add(line)
            }
        }
        return out
    }

    /** Lines before the first key (should not exist, kept anyway). */
    private fun loose(lines: List<String>): List<String> {
        val first = lines.indexOfFirst { KEY.matchEntire(it) != null && !it.startsWith(" ") && !it.startsWith("-") }
        return if (first < 0) lines else lines.subList(0, first)
    }
}
