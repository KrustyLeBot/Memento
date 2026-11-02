package app.memento.contacts

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What both sides agreed on for a contact at the end of the last successful run. */
data class Synced(val hash: String, val at: Long)

/** A change to the `contacts/` folder. */
sealed class FileOp {
    /** A contact created on the phone (or recreated from it): [contact] has its id, no path yet. */
    data class Create(val contact: Contact) : FileOp()
    /** An existing file with new fields (the phone's edit, or a conflict's merge). */
    data class Write(val contact: Contact) : FileOp()
    /** Deleted on the phone: the file goes, kept aside so the deletion can be undone. */
    data class Trash(val contact: Contact) : FileOp()
}

data class SyncPlan(
    /** Applied first: the provider is only told a contact is settled once its file is. */
    val files: List<FileOp>,
    val provider: List<MirrorOp>,
    /** The state to keep once both lists were applied. */
    val synced: Map<String, Synced>,
    /** The folder read empty while the phone holds mirrored contacts: nothing was removed or rewritten. */
    val keptBecauseEmpty: Boolean = false,
    /** Contacts changed on both sides since the last run (the loser's fields went to the notes). */
    val conflicts: Int = 0,
) {
    val isEmpty: Boolean get() = files.isEmpty() && provider.isEmpty()
}

/**
 * The two-way mirror, decided in one pure function (Part C §5 L2). For each raw contact of the account:
 * - no SOURCE_ID: created on the phone → a new contact file, then SOURCE_ID + SYNC1 set and DIRTY cleared;
 * - DIRTY: edited on the phone → the file takes the phone's fields; if the file also changed since the last
 *   run, the most recent change wins (file mtime against the phone's change time) and the other version's
 *   differing fields are appended to the notes, so nothing is lost;
 * - DELETED: deleted on the phone → the file goes to the trash (undo for 7 days) and the row is purged, unless
 *   the file changed since the last run (then the file wins and comes back on the phone);
 * - otherwise files → provider by hash, as in L1: insert, rewrite, delete when the file is gone.
 * Idempotent: applying the plan and running again gives an empty plan.
 */
object MirrorSync {
    fun reconcile(
        files: List<Contact>,
        fileTimes: Map<String, Long>,
        raws: List<MirrorRaw>,
        synced: Map<String, Synced>,
        now: Long,
        zone: ZoneId,
        newId: () -> String,
    ): SyncPlan {
        val fileOps = ArrayList<FileOp>()
        val ops = ArrayList<MirrorOp>()
        val out = HashMap<String, Synced>()
        var conflicts = 0
        val wanted = MirrorPlan.mirrored(files).associateBy { it.id }
        val allIds = files.map { it.id }.toHashSet()
        val sorted = raws.sortedBy { it.rawId }
        val live = sorted.filter { !it.deleted && it.sourceId != null }
        // An unreadable folder (sync in progress, SAF trouble) looks empty: never wipe or rewrite the phone on that.
        val keep = wanted.isEmpty() && live.isNotEmpty()

        fun rowsOf(r: MirrorRaw): List<MirrorRow> = r.rows ?: throw IllegalArgumentException("Données du contact ${r.rawId} non lues")

        fun agreed(id: String, hash: String, changed: Boolean) {
            val before = synced[id]
            out[id] = Synced(hash, if (!changed && before?.hash == hash) before.at else now)
        }

        // Created on the phone: never depends on the folder, so it runs even under the guard.
        for (r in sorted) {
            if (r.deleted || r.sourceId != null) continue
            var id = newId()
            while (id in allIds) id = newId()
            val c = MirrorPlan.adopt(Contact(id = id), rowsOf(r), r.starred)
            if (c.isEmpty) continue // a raw contact with nothing in it: nothing to keep, left to the phone
            allIds.add(id)
            val h = MirrorPlan.hash(c)
            fileOps.add(FileOp.Create(c))
            ops.add(MirrorOp.Ack(r.rawId, id, h))
            agreed(id, h, true)
        }
        if (keep) return SyncPlan(fileOps, ops, synced + out, keptBecauseEmpty = true)

        val primary = LinkedHashMap<String, MirrorRaw>()
        for (r in live) {
            if (r.sourceId!! in primary) ops.add(MirrorOp.Delete(r.rawId)) else primary[r.sourceId] = r
        }
        val trashed = HashSet<String>()

        for (r in sorted) {
            if (!r.deleted) continue
            ops.add(MirrorOp.Delete(r.rawId))
            val src = r.sourceId ?: continue
            if (src in primary || src in trashed) continue
            val f = wanted[src] ?: continue
            val base = synced[src]?.hash ?: r.hash
            // a file edited since the last run wins over the deletion: it is inserted again below
            if (MirrorPlan.hash(f) == base) {
                fileOps.add(FileOp.Trash(f))
                trashed.add(src)
            }
        }

        for ((src, r) in primary) {
            val f = wanted[src]
            if (f == null) {
                // the file is gone (deleted in the app or on the PC); an edit made on the phone meanwhile brings it back
                val back = if (r.dirty && src !in allIds) MirrorPlan.adopt(Contact(id = src), rowsOf(r), r.starred) else null
                if (back == null || back.isEmpty) {
                    ops.add(MirrorOp.Delete(r.rawId))
                } else {
                    val h = MirrorPlan.hash(back)
                    fileOps.add(FileOp.Create(back))
                    ops.add(MirrorOp.Ack(r.rawId, src, h))
                    agreed(src, h, true)
                }
                continue
            }
            val hf = MirrorPlan.hash(f)
            if (!r.dirty) {
                if (r.hash != hf) ops.add(MirrorOp.Update(r.rawId, f, hf))
                agreed(src, hf, r.hash != hf)
                continue
            }
            val phone = MirrorPlan.adopt(f, rowsOf(r), r.starred)
            val hp = MirrorPlan.hash(phone)
            val base = synced[src]?.hash ?: r.hash
            when {
                hp == hf -> { ops.add(MirrorOp.Ack(r.rawId, src, hf)); agreed(src, hf, false) }
                hf == base -> {
                    fileOps.add(FileOp.Write(phone))
                    ops.add(MirrorOp.Ack(r.rawId, src, hp))
                    agreed(src, hp, true)
                }
                hp == base -> { ops.add(MirrorOp.Update(r.rawId, f, hf)); agreed(src, hf, true) }
                else -> {
                    conflicts++
                    val phoneWins = (r.changedAt ?: now) >= (fileTimes[src] ?: 0L)
                    val merged = if (phoneWins) {
                        withLoser(phone, f, "Version de Mémento remplacée le ${stamp(now, zone)}")
                    } else {
                        withLoser(f, phone, "Version du téléphone remplacée le ${stamp(now, zone)}")
                    }
                    val h = MirrorPlan.hash(merged)
                    fileOps.add(FileOp.Write(merged))
                    ops.add(MirrorOp.Update(r.rawId, merged, h))
                    agreed(src, h, true)
                }
            }
        }

        for ((id, f) in wanted) {
            if (id in primary || id in trashed) continue
            val h = MirrorPlan.hash(f)
            ops.add(MirrorOp.Insert(f, h))
            agreed(id, h, true)
        }
        return SyncPlan(fileOps, ops, out, conflicts = conflicts)
    }

