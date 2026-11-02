package app.scriptorium.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regressions found in review: each test failed before its fix. */
class ContactsRegressionTest {
    private fun res(path: String): String =
        listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.isFile }.readText()

    @Test
    fun mirrorDeclarationsMatch() {
        val manifest = res("AndroidManifest.xml")
        // ContentResolver.setIsSyncable / setSyncAutomatically throw a SecurityException without it
        assertTrue(manifest.contains("android.permission.WRITE_SYNC_SETTINGS"))
        val type = "android:accountType=\"${MirrorPlan.ACCOUNT_TYPE}\""
        assertTrue(res("res/xml/authenticator.xml").contains(type))
        assertTrue(res("res/xml/syncadapter.xml").contains(type))
    }

    @Test
    fun longFoldedPhotosAreSkippedQuickly() {
        val photo = "PHOTO;ENCODING=b;TYPE=JPEG:" + "A".repeat(74) + "\r\n" + (" " + "B".repeat(74) + "\r\n").repeat(4000)
        val card = "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Dupont;Marie;;;\r\n${photo}TEL:0612345678\r\nEND:VCARD\r\n"
        val start = System.nanoTime()
        val r = VCard.parse(card.repeat(40))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(40, r.contacts.size)
        assertEquals("0612345678", r.contacts[0].phones.single().number)
        assertTrue("took $ms ms", ms < 3000)
    }

    @Test
    fun labelsThatWouldNotReadBackAreCleaned() {
        val text = "BEGIN:VCARD\nVERSION:3.0\nN:X;Y\nitem1.TEL:06 00 00 00 01\nitem1.X-ABLabel:2\n" +
            "item2.TEL:06 00 00 00 02\nitem2.X-ABLabel:Pro (ancien)\nEND:VCARD\n"
        val phones = VCard.parse(text).contacts.single().phones
        assertEquals(listOf(Phone("06 00 00 00 01", null), Phone("06 00 00 00 02", "Pro ancien")), phones)
        // whatever the label, the stored line reads back as the same number
        listOf("2", "a (b)", "(x)", "  ").forEach { label ->
            assertEquals("06 00 00 00 03", Phone.decode(Phone("06 00 00 00 03", label).encode()).number)
        }
    }

    @Test
    fun dialableDropsTheTrunkZero() {
        assertEquals("+33 1 98 76 54 32", Phones.dialable("+33 (0)1 98 76 54 32"))
        assertEquals("+44 20 7946 0000", Phones.dialable("+44 (0) 20 7946 0000"))
        assertEquals("06 12 34 56 78", Phones.dialable("06 12 34 56 78"))
        assertEquals("(06) 12-34-56-78", Phones.dialable("(06) 12-34-56-78"))
        val row = MirrorPlan.rows(Contact(id = "a", firstName = "A", phones = listOf(Phone("+33 (0)6 12 34 56 78", "mobile"))))[1]
        assertEquals(MirrorRow.Tel("+33 6 12 34 56 78", PhoneKind.MOBILE, null), row)
    }

    @Test
    fun googleStarredBecomesAFavourite() {
        val text = "BEGIN:VCARD\nVERSION:3.0\nN:X;Y\nCATEGORIES:myContacts,starred\nEND:VCARD\n" +
            "BEGIN:VCARD\nVERSION:3.0\nN:Z;W\nCATEGORIES:myContacts\nEND:VCARD\n"
        val r = VCard.parse(text)
        assertEquals(listOf(true, false), r.contacts.map { it.favorite })
        assertEquals(mapOf("CATEGORIES" to 2), r.ignored)
    }
}
