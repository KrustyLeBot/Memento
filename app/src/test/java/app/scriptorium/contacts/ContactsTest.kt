package app.scriptorium.contacts

import app.scriptorium.NoteFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_793_000_000_000L

@Suppress("UNCHECKED_CAST")
class ContactsTest {
    private fun vcard(name: String) {
        val expected = Vectors.json("$name.json") as Map<String, Any?>
        val result = VCard.parse(Vectors.bytes("$name.vcf"))
        assertEquals(name, (expected["contacts"] as List<Map<String, Any?>>).map { LinkedHashMap(it) }, result.contacts.map { Vectors.fields(it) })
        assertEquals(name, (expected["bad"] as Number).toInt(), result.bad)
        assertEquals(name, (expected["empty"] as Number).toInt(), result.empty)
        assertEquals(name, (expected["ignored"] as Map<String, Number>).mapValues { it.value.toInt() }, result.ignored)
        (expected["blank"] as Number?)?.let { assertEquals(it.toInt(), ContactImport.preview(result, emptyList()).blank) }
    }

    @Test fun googleExport() = vcard("google")
    @Test fun android21QuotedPrintable() = vcard("android21")
    @Test fun proton40() = vcard("proton40")
    @Test fun appleGroupedLabels() = vcard("apple")

    @Test
    fun crlfAndWindows1252() {
        val crlf = Vectors.text("google.vcf").replace("\n", "\r\n")
        assertEquals(VCard.parse(Vectors.text("google.vcf")), VCard.parse(crlf))
        val latin = "BEGIN:VCARD\nVERSION:3.0\nN:Lefèvre;Hélène;;;\nEND:VCARD\n".toByteArray(charset("windows-1252"))
        assertEquals("Hélène", VCard.parse(latin).contacts.single().firstName)
    }

    @Test
    fun phoneNormalisation() {
        val v = Vectors.json("phones.json") as Map<String, Any?>
        (v["same"] as List<List<String>>).forEach { group ->
            assertEquals(group.toString(), 1, group.map { Phones.normalize(it) }.toSet().size)
        }
        (v["normalized"] as List<List<String>>).forEach { (input, out) -> assertEquals(input, out, Phones.normalize(input)) }
        (v["search"] as List<List<Any>>).forEach { (q, number, found) ->
            assertEquals("$q in $number", found, ContactList.matches(Contact(id = "x", phones = listOf(Phone(number as String))), q as String))
        }
    }

    @Test
    fun sectionsSearchAndBirthdays() {
        val v = Vectors.json("list.json") as Map<String, Any?>
        val contacts = (v["contacts"] as List<Map<String, Any?>>).map { Vectors.contact(it) }
        fun sections(firstFirst: Boolean) = ContactList.sections(contacts, firstFirst).map { listOf(it.title, it.contacts.map { c -> c.id }) }
        assertEquals(v["sections_last_first"], sections(false))
        assertEquals(v["sections_first_first"], sections(true))
        (v["search"] as List<List<Any>>).forEach { (q, ids) ->
            assertEquals(q as String, ids, ContactList.search(contacts, q, false).map { it.id })
        }
        assertEquals(v["birthdays"], birthdays(contacts).map { listOf(it.contactId, it.name, it.birthday.encode()) })
    }

    @Test
    fun birthdayForms() {
        assertEquals(Birthday(1990, 5, 14), Birthday.parse("1990-05-14"))
        assertEquals(Birthday(1990, 5, 14), Birthday.parse("19900514"))
        assertEquals(Birthday(null, 5, 14), Birthday.parse("--0514"))
        assertEquals(Birthday(null, 5, 14), Birthday.parse("--05-14"))
        assertEquals(Birthday(1990, 5, 14), Birthday.parse("1990-05-14T00:00:00Z"))
        assertNull(Birthday.parse("1990-02-30"))
        assertNull(Birthday.parse("circa 1800"))
        assertEquals(Birthday(null, 2, 29), Birthday.parse("--02-29"))
        assertEquals(java.time.LocalDate.of(2027, 2, 28), Birthday(null, 2, 29).occurrence(2027))
        assertEquals(36, Birthday(1990, 5, 14).ageIn(2026))
    }

    @Test
    fun duplicatesAndMerge() {
        val v = Vectors.json("duplicates.json") as Map<String, Any?>
        val contacts = (v["contacts"] as List<Map<String, Any?>>).map { Vectors.contact(it) }
        assertEquals(v["groups"], Duplicates.groups(contacts).map { g -> g.map { it.id } })
        val m = v["merge"] as Map<String, Any?>
        val keep = contacts.first { it.id == m["keep"] }
        val others = (m["others"] as List<String>).map { id -> contacts.first { it.id == id } }
        assertEquals(m["expected"], Vectors.fields(Duplicates.merge(keep, others)))
    }

