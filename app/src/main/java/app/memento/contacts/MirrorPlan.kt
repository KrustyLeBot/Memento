package app.memento.contacts

import java.security.MessageDigest

/** A data row of a mirrored raw contact, independent of `ContactsContract` (mapped in [ContactsMirror]). */
sealed class MirrorRow {
    data class Name(val given: String, val family: String, val display: String) : MirrorRow()
    data class Tel(val number: String, val kind: PhoneKind, val label: String?) : MirrorRow()
    data class Mail(val address: String) : MirrorRow()
    data class Postal(val formatted: String) : MirrorRow()
    /** `1990-05-14`, or `--05-14` without the year (what the phone's contacts app writes itself). */
    data class Birthday(val date: String) : MirrorRow()
    data class Note(val text: String) : MirrorRow()
    /**
     * A row of a kind the files have no field for (organisation, website, nickname, IM, relation, SIP, an event
     * other than the birthday, an e-mail's type), as "label : value": read from the phone into the notes only,
     * never written, never deleted by an update ([MirrorPlan.OWNED_MIMES]).
     */
    data class Extra(val label: String, val value: String) : MirrorRow()
}

enum class PhoneKind { MOBILE, HOME, WORK, OTHER, CUSTOM }

/**
 * One raw contact of the app's account, as read from the provider. [rows] and [starred] are read for the raw
 * contacts the phone created or edited (no source id, or dirty); [changedAt] is when the phone last changed it
 * (the aggregate's last-updated time, else when the mirror first saw it dirty).
 */
data class MirrorRaw(
    val rawId: Long,
    val sourceId: String?,
    val hash: String?,
    val dirty: Boolean = false,
    val deleted: Boolean = false,
    val starred: Boolean = false,
    val rows: List<MirrorRow>? = null,
    val changedAt: Long? = null,
)

/** A change to the provider. */
sealed class MirrorOp {
    data class Insert(val contact: Contact, val hash: String) : MirrorOp()
    /** Replaces the mirrored data rows of [rawId] (rows of other kinds, a photo, are kept) and clears DIRTY. */
    data class Update(val rawId: Long, val contact: Contact, val hash: String) : MirrorOp()
    /** Removes the raw contact for good (as the sync adapter: a DELETED row is purged). */
    data class Delete(val rawId: Long) : MirrorOp()
    /** The phone's version was taken into the file as it is: sets SOURCE_ID and SYNC1, clears DIRTY, rows untouched. */
    data class Ack(val rawId: Long, val sourceId: String, val hash: String) : MirrorOp()
}

/**
 * The mirror of the contacts into the phone's contacts (Part C §5, L2: both ways). This is the pure part of the
 * mapping: which rows a contact gives, what a contact is made of rows the phone wrote, and the provider batches.
 * One raw contact per contact file: `SOURCE_ID` = the contact id, `SYNC1` = [hash] of the mirrored fields.
 * What to do on each run is [MirrorSync].
 */
object MirrorPlan {
    const val ACCOUNT_TYPE = "app.memento.contacts"
    const val ACCOUNT_NAME = "Mémento"
    /** Bump when the mapping below changes: every raw contact is then rewritten once. */
    private const val VERSION = 1
    /** The provider applies a batch in one transaction; stay well under its limit of about 500 operations. */
    const val MAX_BATCH = 400

    fun mirrored(contacts: List<Contact>): List<Contact> =
        contacts.filter { !it.conflict && it.id.isNotEmpty() && !it.isEmpty }.distinctBy { it.id }

    fun kindOf(label: String?): PhoneKind = when (label) {
        PhoneLabels.MOBILE -> PhoneKind.MOBILE
        PhoneLabels.HOME -> PhoneKind.HOME
        PhoneLabels.WORK -> PhoneKind.WORK
        null, "", PhoneLabels.OTHER -> PhoneKind.OTHER
        else -> PhoneKind.CUSTOM
    }

    /** The label stored in the file for a number of [kind] (the reverse of [kindOf]). */
    fun labelOf(kind: PhoneKind, label: String?): String? = when (kind) {
        PhoneKind.MOBILE -> PhoneLabels.MOBILE
        PhoneKind.HOME -> PhoneLabels.HOME
        PhoneKind.WORK -> PhoneLabels.WORK
        PhoneKind.OTHER -> null
        PhoneKind.CUSTOM -> PhoneLabels.clean(label)
    }

