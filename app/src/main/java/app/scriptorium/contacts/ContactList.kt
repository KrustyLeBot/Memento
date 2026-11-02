package app.scriptorium.contacts

import app.scriptorium.fold

/**
 * Phone numbers are compared through a normalised form, never rewritten: `06 12 34 56 78`, `+33 6 12 34 56 78`,
 * `0033 6 12 34 56 78` and `(06) 12-34-56-78` are the same number.
 */
object Phones {
    /**
     * `+<country><number>` for an international or French national number, the bare digits otherwise
     * (short numbers, services). A French `+33 (0)6…` loses its `(0)`.
     */
    fun normalize(raw: String): String {
        val s = raw.trim()
        val digits = s.filter { it in '0'..'9' }
        if (digits.isEmpty()) return ""
        var intl: String? = when {
            s.startsWith("+") -> digits
            digits.startsWith("00") -> digits.drop(2)
            digits.length == 10 && digits.startsWith("0") -> "33" + digits.drop(1)
            else -> null
        }
        if (intl != null && intl.startsWith("330") && intl.length == 12) intl = "33" + intl.drop(3)
        return if (intl != null) "+$intl" else digits
    }

    /** Digit strings a number can be found by: its normalised digits and, for a French number, `0…`. */
    fun searchForms(raw: String): List<String> {
        val n = normalize(raw)
        val digits = n.removePrefix("+")
        val out = mutableListOf(digits, raw.filter { it in '0'..'9' })
        if (n.startsWith("+33")) out.add("0" + digits.drop(2))
        return out.filter { it.isNotEmpty() }.distinct()
    }

    /** A query made only of digits and phone punctuation. */
    fun isNumberQuery(query: String): Boolean {
        val q = query.trim()
        return q.any { it in '0'..'9' } && q.all { it in '0'..'9' || it in " +-.()/" }
    }

    /** The query's digit forms: `0612` also searches `33612`, `+33 6` / `0033 6` search `336`. */
    fun queryForms(query: String): List<String> {
        val q = query.trim()
        val digits = q.filter { it in '0'..'9' }
        val out = mutableListOf(digits)
        when {
            q.startsWith("+") -> Unit
            digits.startsWith("00") -> out.add(digits.drop(2))
            digits.startsWith("0") && digits.length > 1 -> out.add("33" + digits.drop(1))
        }
        return out.filter { it.isNotEmpty() }.distinct()
    }
}

/** A section of the list: "Favoris", a letter, or "#" for names that do not start with a letter. */
data class ContactSection(val title: String, val contacts: List<Contact>)

/** Sorting, letter headers, search, favourites and birthdays. Same algorithms in `core/contacts.py`. */
object ContactList {
    const val FAVORITES = "Favoris"
    const val OTHER = "#"

    /** Sort key: last name then first name (or the reverse); a contact without a name sorts by what it shows. */
    fun sortKey(c: Contact, firstNameFirst: Boolean): List<String> {
        val names = (if (firstNameFirst) listOf(c.firstName, c.lastName) else listOf(c.lastName, c.firstName))
            .map { it.trim().fold() }.filter { it.isNotEmpty() }
        return names.ifEmpty { listOf(c.displayName.fold()) }
    }

    /** "A".."Z" from the first letter of the sort key (accents folded), "#" otherwise. */
    fun letter(c: Contact, firstNameFirst: Boolean): String {
        val first = sortKey(c, firstNameFirst).first().firstOrNull()
        return if (first != null && first in 'a'..'z') first.uppercase() else OTHER
    }

    fun comparator(firstNameFirst: Boolean) = Comparator<Contact> { a, b ->
        val la = letter(a, firstNameFirst) == OTHER
        val lb = letter(b, firstNameFirst) == OTHER
        if (la != lb) return@Comparator if (la) 1 else -1
        val ka = sortKey(a, firstNameFirst)
        val kb = sortKey(b, firstNameFirst)
        for (i in 0 until minOf(ka.size, kb.size)) {
            val c = ka[i].compareTo(kb[i])
            if (c != 0) return@Comparator c
        }
        if (ka.size != kb.size) return@Comparator ka.size - kb.size
        a.id.compareTo(b.id)
    }

    fun sorted(contacts: List<Contact>, firstNameFirst: Boolean): List<Contact> = contacts.sortedWith(comparator(firstNameFirst))

    /** "Favoris" first when any is ticked, then one section per letter ("#" last). Favourites also stay in their letter. */
    fun sections(contacts: List<Contact>, firstNameFirst: Boolean): List<ContactSection> {
        val all = sorted(contacts, firstNameFirst)
        val out = ArrayList<ContactSection>()
        val favorites = all.filter { it.favorite }
        if (favorites.isNotEmpty()) out.add(ContactSection(FAVORITES, favorites))
        all.groupBy { letter(it, firstNameFirst) }.forEach { (letter, list) -> out.add(ContactSection(letter, list)) }
        return out
    }

    /**
     * Every word of [query] must be found in the names, the e-mails, the address or the notes (accents and
     * case ignored); a query of digits matches the phone numbers (`0612` finds `+33 6 12…`).
     */
    fun matches(c: Contact, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        if (Phones.isNumberQuery(q)) {
            val forms = Phones.queryForms(q)
            if (c.phones.any { p -> Phones.searchForms(p.number).any { s -> forms.any { s.contains(it) } } }) return true
        }
        val hay = listOf(c.firstName, c.lastName, c.emails.joinToString(" "), c.address.orEmpty(), c.notes).joinToString("\n").fold()
        return q.fold().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { hay.contains(it) }
    }

    fun search(contacts: List<Contact>, query: String, firstNameFirst: Boolean): List<Contact> =
        sorted(contacts.filter { matches(it, query) }, firstNameFirst)

    /** "1 contact", "236 contacts". */
    fun countLabel(n: Int): String = if (n <= 1) "$n contact" else "$n contacts"
}

/** A contact's birthday, for the agenda's "Anniversaires" layer. */
data class BirthdayEntry(val contactId: String, val name: String, val birthday: Birthday)

/** Contacts with a birthday, sorted by month and day. The agenda expands them per year with [Birthday.occurrence]. */
fun birthdays(contacts: List<Contact>): List<BirthdayEntry> =
    contacts.filter { it.birthday != null && !it.conflict }
        .map { BirthdayEntry(it.id, it.displayName, it.birthday!!) }
        .sortedWith(compareBy({ it.birthday.month }, { it.birthday.day }, { it.name.fold() }))