    @Test
    fun fileFormat() {
        val note = NoteFormat.parse("contacts/dupont-marie-p4x8q1.md", Vectors.text("dupont-marie-p4x8q1.md"), 0)
        val c = ContactFormat.fromNote(note)
        val e = Vectors.json("dupont-marie-p4x8q1.json") as Map<String, Any?>
        assertEquals(e["id"], c.id)
        assertEquals(e["import"], c.importId)
        assertEquals(Vectors.contact(e).copy(id = c.id), c.copy(importId = null, path = "", base = null, created = 0, updated = 0))
        // unknown keys stay, the contact keys are rewritten in place of the old ones
        val again = ContactFormat.fromNote(NoteFormat.parse(c.path, NoteFormat.serialize(ContactFormat.toNote(c)), 0))
        assertEquals(c.copy(base = null), again.copy(base = null))
        assertTrue(NoteFormat.serialize(ContactFormat.toNote(c)).contains("aliases: [Marie D.]\n"))
        assertEquals(e["extra"], again.base!!.extra.filter { it.startsWith("aliases") })
        assertEquals("Marie Dupont", ContactFormat.toNote(c).title)
    }

    @Test
    fun fileNames() {
        assertEquals("contacts/dupont-marie-abc123.md", ContactFormat.fileNameFor(Contact(firstName = "Marie", lastName = "Dupont"), "abc123"))
        assertEquals("contacts/boulangerie-abc123.md", ContactFormat.fileNameFor(Contact(firstName = "Boulangerie"), "abc123"))
        assertEquals("contacts/contact-abc123.md", ContactFormat.fileNameFor(Contact(phones = listOf(Phone("06"))), "abc123"))
    }

    @Test
    fun exportImportRoundTrip() {
        val contacts = (VCard.parse(Vectors.bytes("google.vcf")).contacts + VCard.parse(Vectors.bytes("android21.vcf")).contacts +
            VCard.parse(Vectors.bytes("proton40.vcf")).contacts + VCard.parse(Vectors.bytes("apple.vcf")).contacts)
            .mapIndexed { i, c -> if (i == 0) c.copy(favorite = true) else c }
        val text = VCard.write(contacts)
        text.split("\r\n").forEach { assertTrue(it, it.toByteArray().size <= 75) }
        val back = VCard.parse(text)
        assertEquals(0, back.bad)
        assertEquals(contacts.map { Vectors.fields(it) }, back.contacts.map { Vectors.fields(it) })
    }

    @Test
    fun importPoliciesAndUndo() {
        val fs = MemFs()
        val book = ContactBook { fs }
        val marie = book.save(Contact(firstName = "Marie", lastName = "Dupont", phones = listOf(Phone("06 12 34 56 78", "mobile")), notes = "à moi"))
        val before = fs.files.toMap()
        val result = VCard.parse(Vectors.bytes("google.vcf"))
        val preview = ContactImport.preview(result, book.contacts.value)
        assertEquals(4, preview.cards)
        assertEquals(1, preview.duplicates)
        var n = 0
        fun id() = "imp%03d".format(++n)

        // keep both: everything created, marked with the import, and undo removes exactly that
        val keep = ContactImport.plan(result, book.contacts.value, DuplicatePolicy.KEEP_BOTH, "I1", NOW) { id() }
        val out = book.apply(keep, "I1", result)
        assertEquals(4, out.created.size)
        assertEquals(5, book.contacts.value.size)
        assertTrue(out.created.all { fs.files[it]!!.contains("import: I1") })
        book.undo(out)
        assertEquals(before, fs.files)

        // skip: the duplicate is left out
        val skip = ContactImport.plan(result, book.contacts.value, DuplicatePolicy.SKIP, "I2", NOW) { id() }
        assertEquals(1, skip.skipped)
        assertEquals(3, skip.creates.size)

        // update: merged into the existing contact, then undo puts the file back as it was
        val upd = ContactImport.plan(result, book.contacts.value, DuplicatePolicy.UPDATE, "I3", NOW) { id() }
        assertEquals(1, upd.updates.size)
        val out3 = book.apply(upd, "I3", result)
        val merged = book.byId(marie.id)!!
        assertEquals(4, merged.phones.size)
        assertTrue(merged.notes.startsWith("à moi"))
        assertEquals("a1b2c3d4@example.org", merged.uid)
        assertFalse(fs.files[marie.path]!!.contains("import:"))
        book.undo(out3)
        assertEquals(before, fs.files)

        // re-importing the same file with update changes nothing the second time
        val again = book.apply(ContactImport.plan(result, book.contacts.value, DuplicatePolicy.UPDATE, "I4", NOW) { id() }, "I4", result)
        val third = ContactImport.plan(result, book.contacts.value, DuplicatePolicy.UPDATE, "I5", NOW + 1000) { id() }
        assertEquals(0, third.creates.size)
        assertEquals(0, third.updates.size)
        assertEquals(4, third.unchanged)
        book.undo(again)
        assertEquals(before, fs.files)
    }

    @Test
    fun deleteRestoreAndMergeUndo() {
        val fs = MemFs()
        val book = ContactBook { fs }
        val a = book.save(Contact(firstName = "Luc", phones = listOf(Phone("06 00 00 00 01"))))
        val b = book.save(Contact(firstName = "Luc", emails = listOf("luc@example.org")))
        val before = fs.files.toMap()
        val raw = book.delete(a)!!
        assertEquals(1, book.contacts.value.size)
        book.restore(a.path, raw)
        assertEquals(before, fs.files)
        val texts = book.merge(a, listOf(b))
        assertEquals(1, book.contacts.value.size)
        assertEquals(listOf("luc@example.org"), book.contacts.value.single().emails)
        book.restoreAll(texts)
        assertEquals(before, fs.files)
        assertTrue(book.contacts.value.none { ContactFormat.isContactPath(it.path).not() })
    }
}