    /**
     * A `Phone.TYPE` of the provider (the values are public API constants) as a kind and label. The types
     * Mémento has no label for keep a French one of their own, so nothing the phone shows is lost.
     */
    fun phoneType(type: Int, label: String?): Pair<PhoneKind, String?> = when (type) {
        2 -> PhoneKind.MOBILE to null
        1 -> PhoneKind.HOME to null
        3 -> PhoneKind.WORK to null
        7 -> PhoneKind.OTHER to null
        0 -> (if (PhoneLabels.clean(label) == null) PhoneKind.OTHER else PhoneKind.CUSTOM) to PhoneLabels.clean(label)
        else -> PhoneKind.CUSTOM to (OTHER_TYPES[type] ?: PhoneLabels.clean(label) ?: "autre")
    }

    private val OTHER_TYPES = mapOf(
        4 to "fax travail", 5 to "fax domicile", 6 to "bipeur", 8 to "rappel", 9 to "voiture", 10 to "standard",
        11 to "RNIS", 12 to "principal", 13 to "fax", 14 to "radio", 15 to "télex", 16 to "ATS",
        17 to "mobile travail", 18 to "bipeur travail", 19 to "assistant", 20 to "MMS",
    )

    private fun tel(p: Phone): MirrorRow.Tel {
        val kind = kindOf(p.label)
        return MirrorRow.Tel(Phones.dialable(p.number), kind, if (kind == PhoneKind.CUSTOM) p.label else null)
    }

    fun rows(c: Contact): List<MirrorRow> {
        val out = ArrayList<MirrorRow>()
        out.add(MirrorRow.Name(c.firstName.trim(), c.lastName.trim(), c.displayName))
        c.phones.forEach { out.add(tel(it)) }
        c.emails.forEach { out.add(MirrorRow.Mail(it)) }
        c.address?.takeIf { it.isNotBlank() }?.let { out.add(MirrorRow.Postal(it)) }
        c.birthday?.let { out.add(MirrorRow.Birthday(it.encode())) }
        if (c.notes.isNotBlank()) out.add(MirrorRow.Note(c.notes.trim()))
        return out
    }

    /** Of what is mirrored only (plus the favourite star): saving a contact unchanged rewrites nothing. */
    fun hash(c: Contact): String {
        val text = "v$VERSION\u0001" + (if (c.favorite) "*" else "") + "\u0001" + rows(c).joinToString("\u0001")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(32)
    }

    // ---- phone → file ----

    private fun address(raw: String): String =
        raw.split('\n').map { it.trim().trim(',').trim() }.filter { it.isNotEmpty() }.joinToString(", ").replace(Regex("[ \\t]+"), " ")

    private fun nameOf(rows: List<MirrorRow>): Pair<String, String> {
        val n = rows.filterIsInstance<MirrorRow.Name>().firstOrNull() ?: return "" to ""
        val given = n.given.trim()
        val family = n.family.trim()
        return if (given.isEmpty() && family.isEmpty()) n.display.trim() to "" else given to family
    }

    private fun telKey(t: MirrorRow.Tel) =
        Triple(Phones.normalize(t.number).ifEmpty { t.number.trim() }, t.kind, if (t.kind == PhoneKind.CUSTOM) PhoneLabels.clean(t.label) else null)

    private fun tels(rows: List<MirrorRow>) = rows.filterIsInstance<MirrorRow.Tel>().filter { it.number.isNotBlank() }
    private fun mails(rows: List<MirrorRow>) = rows.filterIsInstance<MirrorRow.Mail>().map { it.address.trim() }.filter { it.isNotEmpty() }
    private fun postal(rows: List<MirrorRow>) = rows.filterIsInstance<MirrorRow.Postal>().map { address(it.formatted) }.firstOrNull { it.isNotEmpty() }
    private fun birthday(rows: List<MirrorRow>) = rows.filterIsInstance<MirrorRow.Birthday>().firstNotNullOfOrNull { Birthday.parse(it.date) }
    private fun note(rows: List<MirrorRow>) = rows.filterIsInstance<MirrorRow.Note>().map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

