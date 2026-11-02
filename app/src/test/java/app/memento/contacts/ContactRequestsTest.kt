package app.memento.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactRequestsTest {
    private fun extras(vararg pairs: Pair<String, Any?>): (String) -> Any? = pairs.toMap()::get
    private val none: (String) -> Any? = { null }

    private fun classify(action: String?, type: String?, uri: String? = null, ex: (String) -> Any? = none, data: List<DataRow> = emptyList()) =
        uri?.removePrefix("content://")?.split('/').let { parts ->
            ContactRequests.classify(action, type, parts?.first(), parts?.drop(1), ex, data)
        }

    @Test
    fun dialerCreateContact() {
        val r = classify(
            ContactRequests.ACTION_INSERT, ContactRequests.TYPE_CONTACT_DIR,
            ex = extras(ContactRequests.PHONE to "06 12 34 56 78", ContactRequests.PHONE_TYPE to 2),
        )
        assertEquals(ContactRequest.Insert(Contact(phones = listOf(Phone("06 12 34 56 78", PhoneLabels.MOBILE))), offerExisting = false), r)
    }

    @Test
    fun insertOrEditOffersExisting() {
        val r = classify(ContactRequests.ACTION_INSERT_OR_EDIT, ContactRequests.TYPE_CONTACT_ITEM, ex = extras(ContactRequests.EMAIL to "a@b.fr"))
        assertEquals(ContactRequest.Insert(Contact(emails = listOf("a@b.fr")), offerExisting = true), r)
    }

    @Test
    fun allInsertExtras() {
        val d = ContactRequests.draft(
            extras(
                ContactRequests.NAME to " Jean de La Fontaine ", ContactRequests.PHONE to "0102030405", ContactRequests.PHONE_TYPE to "Maman",
                ContactRequests.SECONDARY_PHONE to "0605040302", ContactRequests.SECONDARY_PHONE_TYPE to 3,
                ContactRequests.TERTIARY_PHONE to "0700000000", ContactRequests.TERTIARY_PHONE_TYPE to "1",
                ContactRequests.EMAIL to "j@f.fr", ContactRequests.SECONDARY_EMAIL to "", ContactRequests.TERTIARY_EMAIL to "x@y.fr",
                ContactRequests.POSTAL to "1 rue X\n75001 Paris", ContactRequests.NOTES to "Fabuliste",
                ContactRequests.COMPANY to "Académie", ContactRequests.JOB_TITLE to "Membre",
            ),
            emptyList(),
        )
        assertEquals("Jean", d.firstName)
        assertEquals("de La Fontaine", d.lastName)
        assertEquals(listOf(Phone("0102030405", "Maman"), Phone("0605040302", PhoneLabels.WORK), Phone("0700000000", PhoneLabels.HOME)), d.phones)
        assertEquals(listOf("j@f.fr", "x@y.fr"), d.emails)
        assertEquals("1 rue X, 75001 Paris", d.address)
        assertEquals("Fabuliste\nEntreprise : Académie (Membre)", d.notes)
    }

    @Test
    fun dataExtraFillsWhatTheExtrasLack() {
        val d = ContactRequests.draft(
            extras(ContactRequests.PHONE to "06 12 34 56 78"),
            listOf(
                DataRow(ContactRequests.MIME_NAME, mapOf("data2" to "Marie", "data3" to "Dupont")),
                DataRow(ContactRequests.MIME_PHONE, mapOf("data1" to "+33 6 12 34 56 78", "data2" to "2")),
                DataRow(ContactRequests.MIME_EMAIL, mapOf("data1" to "m@d.fr")),
            ),
        )
        assertEquals("Marie", d.firstName)
        assertEquals("Dupont", d.lastName)
        assertEquals(1, d.phones.size) // the same number twice
        assertEquals(listOf("m@d.fr"), d.emails)
    }

    @Test
    fun rowsOfAnotherAccount() {
        val c = ContactRequests.fromRows(
            listOf(
                DataRow(ContactRequests.MIME_NAME, mapOf("data1" to "Paul Martin")),
                DataRow(ContactRequests.MIME_PHONE, mapOf("data1" to "0612345678", "data2" to "2")),
                DataRow(ContactRequests.MIME_PHONE, mapOf("data1" to "+33612345678", "data2" to "1")),
                DataRow(ContactRequests.MIME_PHONE, mapOf("data1" to "0145", "data2" to "0", "data3" to "Bureau (2)")),
                DataRow(ContactRequests.MIME_EMAIL, mapOf("data1" to "P@M.fr")),
                DataRow(ContactRequests.MIME_EMAIL, mapOf("data1" to "p@m.fr")),
                DataRow(ContactRequests.MIME_POSTAL, mapOf("data4" to "3 place Y", "data7" to "Lyon", "data9" to "69001")),
                DataRow(ContactRequests.MIME_POSTAL, mapOf("data1" to "Chalet\nChamonix")),
                DataRow(ContactRequests.MIME_EVENT, mapOf("data1" to "--05-14", "data2" to "3")),
                DataRow(ContactRequests.MIME_NOTE, mapOf("data1" to "ami")),
                DataRow(ContactRequests.MIME_ORGANIZATION, mapOf("data1" to "ACME")),
                DataRow("vnd.android.cursor.item/photo", mapOf("data1" to null)),
            ),
        )
        assertEquals("Paul", c.firstName)
        assertEquals("Martin", c.lastName)
        assertEquals(listOf(Phone("0612345678", PhoneLabels.MOBILE), Phone("0145", "Bureau 2")), c.phones)
        assertEquals(listOf("P@M.fr"), c.emails)
        assertEquals("3 place Y, 69001 Lyon", c.address)
        assertEquals(Birthday(null, 5, 14), c.birthday)
        assertEquals("ami\nEntreprise : ACME\nAdresse : Chalet, Chamonix", c.notes)
    }

    @Test
    fun viewEditAndInviteName_theContact() {
        assertEquals(
            ContactRequest.View(ContactRef.Lookup("0r1-ABC", 42)),
            classify(ContactRequests.ACTION_VIEW, null, "content://com.android.contacts/contacts/lookup/0r1-ABC/42"),
        )
        assertEquals(
            ContactRequest.View(ContactRef.ContactId(7)),
            classify(ContactRequests.ACTION_QUICK_CONTACT, ContactRequests.TYPE_CONTACT_ITEM, "content://com.android.contacts/contacts/7"),
        )
        assertEquals(
            ContactRequest.Edit(ContactRef.RawId(9)),
            classify(ContactRequests.ACTION_EDIT, ContactRequests.TYPE_RAW_ITEM, "content://com.android.contacts/raw_contacts/9"),
        )
        assertEquals(
            ContactRequest.Invite(ContactRef.Lookup("k", null)),
            classify(ContactRequests.ACTION_INVITE, null, "content://com.android.contacts/contacts/lookup/k"),
        )
        assertEquals(ContactRequest.AllContacts, classify(ContactRequests.ACTION_VIEW, ContactRequests.TYPE_CONTACT_DIR, "content://com.android.contacts/contacts"))
    }

    @Test
    fun notContactRequests() {
        // a vCard is the import's, whatever its URI
        assertNull(classify(ContactRequests.ACTION_VIEW, "text/x-vcard", "content://com.android.contacts/contacts/as_vcard/k"))
        assertNull(classify(ContactRequests.ACTION_VIEW, null, "content://com.android.contacts/contacts/as_vcard/k"))
        assertNull(classify(ContactRequests.ACTION_VIEW, "text/calendar", "content://media/external/file/3"))
        assertNull(classify("android.intent.action.SEND", "text/plain"))
        assertNull(classify("android.intent.action.MAIN", null))
        assertNull(classify(ContactRequests.ACTION_INSERT, null)) // nothing at all to insert
        assertNull(classify(ContactRequests.ACTION_EDIT, ContactRequests.TYPE_CONTACT_ITEM, "content://com.android.contacts/contacts/lookup"))
    }

    @Test
    fun addToExistingKeepsWhatIsThere() {
        val existing = Contact(id = "a", firstName = "Marie", phones = listOf(Phone("06 12 34 56 78", PhoneLabels.MOBILE)), address = "12 rue")
        val draft = Contact(phones = listOf(Phone("+33612345678"), Phone("0145")), emails = listOf("m@d.fr"), address = "Autre")
        val merged = ContactRequests.addTo(existing, draft)
        assertEquals("a", merged.id)
        assertEquals(listOf(Phone("06 12 34 56 78", PhoneLabels.MOBILE), Phone("0145")), merged.phones)
        assertEquals(listOf("m@d.fr"), merged.emails)
        assertEquals("12 rue", merged.address)
        assertTrue(merged.notes.contains("Adresse : Autre"))
    }

    @Test
    fun splitAndLabels() {
        assertEquals("Cher" to "", ContactRequests.splitName("Cher"))
        assertEquals("" to "", ContactRequests.splitName("  "))
        assertNull(ContactRequests.phoneLabel(7))
        assertEquals("fax", ContactRequests.phoneLabel(4))
        assertNull(ContactRequests.phoneLabel(null))
    }
}
