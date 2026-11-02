package app.memento.contacts

/**
 * One `ContactsContract.Data` row (of another account's contact, or of the `Insert.DATA` extra): its MIME type
 * and its columns (`data1`…`data15`) as strings. Pure, so the mapping below is testable without Android.
 */
data class DataRow(val mimeType: String, val values: Map<String, String?>) {
    operator fun get(column: String): String? = values[column]?.trim()?.ifEmpty { null }
}

/** What another app asked of the contacts app, read from its intent (see [ContactRequests.classify]). */
sealed class ContactRequest {
    /** "Créer un contact" / "Ajouter aux contacts": [draft] prefilled from the extras. */
    data class Insert(val draft: Contact, val offerExisting: Boolean) : ContactRequest()
    data class View(val ref: ContactRef) : ContactRequest()
    data class Edit(val ref: ContactRef) : ContactRequest()
    /** "Ajouter à Mémento" in the phone's contacts app on another account's contact (`inviteContactActivity`). */
    data class Invite(val ref: ContactRef) : ContactRequest()
    /** The contacts list itself (`VIEW` on `contacts` without an id). */
    data object AllContacts : ContactRequest()
}

/** A contact of the phone's provider, as its URI names it. */
sealed class ContactRef {
    data class Lookup(val key: String, val contactId: Long?) : ContactRef()
    data class ContactId(val id: Long) : ContactRef()
    data class RawId(val id: Long) : ContactRef()
}

/**
 * The contacts intents other apps fire (dialer, messaging, the phone's contacts app), read without Android:
 * which request, which contact, and the `Insert` extras turned into a contact draft.
 */
object ContactRequests {
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_EDIT = "android.intent.action.EDIT"
    const val ACTION_INSERT = "android.intent.action.INSERT"
    const val ACTION_INSERT_OR_EDIT = "android.intent.action.INSERT_OR_EDIT"
    const val ACTION_QUICK_CONTACT = "android.provider.action.QUICK_CONTACT"
    const val ACTION_INVITE = "com.android.contacts.action.INVITE_CONTACT"

    const val TYPE_CONTACT_DIR = "vnd.android.cursor.dir/contact"
    const val TYPE_RAW_DIR = "vnd.android.cursor.dir/raw_contact"
    const val TYPE_CONTACT_ITEM = "vnd.android.cursor.item/contact"
    const val TYPE_RAW_ITEM = "vnd.android.cursor.item/raw_contact"
    /** The pre-2.0 `Contacts.People` types, still sent by a few apps. */
    const val TYPE_PERSON_DIR = "vnd.android.cursor.dir/person"
    const val TYPE_PERSON_ITEM = "vnd.android.cursor.item/person"
    private val TYPES = setOf(TYPE_CONTACT_DIR, TYPE_RAW_DIR, TYPE_CONTACT_ITEM, TYPE_RAW_ITEM, TYPE_PERSON_DIR, TYPE_PERSON_ITEM)

    private val AUTHORITIES = setOf("com.android.contacts", "contacts")

    /**
     * The request, or null when the intent is not one (a `.vcf` – including `contacts/as_vcard/…` – is the
     * vCard import's). [segments] are the data URI's path segments, null without a contacts URI.
     */
    fun classify(
        action: String?, type: String?, authority: String?, segments: List<String>?,
        extras: (String) -> Any?, data: List<DataRow>,
    ): ContactRequest? {
        val t = type?.lowercase()?.substringBefore(';')?.trim()
        val contactsUri = authority != null && authority in AUTHORITIES && segments != null
        if (t != null && t !in TYPES) return null
        if (t == null && !contactsUri && action != ACTION_INSERT) return null
        val ref = if (contactsUri) ref(segments!!) else null
        if (contactsUri && segments!!.any { it == "as_vcard" || it == "as_multi_vcard" }) return null
        return when (action) {
            ACTION_INSERT -> if (t == null && !contactsUri && !hasInsertExtras(extras)) null
            else ContactRequest.Insert(draft(extras, data), offerExisting = false)
            ACTION_INSERT_OR_EDIT -> ContactRequest.Insert(draft(extras, data), offerExisting = true)
            ACTION_VIEW, ACTION_QUICK_CONTACT -> when {
                ref != null -> ContactRequest.View(ref)
                t == TYPE_CONTACT_DIR || t == TYPE_RAW_DIR || (contactsUri && segments!!.size <= 1) -> ContactRequest.AllContacts
                else -> null
            }
            ACTION_EDIT -> ref?.let { ContactRequest.Edit(it) }
            ACTION_INVITE -> ref?.let { ContactRequest.Invite(it) }
            else -> null
        }
    }