    /**
     * What the phone holds that a contact file has no field for (a second address, a date it wrote in a form
     * Mémento does not read, the [MirrorRow.Extra] kinds): kept as lines of the notes rather than dropped.
     */
    private fun extras(rows: List<MirrorRow>): List<String> =
        (rows.filterIsInstance<MirrorRow.Postal>().map { address(it.formatted) }.filter { it.isNotEmpty() }.distinct().drop(1)
            .map { "Adresse : $it" } +
            rows.filterIsInstance<MirrorRow.Birthday>().filter { Birthday.parse(it.date) == null && it.date.isNotBlank() }.map { "Anniversaire : ${it.date.trim()}" } +
            rows.filterIsInstance<MirrorRow.Extra>().filter { it.value.isNotBlank() }.map { "${it.label.trim()} : ${oneLine(it.value)}" })
            .distinct()

    private fun oneLine(s: String) = s.trim().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")

    /** The notes the phone's rows give: the note row, with the [extras] its block does not hold yet. */
    private fun phoneNotes(rows: List<MirrorRow>): String = withExtras(note(rows), extras(rows))

    /**
     * [notes] with [lines] in its "Autres informations du téléphone" paragraph. A line already there is not added
     * again: the block reaches the phone's note row with the next update while the rows it came from stay on the
     * phone, so every later run reads both. New lines go at the end of the block; the result is the same however
     * many runs read it.
     */
    fun withExtras(notes: String, lines: List<String>): String {
        val header = "$EXTRAS_TITLE :"
        val paragraphs = if (notes.isEmpty()) mutableListOf() else notes.split("\n\n").toMutableList()
        val at = paragraphs.indexOfFirst { it.trim().lineSequence().first().trim() == header }
        val present = if (at < 0) emptySet() else paragraphs[at].trim().lines().drop(1).map { it.trim().removePrefix("-").trim() }.toSet()
        val added = lines.filter { it !in present }
        if (added.isEmpty()) return notes
        val block = added.joinToString("\n") { "- $it" }
        if (at < 0) paragraphs.add("$header\n$block") else paragraphs[at] = paragraphs[at].trimEnd() + "\n" + block
        return paragraphs.joinToString("\n\n")
    }

    private const val EXTRAS_TITLE = "Autres informations du téléphone"

    /**
     * The `Data.MIMETYPE`s Mémento owns: an update deletes and rewrites their rows (plus the birthday events) and
     * nothing else, so what the phone holds beyond them (photo, organisation, website, other events...) stays.
     */
    val OWNED_MIMES = listOf(
        "vnd.android.cursor.item/name", "vnd.android.cursor.item/phone_v2", "vnd.android.cursor.item/email_v2",
        "vnd.android.cursor.item/postal-address_v2", "vnd.android.cursor.item/note",
    )

    private val IM_PROTOCOLS = mapOf(0 to "AIM", 1 to "Windows Live", 2 to "Yahoo", 3 to "Skype", 4 to "QQ", 5 to "Google Talk", 6 to "ICQ", 7 to "Jabber", 8 to "NetMeeting")
    private val RELATIONS = mapOf(
        1 to "assistant", 2 to "frère", 3 to "enfant", 4 to "concubin", 5 to "père", 6 to "ami", 7 to "responsable", 8 to "mère",
        9 to "parent", 10 to "partenaire", 11 to "recommandé par", 12 to "proche", 13 to "sœur", 14 to "conjoint",
    )
    private val EMAIL_TYPES = mapOf(1 to "domicile", 2 to "travail", 4 to "mobile")
    private val EVENT_TYPES = mapOf(1 to "anniversaire de mariage", 2 to "autre")

