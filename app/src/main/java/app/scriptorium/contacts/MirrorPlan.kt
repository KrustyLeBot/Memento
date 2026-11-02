package app.scriptorium.contacts

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

/** One raw contact of the app's account, as read from the provider. */
data class MirrorRaw(val rawId: Long, val sourceId: String?, val hash: String?, val dirty: Boolean = false, val deleted: Boolean = false)

sealed class MirrorOp {
    data class Insert(val contact: Contact, val hash: String) : MirrorOp()
    /** Replaces every data row of [rawId] (edits made in the phone's contacts app are overwritten). */
    data class Update(val rawId: Long, val contact: Contact, val hash: String) : MirrorOp()
    data class Delete(val rawId: Long) : MirrorOp()
}

/**
 * The mirror of the contacts into the phone's contacts, one way (files → provider), Part C §5 L1. This is the
 * pure part: which rows a contact gives, and what to change given what the provider holds. One raw contact
 * per contact file: `SOURCE_ID` = the contact id, `SYNC1` = [hash] of the mirrored fields.
 */
object MirrorPlan {
    const val ACCOUNT_TYPE = "app.scriptorium.contacts"
    const val ACCOUNT_NAME = "Scriptorium"
    /** Bump when the mapping below changes: every raw contact is then rewritten once. */
    private const val VERSION = 1
    /** The provider applies a batch in one transaction; stay well under its limit of about 500 operations. */
    const val MAX_BATCH = 400

    fun mirrored(contacts: List<Contact>): List<Contact> =
        contacts.filter { !it.conflict && it.id.isNotEmpty() && !it.isEmpty }.distinctBy { it.id }

    fun rows(c: Contact): List<MirrorRow> {
        val out = ArrayList<MirrorRow>()
        out.add(MirrorRow.Name(c.firstName.trim(), c.lastName.trim(), c.displayName))
        c.phones.forEach { p ->
            val kind = when (p.label) {
                PhoneLabels.MOBILE -> PhoneKind.MOBILE
                PhoneLabels.HOME -> PhoneKind.HOME
                PhoneLabels.WORK -> PhoneKind.WORK
                null, "", PhoneLabels.OTHER -> PhoneKind.OTHER
                else -> PhoneKind.CUSTOM
            }
            out.add(MirrorRow.Tel(p.number, kind, if (kind == PhoneKind.CUSTOM) p.label else null))
        }
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

    /**
     * What to change: insert contacts without a raw contact, rewrite those whose hash differs or that were
     * edited in the phone (dirty), delete raw contacts whose file vanished, duplicates of the same id,
     * rows without a source id, and rows the user deleted in the phone (they come back from the file).
     */
    fun diff(contacts: List<Contact>, existing: List<MirrorRaw>): List<MirrorOp> {
        val ops = ArrayList<MirrorOp>()
        val bySource = HashMap<String, MirrorRaw>()
        for (r in existing.sortedBy { it.rawId }) {
            val src = r.sourceId
            if (src == null || r.deleted || src in bySource) ops.add(MirrorOp.Delete(r.rawId)) else bySource[src] = r
        }
        val wanted = mirrored(contacts)
        val ids = wanted.map { it.id }.toSet()
        bySource.values.filter { it.sourceId !in ids }.sortedBy { it.rawId }.forEach { ops.add(MirrorOp.Delete(it.rawId)) }
        for (c in wanted) {
            val h = hash(c)
            val raw = bySource[c.id]
            when {
                raw == null -> ops.add(MirrorOp.Insert(c, h))
                raw.hash != h || raw.dirty -> ops.add(MirrorOp.Update(raw.rawId, c, h))
            }
        }
        return ops
    }

    /** Provider operations an op turns into (a raw contact's rows always travel in the same batch). */
    fun size(op: MirrorOp): Int = when (op) {
        is MirrorOp.Insert -> 1 + rows(op.contact).size
        is MirrorOp.Update -> 2 + rows(op.contact).size
        is MirrorOp.Delete -> 1
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
