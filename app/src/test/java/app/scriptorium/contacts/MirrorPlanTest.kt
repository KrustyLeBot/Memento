package app.scriptorium.contacts

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

    @Test
    fun diffInsertsUpdatesDeletesAndIsIdempotent() {
        val a = contact("a", "06")
        val b = contact("b", "07")
        val c = contact("c", "08")
        val existing = listOf(
            MirrorRaw(1, "a", MirrorPlan.hash(a)), // unchanged
            MirrorRaw(2, "b", "old"), // changed in the file
            MirrorRaw(3, "gone", "x"), // file deleted
            MirrorRaw(4, "a", MirrorPlan.hash(a)), // duplicate row
            MirrorRaw(5, null, null), // not ours
            MirrorRaw(6, "c", MirrorPlan.hash(c), deleted = true), // deleted in the phone: comes back
        )
        val ops = MirrorPlan.diff(listOf(a, b, c, c.copy(path = "contacts/c.md.conflict1", conflict = true)), existing)
        assertEquals(
            setOf(MirrorOp.Delete(3), MirrorOp.Delete(4), MirrorOp.Delete(5), MirrorOp.Delete(6),
                MirrorOp.Update(2, b, MirrorPlan.hash(b)), MirrorOp.Insert(c, MirrorPlan.hash(c))),
            ops.toSet(),
        )
        // after applying, nothing left to do
        val after = listOf(MirrorRaw(1, "a", MirrorPlan.hash(a)), MirrorRaw(2, "b", MirrorPlan.hash(b)), MirrorRaw(7, "c", MirrorPlan.hash(c)))
        assertTrue(MirrorPlan.diff(listOf(a, b, c), after).isEmpty())
        // an edit made in the phone's contacts app (dirty) is overwritten
        assertEquals(listOf(MirrorOp.Update(1, a, MirrorPlan.hash(a))), MirrorPlan.diff(listOf(a), listOf(MirrorRaw(1, "a", MirrorPlan.hash(a), dirty = true))))
    }

    @Test
    fun batchesStayUnderTheLimitAndKeepAContactTogether() {
        val contacts = (1..300).map { contact("c$it", "06 00 00 00 ${it % 100}", "07 00 00 00 01") }
        val ops = MirrorPlan.diff(contacts, emptyList())
        assertEquals(300, ops.size)
        val batches = MirrorPlan.batches(ops)
        assertTrue(batches.all { b -> b.sumOf { MirrorPlan.size(it) } <= MirrorPlan.MAX_BATCH })
        assertEquals(ops, batches.flatten())
        assertEquals(1200, ops.sumOf { MirrorPlan.size(it) }) // raw + name + 2 phones each
        assertEquals(3, batches.size)
    }
}
