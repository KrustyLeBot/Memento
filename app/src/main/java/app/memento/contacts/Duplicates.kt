package app.memento.contacts

import app.memento.fold

/** The "Doublons" tool: groups, then a merge chosen by the user (never automatic). */
object Duplicates {
    /** Numbers shorter than this (services, short codes) never make two contacts duplicates. */
    private const val MIN_PHONE = 6

    fun nameKey(c: Contact): String? =
        listOf(c.firstName, c.lastName).map { it.trim().fold() }.filter { it.isNotEmpty() }.sorted()
            .joinToString(" ").ifEmpty { null }

    fun phoneKeys(c: Contact): Set<String> =
        c.phones.map { it.normalized }.filter { it.removePrefix("+").length >= MIN_PHONE }.toSet()

    /**
     * Contacts sharing a normalised phone or an identical name (accents, case and first/last order ignored),
     * transitively. Groups of two or more, each sorted like the list; groups ordered by their first contact.
     */
    fun groups(contacts: List<Contact>, firstNameFirst: Boolean = false): List<List<Contact>> {
        val parent = IntArray(contacts.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb) }
        val seen = HashMap<String, Int>()
        contacts.forEachIndexed { i, c ->
            val keys = phoneKeys(c).map { "t:$it" } + listOfNotNull(nameKey(c)?.let { "n:$it" })
            keys.forEach { k -> seen[k]?.let { union(it, i) } ?: run { seen[k] = i } }
        }
        return contacts.indices.groupBy { find(it) }.values.filter { it.size > 1 }
            .map { idx -> ContactList.sorted(idx.map { contacts[it] }, firstNameFirst) }
            .sortedWith { a, b -> ContactList.comparator(firstNameFirst).compare(a[0], b[0]) }
    }

    /** [o] has a name with a word [c]'s name lacks ("Paul" against "Marie Dupont"; not "Marie" against it). */
    private fun otherName(o: Contact, c: Contact): Boolean {
        val mine = nameKey(c)?.split(' ')?.toSet() ?: return false
        val theirs = nameKey(o)?.split(' ') ?: return false
        return !mine.containsAll(theirs)
    }

    /** The contact of a group that holds the most, offered as the one to keep. */
    fun best(group: List<Contact>): Contact = group.maxWith(
        compareBy<Contact>({ it.phones.size + it.emails.size + (if (it.address != null) 1 else 0) + (if (it.birthday != null) 1 else 0) },
            { it.notes.length }).thenByDescending { it.created },
    )

    /**
     * [keep] with the union of the others: numbers (by normalised form) and e-mails (case ignored) added after
     * its own, empty fields filled, notes appended, favourite if any was. Its id, file and uid stay. What has no
     * room left (another name, a second address or birthday) goes to the notes, so a merge never loses a value.
     */
    fun merge(keep: Contact, others: List<Contact>): Contact {
        var c = keep
        for (o in others) {
            val known = c.phones.map { it.normalized }.toMutableSet()
            val phones = c.phones + o.phones.filter { known.add(it.normalized) }
            val mails = c.emails.map { it.lowercase() }.toMutableSet()
            val emails = c.emails + o.emails.filter { mails.add(it.lowercase()) }
            val leftovers = listOfNotNull(
                o.name.takeIf { otherName(o, c) }?.let { "Autre nom : $it" },
                o.address?.trim()?.takeIf { a -> a.isNotEmpty() && !c.address.isNullOrBlank() && a.fold() != c.address!!.trim().fold() }
                    ?.let { "Adresse : $it" },
                o.birthday?.takeIf { c.birthday != null && it != c.birthday }?.let { "Anniversaire : ${it.encode()}" },
            )
            // appended once: merging or re-importing the same text again changes nothing
            var notes = c.notes
            for (text in listOf(o.notes.trim()) + leftovers) {
                if (text.isEmpty() || notes.contains(text)) continue
                notes = listOf(notes.trim(), text).filter { it.isNotEmpty() }.joinToString("\n\n")
            }
            c = c.copy(
                firstName = c.firstName.ifBlank { o.firstName },
                lastName = c.lastName.ifBlank { o.lastName },
                phones = phones,
                emails = emails,
                address = c.address?.takeIf { it.isNotBlank() } ?: o.address,
                birthday = c.birthday ?: o.birthday,
                favorite = c.favorite || o.favorite,
                uid = c.uid ?: o.uid,
                notes = notes,
            )
        }
        return c
    }
}