    /**
     * The [MirrorRow.Extra]s of a data row of [mime] that the files do not model; [data] holds its columns data1 to
     * data10 (the `CommonDataKinds` column numbers and type values are public API). Empty for the other kinds.
     */
    fun extra(mime: String, data: List<String>): List<MirrorRow.Extra> {
        fun d(i: Int) = data.getOrNull(i - 1).orEmpty().trim()
        fun typed(type: Int?, names: Map<Int, String>, custom: String) = names[type] ?: custom.ifEmpty { null }
        fun one(label: String, value: String) = if (value.isEmpty()) emptyList() else listOf(MirrorRow.Extra(label, value))
        return when (mime) {
            // data1 company, data4 title, data5 department
            "vnd.android.cursor.item/organization" -> one("Société", d(1)) + one("Fonction", d(4)) + one("Service", d(5))
            "vnd.android.cursor.item/website" -> one("Site web", d(1))
            "vnd.android.cursor.item/nickname" -> one("Surnom", d(1))
            "vnd.android.cursor.item/sip_address" -> one("Adresse SIP", d(1))
            // data1 handle, data5 protocol (-1 custom, named in data6)
            "vnd.android.cursor.item/im" -> one(typed(d(5).toIntOrNull(), IM_PROTOCOLS, d(6))?.let { "Messagerie ($it)" } ?: "Messagerie", d(1))
            // data1 name, data2 type (0 custom, named in data3)
            "vnd.android.cursor.item/relation" -> one(typed(d(2).toIntOrNull(), RELATIONS, d(3))?.let { "Relation ($it)" } ?: "Relation", d(1))
            // data1 date, data2 type (3 the birthday, a row of its own; 0 custom, named in data3)
            "vnd.android.cursor.item/contact_event" ->
                if (d(2).toIntOrNull() == 3) emptyList() else one(typed(d(2).toIntOrNull(), EVENT_TYPES, d(3))?.let { "Date ($it)" } ?: "Date", d(1))
            // the address itself is a Mail row; only its type (3 other, the one Mémento writes, says nothing) is extra
            "vnd.android.cursor.item/email_v2" ->
                typed(d(2).toIntOrNull(), EMAIL_TYPES, if (d(2) == "0") d(3) else "")?.let { one("E-mail ($it)", d(1)) }.orEmpty()
            else -> emptyList()
        }
    }

    /** A StructuredName row: a prefix and a middle name go with the first name, a suffix with the last name. */
    fun nameRow(prefix: String, given: String, middle: String, family: String, suffix: String, display: String): MirrorRow.Name {
        fun join(vararg parts: String) = parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        return MirrorRow.Name(join(prefix, given, middle), join(family, suffix), display.trim())
    }

    /**
     * [base] with the fields the phone holds in [rows]. A field whose rows say the same as the file (a number
     * typed with other spaces, `autre` written as "other") keeps the file's own spelling; everything else of the
     * file (id, path, unknown keys, attachments, uid) is kept. The notes are the note row.
     */
    fun adopt(base: Contact, rows: List<MirrorRow>, starred: Boolean): Contact {
        val mine = rows(base)
        val (given, family) = nameOf(rows)
        val sameName = nameOf(mine) == (given to family)
        val sameTels = tels(mine).map(::telKey) == tels(rows).map(::telKey)
        val sameMails = mails(mine).map { it.lowercase() } == mails(rows).map { it.lowercase() }
        return base.copy(
            firstName = if (sameName) base.firstName else given,
            lastName = if (sameName) base.lastName else family,
            phones = if (sameTels) base.phones else {
                // a number the phone left as it was keeps the file's spelling, even when others changed
                val left = base.phones.map { telKey(tel(it)) to it }.toMutableList()
                tels(rows).map { t ->
                    val i = left.indexOfFirst { it.first == telKey(t) }
                    if (i >= 0) left.removeAt(i).second else Phone(t.number.trim(), labelOf(t.kind, t.label))
                }
            },
            emails = if (sameMails) base.emails else mails(rows).distinctBy { it.lowercase() },
            address = if (postal(mine) == postal(rows)) base.address else postal(rows),
            // a birthday row the phone wrote in a form not read here never erases the file's (its text goes to the notes)
            birthday = if (birthday(mine) == birthday(rows) || birthday(rows) == null && rows.any { it is MirrorRow.Birthday }) base.birthday else birthday(rows),
            notes = if (phoneNotes(mine) == phoneNotes(rows)) base.notes else phoneNotes(rows),
            favorite = starred,
        )
    }

    // ---- batches ----

    /** Provider operations an op turns into (a raw contact's rows always travel in the same batch). */
    fun size(op: MirrorOp): Int = when (op) {
        is MirrorOp.Insert -> 1 + rows(op.contact).size
        is MirrorOp.Update -> 2 + rows(op.contact).size
        is MirrorOp.Delete, is MirrorOp.Ack -> 1
    }

    fun batches(ops: List<MirrorOp>, limit: Int = MAX_BATCH): List<List<MirrorOp>> {
        val out = ArrayList<List<MirrorOp>>()
        var cur = ArrayList<MirrorOp>()
        var n = 0
        for (op in ops) {
            val s = size(op)
            if (cur.isNotEmpty() && n + s > limit) { out.add(cur); cur = ArrayList(); n = 0 }
            cur.add(op)
            n += s
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }
}