    /** `contacts/lookup/<key>[/<id>]`, `contacts/<id>`, `raw_contacts/<id>`; null for anything else. */
    fun ref(segments: List<String>): ContactRef? {
        val s = segments.filter { it.isNotEmpty() }
        return when {
            s.size >= 3 && s[0] == "contacts" && s[1] == "lookup" -> ContactRef.Lookup(s[2], s.getOrNull(3)?.toLongOrNull())
            s.size >= 2 && s[0] == "contacts" -> s[1].toLongOrNull()?.let { ContactRef.ContactId(it) }
            // a legacy `people/<id>` is a raw contact id
            s.size >= 2 && (s[0] == "raw_contacts" || s[0] == "people") -> s[1].toLongOrNull()?.let { ContactRef.RawId(it) }
            else -> null
        }
    }

    // ---- ContactsContract.Intents.Insert ----

    const val NAME = "name"
    const val PHONETIC_NAME = "phonetic_name"
    const val COMPANY = "company"
    const val JOB_TITLE = "job_title"
    const val NOTES = "notes"
    const val PHONE = "phone"
    const val PHONE_TYPE = "phone_type"
    const val SECONDARY_PHONE = "secondary_phone"
    const val SECONDARY_PHONE_TYPE = "secondary_phone_type"
    const val TERTIARY_PHONE = "tertiary_phone"
    const val TERTIARY_PHONE_TYPE = "tertiary_phone_type"
    const val EMAIL = "email"
    const val SECONDARY_EMAIL = "secondary_email"
    const val TERTIARY_EMAIL = "tertiary_email"
    const val POSTAL = "postal"
    const val IM_HANDLE = "im_handle"
    /** `ArrayList<ContentValues>` of data rows (read by the glue into [DataRow]s). */
    const val DATA = "data"

    private val INSERT_KEYS = listOf(NAME, COMPANY, JOB_TITLE, NOTES, PHONE, SECONDARY_PHONE, TERTIARY_PHONE, EMAIL, SECONDARY_EMAIL, TERTIARY_EMAIL, POSTAL)

    private fun hasInsertExtras(extras: (String) -> Any?) = INSERT_KEYS.any { (extras(it) as? CharSequence)?.isNotBlank() == true }

    /** The contact the extras (then the `DATA` rows) describe; empty fields stay empty. */
    fun draft(extras: (String) -> Any?, data: List<DataRow>): Contact {
        fun str(key: String) = (extras(key) as? CharSequence)?.toString()?.trim()?.ifEmpty { null }
        val (first, last) = splitName(str(NAME))
        val phones = listOf(PHONE to PHONE_TYPE, SECONDARY_PHONE to SECONDARY_PHONE_TYPE, TERTIARY_PHONE to TERTIARY_PHONE_TYPE)
            .mapNotNull { (k, type) -> str(k)?.let { Phone(it, phoneLabel(extras(type))) } }
        val emails = listOfNotNull(str(EMAIL), str(SECONDARY_EMAIL), str(TERTIARY_EMAIL))
        val extra = listOfNotNull(
            organization(str(COMPANY), str(JOB_TITLE)),
            str(IM_HANDLE)?.let { "Messagerie : $it" },
        )
        val notes = (listOfNotNull(str(NOTES)) + extra).joinToString("\n")
        val fromExtras = Contact(
            firstName = first, lastName = last, phones = phones, emails = emails,
            address = str(POSTAL)?.let(::oneLine), notes = notes,
        )
        return if (data.isEmpty()) fromExtras else Duplicates.merge(fromExtras, listOf(fromRows(data)))
    }

    /** "Marie Dupont" → (Marie, Dupont); "Jean de La Fontaine" → (Jean, de La Fontaine); one word is a first name. */
    fun splitName(name: String?): Pair<String, String> {
        val words = name?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
        return when (words.size) {
            0 -> "" to ""
            1 -> words[0] to ""
            else -> words[0] to words.drop(1).joinToString(" ")
        }
    }

    /** `Phone.TYPE_*` (an Int) or a custom label (a String) → the label stored after the number. */
    fun phoneLabel(type: Any?): String? = when (type) {
        is Number -> phoneTypeLabel(type.toInt(), null)
        is CharSequence -> type.toString().trim().toIntOrNull()?.let { phoneTypeLabel(it, null) } ?: PhoneLabels.clean(type.toString())
        else -> null
    }

