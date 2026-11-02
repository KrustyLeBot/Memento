package app.memento.contacts

/** What to do with a card that looks like a contact already in the app. */
enum class DuplicatePolicy(val label: String) {
    KEEP_BOTH("Garder les deux"),
    SKIP("Ne pas importer les doublons"),
    UPDATE("Mettre à jour (même UID ou numéro)"),
}

/** The preview shown before anything is written. */
data class ImportPreview(
    val cards: Int,
    /** Cards that look like a contact already in the app (same UID, number or name). */
    val duplicates: Int,
    /** Cards without any name or number (an e-mail only, a note only…). */
    val blank: Int,
    val bad: Int,
    val empty: Int,
    val ignored: Map<String, Int>,
)

data class ImportPlan(
    val creates: List<Contact>,
    /** (before, after) for the contacts an UPDATE import changes. */
    val updates: List<Pair<Contact, Contact>>,
    val skipped: Int,
    val unchanged: Int,
)

/** vCard import: preview, duplicate policies, plan. Pure; the writing and the undo are in [ContactBook]. */
object ContactImport {
    fun preview(result: VCardResult, existing: List<Contact>): ImportPreview = ImportPreview(
        cards = result.contacts.size,
        duplicates = result.contacts.count { match(it, existing, byName = true) != null },
        blank = result.contacts.count { it.name.isEmpty() && it.phones.isEmpty() },
        bad = result.bad,
        empty = result.empty,
        ignored = result.ignored,
    )

    /**
     * The existing contact [card] duplicates: same UID, then a common number when the names do not differ (a
     * shared home line is not the same person), then (when [byName]) the same name.
     */
    fun match(card: Contact, existing: List<Contact>, byName: Boolean): Contact? {
        val live = existing.filter { !it.conflict }
        card.uid?.let { uid -> live.firstOrNull { it.uid == uid }?.let { return it } }
        val phones = Duplicates.phoneKeys(card)
        if (phones.isNotEmpty()) {
            live.firstOrNull { c -> Duplicates.phoneKeys(c).any { it in phones } && namesAgree(card, c) }?.let { return it }
        }
        if (byName) Duplicates.nameKey(card)?.let { key -> live.firstOrNull { Duplicates.nameKey(it) == key }?.let { return it } }
        return null
    }

    /** No name on one side, or the words of one name all in the other ("Marie" and "Marie Dupont"). */
    private fun namesAgree(a: Contact, b: Contact): Boolean {
        val ka = Duplicates.nameKey(a)?.split(' ')?.toSet() ?: return true
        val kb = Duplicates.nameKey(b)?.split(' ')?.toSet() ?: return true
        return ka.containsAll(kb) || kb.containsAll(ka)
    }

    /**
     * New contacts get a fresh id, their file name and `import: [importId]`. With [DuplicatePolicy.UPDATE] a card
     * matched by UID or number is merged into that contact (its own values first, nothing of the contact lost).
     */
    fun plan(
        result: VCardResult, existing: List<Contact>, policy: DuplicatePolicy, importId: String, now: Long, newId: () -> String,
    ): ImportPlan {
        val taken = existing.map { it.id }.toMutableSet()
        val creates = ArrayList<Contact>()
        val current = LinkedHashMap<String, Pair<Contact, Contact>>() // id -> (before, after)
        var skipped = 0
        var unchanged = 0
        for (card in result.contacts) {
            when (policy) {
                DuplicatePolicy.SKIP -> if (match(card, existing, byName = true) != null) { skipped++; continue }
                DuplicatePolicy.UPDATE -> {
                    // by UID or number; a card with neither (e-mail only) falls back on its name
                    val target = match(card, existing, byName = card.uid == null && Duplicates.phoneKeys(card).isEmpty())
                    if (target != null) {
                        val before = current[target.id]?.second ?: target
                        val after = update(before, card, now)
                        if (after == before) unchanged++ else current[target.id] = (current[target.id]?.first ?: target) to after
                        continue
                    }
                }
                DuplicatePolicy.KEEP_BOTH -> Unit
            }
            var id = newId()
            while (id in taken || id.isEmpty()) id = newId()
            taken.add(id)
            val c = card.copy(id = id, importId = importId, created = now, updated = now)
            creates.add(c.copy(path = ContactFormat.fileNameFor(c, id)))
        }
        return ImportPlan(creates, current.values.toList(), skipped, unchanged)
    }

    private fun update(existing: Contact, card: Contact, now: Long): Contact {
        val primary = existing.copy(
            firstName = card.firstName.ifBlank { existing.firstName },
            lastName = card.lastName.ifBlank { existing.lastName },
            address = card.address?.takeIf { it.isNotBlank() } ?: existing.address,
            birthday = card.birthday ?: existing.birthday,
            uid = existing.uid ?: card.uid,
        )
        val merged = Duplicates.merge(primary, listOf(card.copy(favorite = false)))
        return if (merged == existing) existing else merged.copy(updated = now)
    }
}
