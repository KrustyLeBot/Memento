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
            birthday = if (birthday(mine) == birthday(rows)) base.birthday else birthday(rows),
            notes = if (note(mine) == note(rows)) base.notes else note(rows),
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