    private fun phoneTypeLabel(type: Int, custom: String?): String? = when (type) {
        0 -> PhoneLabels.clean(custom)
        1 -> PhoneLabels.HOME
        2 -> PhoneLabels.MOBILE
        3, 10, 17 -> PhoneLabels.WORK // work, company main, work mobile
        4, 5, 13 -> "fax"
        6 -> "bip"
        7 -> null // "autre" is what a number without a label already means
        12 -> "principal"
        19 -> "assistant"
        else -> null
    }

    private fun organization(company: String?, title: String?): String? = when {
        company != null && title != null -> "Entreprise : $company ($title)"
        company != null -> "Entreprise : $company"
        title != null -> "Fonction : $title"
        else -> null
    }

    private fun oneLine(s: String) = s.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")

    // ---- ContactsContract.CommonDataKinds rows ----

    const val MIME_NAME = "vnd.android.cursor.item/name"
    const val MIME_PHONE = "vnd.android.cursor.item/phone_v2"
    const val MIME_EMAIL = "vnd.android.cursor.item/email_v2"
    const val MIME_POSTAL = "vnd.android.cursor.item/postal-address_v2"
    const val MIME_EVENT = "vnd.android.cursor.item/contact_event"
    const val MIME_NOTE = "vnd.android.cursor.item/note"
    const val MIME_ORGANIZATION = "vnd.android.cursor.item/organization"
    const val MIME_NICKNAME = "vnd.android.cursor.item/nickname"
    const val MIME_WEBSITE = "vnd.android.cursor.item/website"

    /**
     * A contact from the data rows of another account (or `Insert.DATA`): name, numbers with their type,
     * e-mails, first address, birthday; the company, nickname and websites go to the notes (no field of
     * their own). Photos, groups and the rest are not read.
     */
    fun fromRows(rows: List<DataRow>): Contact {
        var first = ""
        var last = ""
        val phones = ArrayList<Phone>()
        val emails = ArrayList<String>()
        val addresses = ArrayList<String>()
        var birthday: Birthday? = null
        val notes = ArrayList<String>()
        val extra = ArrayList<String>()
        for (r in rows) {
            when (r.mimeType) {
                MIME_NAME -> if (first.isEmpty() && last.isEmpty()) {
                    val given = listOfNotNull(r["data2"], r["data5"]).joinToString(" ")
                    val family = r["data3"].orEmpty()
                    if (given.isEmpty() && family.isEmpty()) {
                        splitName(r["data1"]).let { first = it.first; last = it.second }
                    } else {
                        first = given
                        last = family
                    }
                }
                MIME_PHONE -> r["data1"]?.let { n ->
                    val key = Phones.normalize(n).ifEmpty { n }
                    if (phones.none { (Phones.normalize(it.number).ifEmpty { it.number }) == key }) {
                        phones.add(Phone(n, phoneTypeLabel(r["data2"]?.toIntOrNull() ?: 7, r["data3"])))
                    }
                }
                MIME_EMAIL -> r["data1"]?.let { e -> if (emails.none { it.equals(e, ignoreCase = true) }) emails.add(e) }
                MIME_POSTAL -> (r["data1"] ?: listOfNotNull(r["data4"], listOfNotNull(r["data9"], r["data7"]).joinToString(" ").ifEmpty { null }, r["data10"])
                    .joinToString(", ").ifEmpty { null })?.let { addresses.add(oneLine(it)) }
                MIME_EVENT -> if (birthday == null && r["data2"] == "3") birthday = Birthday.parse(r["data1"])
                MIME_NOTE -> r["data1"]?.let { notes.add(it) }
                MIME_ORGANIZATION -> organization(r["data1"], r["data4"])?.let { extra.add(it) }
                MIME_NICKNAME -> r["data1"]?.let { extra.add("Surnom : $it") }
                MIME_WEBSITE -> r["data1"]?.let { extra.add("Site : $it") }
            }
        }
        // a second address has no field of its own: kept in the notes rather than lost
        addresses.drop(1).forEach { extra.add("Adresse : $it") }
        return Contact(
            firstName = first, lastName = last, phones = phones, emails = emails, address = addresses.firstOrNull(),
            birthday = birthday, notes = (notes + extra).distinct().joinToString("\n"),
        )
    }

    /** [existing] with what [draft] adds (numbers and e-mails not already there, empty fields filled): "Ajouter à un contact existant". */
    fun addTo(existing: Contact, draft: Contact): Contact = Duplicates.merge(existing, listOf(draft.copy(favorite = false)))
}