    /**
     * Raw contacts the phone created whose file was written by a run that failed before it could set their
     * SOURCE_ID: they are taken as that file's raw contact (a dirty one), not as new contacts again.
     */
    fun resume(raws: List<MirrorRaw>, pending: Map<Long, String>, fileIds: Set<String>): List<MirrorRaw> = raws.map { r ->
        val id = pending[r.rawId]
        if (r.sourceId == null && !r.deleted && id != null && id in fileIds) r.copy(sourceId = id, dirty = true) else r
    }

    private fun stamp(now: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("d MMMM yyyy 'à' HH:mm", Locale.FRENCH).format(Instant.ofEpochMilli(now).atZone(zone))

    /** [winner] with a paragraph listing what [loser] said differently, appended to the notes. */
    fun withLoser(winner: Contact, loser: Contact, title: String): Contact {
        val lines = differences(winner, loser)
        if (lines.isEmpty()) return winner
        val block = "$title :\n" + lines.joinToString("\n") { "- $it" }
        return winner.copy(notes = listOf(winner.notes.trimEnd(), block).filter { it.isNotEmpty() }.joinToString("\n\n"))
    }

    /** "Champ : valeur" of [other] for each field it holds differently from [c]. */
    fun differences(c: Contact, other: Contact): List<String> = buildList {
        if (c.firstName.trim() != other.firstName.trim() || c.lastName.trim() != other.lastName.trim()) add("Nom : ${other.name.ifEmpty { "(vide)" }}")
        if (c.phones.map { it.normalized to it.label } != other.phones.map { it.normalized to it.label }) {
            add("Téléphones : " + other.phones.joinToString(", ") { it.encode() }.ifEmpty { "(aucun)" })
        }
        if (c.emails.map { it.lowercase() } != other.emails.map { it.lowercase() }) add("E-mails : " + other.emails.joinToString(", ").ifEmpty { "(aucun)" })
        if (c.address.orEmpty().trim() != other.address.orEmpty().trim()) add("Adresse : " + (other.address?.ifBlank { null } ?: "(aucune)"))
        if (c.birthday != other.birthday) add("Anniversaire : " + (other.birthday?.encode() ?: "(aucun)"))
        if (c.favorite != other.favorite) add("Favori : " + if (other.favorite) "oui" else "non")
        if (c.notes.trim() != other.notes.trim()) add("Notes : " + other.notes.trim().ifEmpty { "(vides)" })
    }
}
