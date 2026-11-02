package app.memento.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset

/** The phone's contacts provider as the mirror sees it: raw contacts of the account and their mirrored rows. */
private class FakeProvider {
    data class Raw(
        var sourceId: String?, var hash: String?, var dirty: Boolean, var deleted: Boolean, var starred: Boolean,
        var rows: List<MirrorRow>, var changedAt: Long?,
    )

    val raws = LinkedHashMap<Long, Raw>()
    private var next = 100L

    fun read(): List<MirrorRaw> = raws.map { (id, r) ->
        val needed = !r.deleted && (r.dirty || r.sourceId == null)
        MirrorRaw(id, r.sourceId, r.hash, r.dirty, r.deleted, r.starred, if (needed) r.rows else null, if (needed) r.changedAt else null)
    }

    /** As the sync adapter: nothing it writes marks a raw contact dirty. */
    fun apply(ops: List<MirrorOp>) {
        for (op in ops) when (op) {
            is MirrorOp.Insert -> raws[next++] = Raw(op.contact.id, op.hash, false, false, op.contact.favorite, MirrorPlan.rows(op.contact), null)
            is MirrorOp.Update -> raws.getValue(op.rawId).apply {
                rows = MirrorPlan.rows(op.contact); hash = op.hash; dirty = false; starred = op.contact.favorite
            }
            is MirrorOp.Delete -> raws.remove(op.rawId)
            is MirrorOp.Ack -> raws.getValue(op.rawId).apply { sourceId = op.sourceId; hash = op.hash; dirty = false }
        }
    }

    // ---- what the user does in the phone's contacts app ----

    fun create(rows: List<MirrorRow>, at: Long, starred: Boolean = false): Long {
        raws[next] = Raw(null, null, true, false, starred, rows, at)
        return next++
    }

    fun edit(source: String, at: Long, change: (List<MirrorRow>) -> List<MirrorRow>) {
        raws.values.single { it.sourceId == source }.apply { rows = change(rows); dirty = true; changedAt = at }
    }

    fun delete(source: String) {
        raws.values.single { it.sourceId == source }.apply { deleted = true; dirty = true }
    }

    fun of(source: String) = raws.values.single { it.sourceId == source }

    /** A second raw contact of the same contact, holding an edit (a restore, a provider that duplicated it). */
    fun duplicate(source: String, at: Long, change: (List<MirrorRow>) -> List<MirrorRow>): Long {
        val r = raws.values.first { it.sourceId == source }
        raws[next] = Raw(source, r.hash, true, false, r.starred, change(r.rows), at)
        return next++
    }
}

/** A folder where some files cannot be read for a moment (SAF error, a sync replacing them). */
private class FlakyFs(val inner: MemFs) : app.memento.Fs by inner {
    val broken = HashSet<String>()
    override fun readBytes(path: String): ByteArray? = if (path in broken) null else inner.readBytes(path)
    override fun readText(path: String): String? = readBytes(path)?.toString(Charsets.UTF_8)
}

class MirrorSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fs = MemFs()
    private val book = ContactBook { fs }
    private val phone = FakeProvider()
    private var synced: Map<String, Synced> = emptyMap()
    private val trash = ArrayList<Pair<String, String>>()
    private var ids = 0
    private val early = 1_600_000_000_000L // before any file write of MemFs
    private val late = 1_800_000_000_000L // after them

    /** One run, applied as ContactsMirror.sync applies it: files first, then the provider. */
    private fun run(now: Long = late): SyncPlan {
        book.scan()
        val plan = MirrorSync.reconcile(book.contacts.value, book.modifiedById(), phone.read(), synced, now, ZoneOffset.UTC) { "new%03d".format(++ids) }
        for (op in plan.files) when (op) {
            is FileOp.Create -> assertEquals(op.contact.id, book.save(op.contact, now).id)
            is FileOp.Write -> book.save(op.contact, now)
            is FileOp.Trash -> trash.add(op.contact.path to book.text(op.contact)!!)
                .also { book.delete(op.contact) }
        }
        phone.apply(plan.provider)
        synced = plan.synced
        return plan
    }

    private fun assertSettled() {
        val again = run()
        assertTrue("second run not empty: $again", again.isEmpty)
        assertTrue(phone.raws.values.none { it.dirty || it.sourceId == null })
        assertEquals(book.contacts.value.map { it.id }.toSet(), phone.raws.values.map { it.sourceId }.toSet())
        book.contacts.value.forEach { assertEquals(MirrorPlan.hash(it), phone.of(it.id).hash) }
    }

    private fun marie(): Contact = book.save(
        Contact(
            firstName = "Marie", lastName = "Dupont", phones = listOf(Phone("06 12 34 56 78", "mobile"), Phone("01 23 45 67 89", "autre")),
            emails = listOf("marie@example.org"), notes = "Rencontrée à Lyon.",
        ),
    )

    @Test
    fun createdOnThePhone() {
        val raw = phone.create(
            listOf(
                MirrorRow.Name("Jean", "Martin", "Jean Martin"), MirrorRow.Tel("06 11 22 33 44", PhoneKind.MOBILE, null),
                MirrorRow.Tel("04 00 00 00 00", PhoneKind.CUSTOM, "Maman"), MirrorRow.Mail("jean@example.org"),
                MirrorRow.Postal("3 rue Neuve\n69001 Lyon"), MirrorRow.Birthday("--05-14"), MirrorRow.Note("Voisin"),
            ),
            at = late, starred = true,
        )
        val plan = run()
        assertEquals(1, plan.files.size)
        val c = book.contacts.value.single()
        assertEquals("new001", c.id)
        assertTrue(c.path.startsWith("contacts/martin-jean-"))
        assertEquals(listOf(Phone("06 11 22 33 44", "mobile"), Phone("04 00 00 00 00", "Maman")), c.phones)
        assertEquals(listOf("jean@example.org"), c.emails)
        assertEquals("3 rue Neuve, 69001 Lyon", c.address)
        assertEquals(Birthday(null, 5, 14), c.birthday)
        assertEquals("Voisin", c.notes)
        assertTrue(c.favorite)
        assertEquals("new001", phone.raws.getValue(raw).sourceId)
        assertFalse(phone.raws.getValue(raw).dirty)
        assertSettled()
    }

    @Test
    fun anEmptyRawContactIsLeftAlone() {
        phone.create(emptyList(), at = late)
        assertTrue(run().isEmpty)
        assertTrue(book.contacts.value.isEmpty())
    }

    @Test
    fun editedOnThePhoneKeepsTheRestOfTheFile() {
        val m = marie()
        fs.writeText(m.path, fs.readText(m.path)!!.replace("first_name:", "company: \"ACME\"\nfirst_name:"))
        run()
        phone.edit(m.id, late) { rows ->
            rows.map { if (it is MirrorRow.Tel && it.kind == PhoneKind.MOBILE) MirrorRow.Tel("07 98 76 54 32", PhoneKind.WORK, null) else it } +
                MirrorRow.Birthday("1990-05-14")
        }
        val plan = run()
        assertEquals(listOf(m.id), plan.files.map { (it as FileOp.Write).contact.id })
        val c = book.byId(m.id)!!
        // the untouched number keeps its "autre" spelling (the phone says "other")
        assertEquals(listOf(Phone("07 98 76 54 32", "travail"), Phone("01 23 45 67 89", "autre")), c.phones)
        assertEquals(Birthday(1990, 5, 14), c.birthday)
        assertEquals("Rencontrée à Lyon.", c.notes)
        assertTrue(fs.readText(m.path)!!.contains("company: \"ACME\""))
        assertEquals(m.path, c.path)
        assertSettled()
    }

    @Test
    fun anEditThatChangesNothingOnlyClearsDirty() {
        val m = marie()
        run()
        val before = fs.readText(m.path)
        phone.edit(m.id, late) { rows -> rows.map { if (it is MirrorRow.Tel) it.copy(number = it.number.replace(" ", "")) else it } }
        val plan = run()
        assertTrue(plan.files.isEmpty())
        assertEquals(listOf(MirrorOp.Ack::class), plan.provider.map { it::class })
        assertEquals(before, fs.readText(m.path))
        assertSettled()
    }

    @Test
    fun deletedOnThePhoneGoesToTheTrash() {
        val m = marie()
        val text = fs.readText(m.path)
        run()
        phone.delete(m.id)
        val plan = run()
        assertEquals(listOf(FileOp.Trash::class), plan.files.map { it::class })
        assertNull(fs.readText(m.path))
        assertEquals(listOf(m.path to text), trash)
        assertTrue(phone.raws.isEmpty())
        assertSettled()
        // undo: the file comes back and is copied again
        fs.writeText(trash[0].first, trash[0].second)
        run()
        assertEquals(1, phone.raws.size)
        assertSettled()
    }

    @Test
    fun deletedOnThePhoneButEditedInTheFileComesBack() {
        val m = marie()
        run()
        phone.delete(m.id)
        book.save(m.copy(notes = "Nouveau travail"))
        run()
        assertTrue(trash.isEmpty())
        assertEquals("Nouveau travail", book.byId(m.id)!!.notes)
        assertEquals(1, phone.raws.size)
        assertSettled()
    }

    @Test
    fun editedOnBothSidesThePhoneIsNewer() {
        val m = marie()
        run()
        book.save(m.copy(emails = listOf("marie@travail.example")))
        phone.edit(m.id, late) { rows -> rows.map { if (it is MirrorRow.Name) MirrorRow.Name("Marie-Claire", "Dupont", "Marie-Claire Dupont") else it } }
        val plan = run(now = late)
        assertEquals(1, plan.conflicts)
        val c = book.byId(m.id)!!
        assertEquals("Marie-Claire", c.firstName)
        assertEquals(listOf("marie@example.org"), c.emails)
        assertTrue(c.notes, c.notes.startsWith("Rencontrée à Lyon.\n\nVersion de Mémento remplacée le "))
        assertTrue(c.notes, c.notes.contains("- Nom : Marie Dupont"))
        assertTrue(c.notes, c.notes.contains("- E-mails : marie@travail.example"))
        // the phone gets the merged notes too
        assertTrue(phone.of(m.id).rows.contains(MirrorRow.Note(c.notes.trim())))
        assertSettled()
    }

    @Test
    fun editedOnBothSidesTheFileIsNewer() {
        val m = marie()
        run()
        phone.edit(m.id, early) { rows -> rows.filter { it !is MirrorRow.Mail } + MirrorRow.Mail("autre@example.org") }
        book.save(m.copy(lastName = "Durand"))
        val plan = run()
        assertEquals(1, plan.conflicts)
        val c = book.byId(m.id)!!
        assertEquals("Durand", c.lastName)
        assertEquals(listOf("marie@example.org"), c.emails)
        assertTrue(c.notes, c.notes.contains("Version du téléphone remplacée le "))
        assertTrue(c.notes, c.notes.contains("- E-mails : autre@example.org"))
        assertTrue(c.notes, c.notes.contains("- Nom : Marie Dupont"))
        assertTrue(phone.of(m.id).rows.contains(MirrorRow.Name("Marie", "Durand", "Marie Durand")))
        assertSettled()
    }

    @Test
    fun fileDeletedWhileThePhoneEditedIt() {
        val m = marie()
        book.save(Contact(firstName = "Paul")) // the folder does not read empty: the guard stays out of it
        run()
        book.delete(m)
        phone.edit(m.id, late) { rows -> rows + MirrorRow.Mail("nouveau@example.org") }
        run()
        val c = book.byId(m.id)!!
        assertEquals(listOf("marie@example.org", "nouveau@example.org"), c.emails)
        assertEquals("Marie", c.firstName)
        assertSettled()
    }

    @Test
    fun fileDeletedPhoneUntouchedRemovesTheCopy() {
        val m = marie()
        book.save(Contact(firstName = "Paul")) // the folder does not read empty: the guard stays out of it
        run()
        book.delete(m)
        val plan = run()
        assertEquals(1, plan.provider.count { it is MirrorOp.Delete })
        assertTrue(phone.raws.values.none { it.sourceId == m.id })
        assertSettled()
    }

    @Test
    fun anEmptyFolderNeverWipesThePhone() {
        val m = marie()
        run()
        fs.delete(m.path) // the folder reads empty (SAF trouble, sync in progress)
        phone.edit(m.id, late) { rows -> rows + MirrorRow.Mail("x@example.org") }
        val raw = phone.create(listOf(MirrorRow.Name("Paul", "", "Paul")), at = late)
        val plan = run()
        assertTrue(plan.keptBecauseEmpty)
        assertTrue(plan.provider.none { it is MirrorOp.Delete || it is MirrorOp.Update })
        // the contact created on the phone is still saved, the edited one waits (still dirty)
        assertEquals(listOf("Paul"), book.contacts.value.map { it.firstName })
        assertEquals("new001", phone.raws.getValue(raw).sourceId)
        assertTrue(phone.of(m.id).dirty)
        assertNotNull(synced[m.id])
    }

    @Test
    fun anInterruptedCreationIsNotDuplicated() {
        val raw = phone.create(listOf(MirrorRow.Name("Jean", "Martin", "Jean Martin")), at = late)
        book.scan()
        val plan = MirrorSync.reconcile(book.contacts.value, emptyMap(), phone.read(), synced, late, ZoneOffset.UTC) { "abc123" }
        val pending = mapOf(raw to "abc123")
        book.save((plan.files.single() as FileOp.Create).contact) // the provider batch then failed
        book.scan()
        val raws = MirrorSync.resume(phone.read(), pending, book.contacts.value.map { it.id }.toSet())
        val again = MirrorSync.reconcile(book.contacts.value, book.modifiedById(), raws, synced, late, ZoneOffset.UTC) { error("no new id") }
        assertTrue(again.files.isEmpty())
        assertEquals(listOf(MirrorOp.Ack::class), again.provider.map { it::class })
        phone.apply(again.provider)
        synced = again.synced
        assertSettled()
    }

    @Test
    fun phoneTypesAndAddresses() {
        assertEquals(PhoneKind.MOBILE to null, MirrorPlan.phoneType(2, null))
        assertEquals(PhoneKind.CUSTOM to "fax travail", MirrorPlan.phoneType(4, null))
        assertEquals(PhoneKind.CUSTOM to "Mamie", MirrorPlan.phoneType(0, "Mamie"))
        assertEquals(PhoneKind.OTHER to null, MirrorPlan.phoneType(0, "(2)"))
        val c = MirrorPlan.adopt(Contact(id = "x"), listOf(MirrorRow.Name("", "", "Garage"), MirrorRow.Postal(" 1 rue A ,\n\n 75001  Paris ")), false)
        assertEquals("Garage", c.firstName)
        assertEquals("1 rue A, 75001 Paris", c.address)
        // what the phone's rows give is what reading the saved file back gives
        val saved = book.save(c)
        assertEquals(MirrorPlan.hash(c), MirrorPlan.hash(book.byId(saved.id)!!))
    }

    // ---- data-loss regressions ----

    @Test
    fun aPhoneThatReadsBackEmptyNeverErasesTheFile() {
        val m = marie()
        run()
        val before = fs.readText(m.path)
        phone.edit(m.id, late) { emptyList() }
        val plan = run()
        assertTrue(plan.files.isEmpty())
        assertEquals(before, fs.readText(m.path))
        assertEquals(MirrorPlan.rows(book.byId(m.id)!!), phone.of(m.id).rows)
        assertSettled()
    }

    @Test
    fun aBirthdayThePhoneWroteOtherwiseNeverErasesTheFiles() {
        val m = book.save(marie().copy(birthday = Birthday(1990, 5, 14)))
        run()
        phone.edit(m.id, late) { rows -> rows.filter { it !is MirrorRow.Birthday } + MirrorRow.Birthday("14/05/1990") + MirrorRow.Mail("b@example.org") }
        run()
        val c = book.byId(m.id)!!
        assertEquals(Birthday(1990, 5, 14), c.birthday)
        assertEquals(listOf("marie@example.org", "b@example.org"), c.emails)
        assertTrue(c.notes, c.notes.contains("Anniversaire : 14/05/1990"))
        assertSettled()
    }

    @Test
    fun aSecondAddressGoesToTheNotes() {
        phone.create(
            listOf(MirrorRow.Name("Jean", "Martin", "Jean Martin"), MirrorRow.Postal("1 rue A\nParis"), MirrorRow.Postal("2 rue B\nLyon"), MirrorRow.Note("Voisin")),
            at = late,
        )
        run()
        val c = book.contacts.value.single()
        assertEquals("1 rue A, Paris", c.address)
        assertEquals("Voisin\n\nAutres informations du téléphone :\n- Adresse : 2 rue B, Lyon", c.notes)
        assertSettled()
        // an edit on the phone afterwards (the two addresses still there) keeps the notes as they are
        phone.edit(c.id, late) { rows -> rows + MirrorRow.Mail("j@example.org") }
        run()
        assertEquals(c.notes, book.byId(c.id)!!.notes)
        assertSettled()
    }

    @Test
    fun prefixMiddleNameAndSuffixAreKept() {
        assertEquals(MirrorRow.Name("Dr Jean Paul", "de la Fontaine Jr", "Dr Jean Paul de la Fontaine Jr"),
            MirrorPlan.nameRow("Dr", "Jean", "Paul", "de la Fontaine", "Jr", " Dr Jean Paul de la Fontaine Jr "))
        val c = MirrorPlan.adopt(Contact(id = "x"), listOf(MirrorPlan.nameRow("", "Jean", "", "de la Fontaine", "", "Jean de la Fontaine")), false)
        assertEquals("Jean" to "de la Fontaine", c.firstName to c.lastName)
    }

    @Test
    fun aStateOlderThanTheProviderIsNotAConflict() {
        val m = marie()
        run()
        val old = synced
        book.save(book.byId(m.id)!!.copy(notes = "Nouveau"))
        run() // the provider took it, then the state could not be written
        synced = old
        phone.edit(m.id, late) { rows -> rows.map { if (it is MirrorRow.Name) MirrorRow.Name("Marie-Claire", "Dupont", "Marie-Claire Dupont") else it } }
        val plan = run()
        assertEquals(0, plan.conflicts)
        val c = book.byId(m.id)!!
        assertEquals("Marie-Claire", c.firstName)
        assertEquals("Nouveau", c.notes)
        assertSettled()
    }

    @Test
    fun manyDeletionsAtOnceKeepTheFiles() {
        val all = (1..12).map { book.save(Contact(firstName = "P$it", phones = listOf(Phone("06 00 00 00 %02d".format(it))))) }
        run()
        all.forEach { phone.delete(it.id) }
        val plan = run()
        assertEquals(12, plan.keptBecauseMassDelete)
        assertTrue(plan.files.isEmpty())
        assertEquals(12, book.contacts.value.size)
        assertEquals(12, phone.raws.size)
        assertSettled()
    }

    @Test
    fun aFileThatIsNotMirroredKeepsItsPhoneCopy() {
        val m = marie()
        book.save(Contact(firstName = "Paul"))
        run()
        fs.writeText(m.path, "---\nid: ${m.id}\n---\n") // a file whose fields could not be read
        phone.edit(m.id, late) { rows -> rows + MirrorRow.Mail("x@example.org") }
        val plan = run()
        assertTrue(plan.provider.none { it is MirrorOp.Delete })
        assertTrue(plan.files.isEmpty())
        assertTrue(phone.of(m.id).rows.contains(MirrorRow.Mail("x@example.org")))
    }

    @Test
    fun twoCopiesKeepTheEditedOne() {
        val m = marie()
        run()
        phone.duplicate(m.id, late) { rows -> rows + MirrorRow.Mail("dup@example.org") }
        run()
        assertTrue(book.byId(m.id)!!.emails.contains("dup@example.org"))
        assertSettled()
    }

    @Test
    fun theBookNeverHidesAFileItCouldNotRead() {
        val flaky = FlakyFs(MemFs())
        val b = ContactBook { flaky }
        val c = b.save(Contact(firstName = "Marie", lastName = "Dupont", id = "abc123"))
        flaky.broken.add(c.path)
        b.clear()
        b.scan()
        assertEquals(setOf(c.path), b.unreadable)
        // a contact re-created from the phone under the same id never replaces that file
        val e = runCatching { b.save(Contact(firstName = "Marie", lastName = "Dupont", id = "abc123")) }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
        flaky.broken.clear()
        b.scan()
        assertTrue(b.unreadable.isEmpty())
        val read = b.byId("abc123")!!
        assertFalse(b.changedOnDisk(read))
        flaky.inner.writeText(c.path, flaky.inner.readText(c.path)!! + "\nModifié sur le PC")
        assertTrue(b.changedOnDisk(read))
    }

    @Test
    fun theTrashKeepsWhatWasNeverShown() {
        val store = MirrorStore(tmp.newFolder("trash"))
        val day = 24 * 3600 * 1000L
        store.trash("contacts/a.md", "A", "a", 0)
        store.purge(30 * day) // deleted by the sync adapter a month ago, the app never opened since
        assertEquals(1, store.entries().size)
        assertEquals(listOf("A"), store.announce(30 * day).map { it.name })
        store.purge(36 * day)
        assertEquals(1, store.entries().size)
        store.purge(38 * day)
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun storeKeepsStateAndTrash() {
        val store = MirrorStore(tmp.newFolder("mirror"))
        store.write(MirrorStore.State(mapOf("a" to Synced("h1", 5)), mapOf(7L to "b")))
        val s = store.read()
        assertEquals(mapOf("a" to Synced("h1", 5)), s.synced)
        assertEquals(mapOf(7L to "b"), s.pending)
        val day = 24 * 3600 * 1000L
        store.trash("contacts/dupont-marie-a.md", "Marie\tDupont", "---\nid: a\n---\n", 1000)
        store.trash("contacts/old-b.md", "Old", "x", 1000 - 8 * day)
        val fresh = store.announce(2000)
        // never shown before: kept, however old
        assertEquals(listOf("Marie Dupont", "Old"), fresh.map { it.name })
        assertEquals("---\nid: a\n---\n", store.text(fresh[0]))
        assertTrue(store.announce(3000).isEmpty())
        store.remove(fresh[0])
        assertEquals(listOf("Old"), store.entries().map { it.name })
    }
}
