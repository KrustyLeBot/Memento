package app.memento.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorPlanTest {
    private fun contact(id: String, vararg phones: String) =
        Contact(id = id, path = "contacts/$id.md", firstName = "P$id", phones = phones.map { Phone.decode(it) })

    @Test
    fun rowsMapTheFields() {
        val c = Contact(
            id = "a", firstName = "Marie", lastName = "Dupont",
            phones = listOf(Phone("06", "mobile"), Phone("01", "domicile"), Phone("02", "travail"), Phone("03"), Phone("04", "Maman")),
            emails = listOf("m@example.org"), address = "12 rue", birthday = Birthday(null, 5, 14), notes = "note",
        )
        assertEquals(
            listOf(
                MirrorRow.Name("Marie", "Dupont", "Marie Dupont"),
                MirrorRow.Tel("06", PhoneKind.MOBILE, null), MirrorRow.Tel("01", PhoneKind.HOME, null),
                MirrorRow.Tel("02", PhoneKind.WORK, null), MirrorRow.Tel("03", PhoneKind.OTHER, null),
                MirrorRow.Tel("04", PhoneKind.CUSTOM, "Maman"),
                MirrorRow.Mail("m@example.org"), MirrorRow.Postal("12 rue"), MirrorRow.Birthday("--05-14"), MirrorRow.Note("note"),
            ),
            MirrorPlan.rows(c),
        )
    }

    @Test
    fun hashIgnoresWhatIsNotMirrored() {
        val c = contact("a", "06")
        assertEquals(MirrorPlan.hash(c), MirrorPlan.hash(c.copy(updated = 99, uid = "x", path = "other")))
        assertNotEquals(MirrorPlan.hash(c), MirrorPlan.hash(c.copy(favorite = true)))
        assertNotEquals(MirrorPlan.hash(c), MirrorPlan.hash(c.copy(phones = listOf(Phone("07")))))
    }

    private fun plan(files: List<Contact>, raws: List<MirrorRaw>) =
        MirrorSync.reconcile(files, emptyMap(), raws, emptyMap(), 0, java.time.ZoneOffset.UTC) { error("no new contact expected") }

    @Test
    fun filesToPhoneInsertsUpdatesDeletesAndIsIdempotent() {
        val a = contact("a", "06")
        val b = contact("b", "07")
        val c = contact("c", "08")
        val existing = listOf(
            MirrorRaw(1, "a", MirrorPlan.hash(a)), // unchanged
            MirrorRaw(2, "b", "old"), // changed in the file
            MirrorRaw(3, "gone", "x"), // file deleted
            MirrorRaw(4, "a", MirrorPlan.hash(a)), // duplicate row
        )
        val p = plan(listOf(a, b, c, c.copy(path = "contacts/c.md.conflict1", conflict = true)), existing)
        assertTrue(p.files.isEmpty())
        assertEquals(
            setOf(MirrorOp.Delete(3), MirrorOp.Delete(4), MirrorOp.Update(2, b, MirrorPlan.hash(b)), MirrorOp.Insert(c, MirrorPlan.hash(c))),
            p.provider.toSet(),
        )
        // after applying, nothing left to do
        val after = listOf(MirrorRaw(1, "a", MirrorPlan.hash(a)), MirrorRaw(2, "b", MirrorPlan.hash(b)), MirrorRaw(7, "c", MirrorPlan.hash(c)))
        assertTrue(plan(listOf(a, b, c), after).isEmpty)
    }

    @Test
    fun batchesStayUnderTheLimitAndKeepAContactTogether() {
        val contacts = (1..300).map { contact("c$it", "06 00 00 00 ${it % 100}", "07 00 00 00 01") }
        val ops = plan(contacts, emptyList()).provider
        assertEquals(300, ops.size)
        val batches = MirrorPlan.batches(ops)
        assertTrue(batches.all { b -> b.sumOf { MirrorPlan.size(it) } <= MirrorPlan.MAX_BATCH })
        assertEquals(ops, batches.flatten())
        assertEquals(1200, ops.sumOf { MirrorPlan.size(it) }) // raw + name + 2 phones each
        assertEquals(3, batches.size)
    }

    @Test
    fun extraKindsAreReadAsLabelledLines() {
        val p = "vnd.android.cursor.item/"
        assertEquals(
            listOf(MirrorRow.Extra("Société", "ACME"), MirrorRow.Extra("Fonction", "Comptable"), MirrorRow.Extra("Service", "Paie")),
            MirrorPlan.extra("${p}organization", listOf("ACME", "1", "", "Comptable", "Paie")),
        )
        assertEquals(listOf(MirrorRow.Extra("Site web", "acme.example")), MirrorPlan.extra("${p}website", listOf("acme.example", "7")))
        assertEquals(listOf(MirrorRow.Extra("Surnom", "Jeannot")), MirrorPlan.extra("${p}nickname", listOf("Jeannot")))
        assertEquals(listOf(MirrorRow.Extra("Adresse SIP", "j@sip.example")), MirrorPlan.extra("${p}sip_address", listOf("j@sip.example")))
        assertEquals(listOf(MirrorRow.Extra("Messagerie (Skype)", "jean.m")), MirrorPlan.extra("${p}im", listOf("jean.m", "3", "", "", "3")))
        assertEquals(listOf(MirrorRow.Extra("Messagerie (Signal)", "jean")), MirrorPlan.extra("${p}im", listOf("jean", "3", "", "", "-1", "Signal")))
        assertEquals(listOf(MirrorRow.Extra("Relation (mère)", "Anne")), MirrorPlan.extra("${p}relation", listOf("Anne", "8")))
        assertEquals(listOf(MirrorRow.Extra("Relation (marraine)", "Lise")), MirrorPlan.extra("${p}relation", listOf("Lise", "0", "marraine")))
        assertEquals(listOf(MirrorRow.Extra("Date (anniversaire de mariage)", "2010-06-12")), MirrorPlan.extra("${p}contact_event", listOf("2010-06-12", "1")))
        assertEquals(listOf(MirrorRow.Extra("Date (fête)", "--07-26")), MirrorPlan.extra("${p}contact_event", listOf("--07-26", "0", "fête")))
        assertEquals(listOf(MirrorRow.Extra("E-mail (travail)", "j@acme.example")), MirrorPlan.extra("${p}email_v2", listOf("j@acme.example", "2")))
        // read as rows of their own (the birthday, an e-mail of the type Mémento writes) or not read (a photo)
        assertEquals(emptyList<MirrorRow>(), MirrorPlan.extra("${p}contact_event", listOf("1990-05-14", "3")))
        assertEquals(emptyList<MirrorRow>(), MirrorPlan.extra("${p}email_v2", listOf("j@example.org", "3")))
        assertEquals(emptyList<MirrorRow>(), MirrorPlan.extra("${p}photo", List(10) { "" }))
        assertEquals(emptyList<MirrorRow>(), MirrorPlan.extra("${p}organization", List(10) { "" }))
        assertEquals(emptyList<MirrorRow>(), MirrorPlan.extra("${p}phone_v2", listOf("06", "2")))
    }

    @Test
    fun updatesTouchOnlyTheKindsMementoOwns() {
        // the public ContactsContract values (compile-time constants, inlined)
        assertEquals(
            listOf(
                android.provider.ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                android.provider.ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
                android.provider.ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
            ),
            MirrorPlan.OWNED_MIMES,
        )
        listOf("organization", "website", "nickname", "im", "relation", "sip_address", "contact_event", "photo", "group_membership").forEach {
            assertTrue(it, "vnd.android.cursor.item/$it" !in MirrorPlan.OWNED_MIMES)
        }
    }

    @Test
    fun extrasAreMergedIntoTheNotesIdempotently() {
        val a = MirrorPlan.withExtras("Voisin", listOf("Société : ACME"))
        assertEquals("Voisin\n\nAutres informations du téléphone :\n- Société : ACME", a)
        assertEquals(a, MirrorPlan.withExtras(a, listOf("Société : ACME")))
        assertEquals("$a\n- Surnom : Jeannot", MirrorPlan.withExtras(a, listOf("Société : ACME", "Surnom : Jeannot")))
        assertEquals("Autres informations du téléphone :\n- Surnom : J", MirrorPlan.withExtras("", listOf("Surnom : J")))
        assertEquals("Voisin", MirrorPlan.withExtras("Voisin", emptyList()))
        // the block in the middle of the notes (text added after it in Mémento) is found and extended in place
        val mid = "Voisin\n\nAutres informations du téléphone :\n- Société : ACME\n\nVu en mai."
        assertEquals(mid, MirrorPlan.withExtras(mid, listOf("Société : ACME")))
        assertEquals("Voisin\n\nAutres informations du téléphone :\n- Société : ACME\n- Surnom : J\n\nVu en mai.", MirrorPlan.withExtras(mid, listOf("Surnom : J")))
        // a value typed over two lines stays one line of the block; adopting the result again changes nothing
        val c = MirrorPlan.adopt(Contact(id = "x"), listOf(MirrorRow.Name("A", "B", "A B"), MirrorRow.Extra("Société", "ACME\nLyon")), false)
        assertEquals("Autres informations du téléphone :\n- Société : ACME, Lyon", c.notes)
        assertEquals(c, MirrorPlan.adopt(c, MirrorPlan.rows(c) + MirrorRow.Extra("Société", "ACME\nLyon"), false))
    }
}
