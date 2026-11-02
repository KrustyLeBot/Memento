package app.memento

import app.memento.agenda.EventDraft
import app.memento.agenda.Stamp
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.time.LocalDate
import java.time.LocalTime
import java.util.Properties

/**
 * What an open event editor holds, enough to rebuild it after the process was killed (Android frees a
 * backgrounded app at will). The base event is not copied: it is read again from the folder on restore.
 */
data class EventEditorDraft(
    /** "new", "file" (the file at [basePath]) or "occurrence" (one occurrence of [seriesId] at [recurrenceAt]). */
    val mode: String,
    val basePath: String? = null,
    val seriesId: String? = null,
    val recurrenceAt: Stamp? = null,
    val draft: EventDraft,
    val initial: EventDraft,
)

/** The contact editor's fields as typed (an unfinished birthday included); [originalPath] null for a new contact. */
data class ContactEditorDraft(
    val originalPath: String?,
    val firstName: String,
    val lastName: String,
    val phones: List<PhoneDraft>,
    val emails: List<String>,
    val address: String,
    val birthDay: String,
    val birthMonth: Int?,
    val birthYear: String,
    val notes: String,
    val favorite: Boolean,
)

data class PhoneDraft(val number: String, val label: String?)

/** Text form of the drafts (java Properties: any character, newlines included, survives). Pure. */
object DraftCodec {
    private fun Properties.str(k: String): String? = getProperty(k)

    private fun write(p: Properties): String = StringWriter().also { p.store(it, null) }.toString()
    private fun read(text: String): Properties = Properties().apply { load(StringReader(text)) }

    private fun putDraft(p: Properties, prefix: String, d: EventDraft) {
        p["$prefix.title"] = d.title
        p["$prefix.allDay"] = d.allDay.toString()
        p["$prefix.startDate"] = d.startDate.toString()
        p["$prefix.startTime"] = d.startTime.toString()
        p["$prefix.endDate"] = d.endDate.toString()
        p["$prefix.endTime"] = d.endTime.toString()
        d.tz?.let { p["$prefix.tz"] = it }
        d.endTz?.let { p["$prefix.endTz"] = it }
        d.calendar?.let { p["$prefix.calendar"] = it }
        p["$prefix.location"] = d.location
        d.rrule?.let { p["$prefix.rrule"] = it }
        p["$prefix.alerts"] = d.alerts.joinToString(",")
        p["$prefix.noEnd"] = d.noEnd.toString()
    }

    private fun getDraft(p: Properties, prefix: String): EventDraft {
        val startDate = LocalDate.parse(p.str("$prefix.startDate") ?: throw IllegalArgumentException("brouillon incomplet"))
        return EventDraft(
            title = p.str("$prefix.title").orEmpty(),
            allDay = p.str("$prefix.allDay") == "true",
            startDate = startDate,
            startTime = LocalTime.parse(p.str("$prefix.startTime")),
            endDate = LocalDate.parse(p.str("$prefix.endDate")),
            endTime = LocalTime.parse(p.str("$prefix.endTime")),
            tz = p.str("$prefix.tz"),
            endTz = p.str("$prefix.endTz"),
            calendar = p.str("$prefix.calendar"),
            location = p.str("$prefix.location").orEmpty(),
            rrule = p.str("$prefix.rrule"),
            alerts = p.str("$prefix.alerts").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() },
            noEnd = p.str("$prefix.noEnd") == "true",
        )
    }

    fun encodeEvent(d: EventEditorDraft): String {
        val p = Properties()
        p["mode"] = d.mode
        d.basePath?.let { p["basePath"] = it }
        d.seriesId?.let { p["seriesId"] = it }
        d.recurrenceAt?.let { p["recurrenceAt"] = it.toString() }
        putDraft(p, "draft", d.draft)
        putDraft(p, "initial", d.initial)
        return write(p)
    }

    /** Null when the text is not a draft this version can read (it is then dropped). */
    fun decodeEvent(text: String): EventEditorDraft? = try {
        val p = read(text)
        val mode = p.str("mode")
        if (mode == null) null else EventEditorDraft(
            mode = mode,
            basePath = p.str("basePath"),
            seriesId = p.str("seriesId"),
            recurrenceAt = p.str("recurrenceAt")?.let { Stamp.parse(it) },
            draft = getDraft(p, "draft"),
            initial = getDraft(p, "initial"),
        )
    } catch (_: RuntimeException) {
        null
    }

    fun encodeContact(d: ContactEditorDraft): String {
        val p = Properties()
        d.originalPath?.let { p["originalPath"] = it }
        p["firstName"] = d.firstName
        p["lastName"] = d.lastName
        p["phones"] = d.phones.size.toString()
        d.phones.forEachIndexed { i, ph ->
            p["phone.$i"] = ph.number
            ph.label?.let { p["phone.$i.label"] = it }
        }
        p["emails"] = d.emails.size.toString()
        d.emails.forEachIndexed { i, e -> p["email.$i"] = e }
        p["address"] = d.address
        p["birthDay"] = d.birthDay
        d.birthMonth?.let { p["birthMonth"] = it.toString() }
        p["birthYear"] = d.birthYear
        p["notes"] = d.notes
        p["favorite"] = d.favorite.toString()
        return write(p)
    }

    fun decodeContact(text: String): ContactEditorDraft? = try {
        val p = read(text)
        ContactEditorDraft(
            originalPath = p.str("originalPath"),
            firstName = p.str("firstName").orEmpty(),
            lastName = p.str("lastName").orEmpty(),
            phones = (0 until (p.str("phones")?.toInt() ?: 0)).map { PhoneDraft(p.str("phone.$it").orEmpty(), p.str("phone.$it.label")) },
            emails = (0 until (p.str("emails")?.toInt() ?: 0)).map { p.str("email.$it").orEmpty() },
            address = p.str("address").orEmpty(),
            birthDay = p.str("birthDay").orEmpty(),
            birthMonth = p.str("birthMonth")?.toInt(),
            birthYear = p.str("birthYear").orEmpty(),
            notes = p.str("notes").orEmpty(),
            favorite = p.str("favorite") == "true",
        )
    } catch (_: RuntimeException) {
        null
    }
}

/** The drafts' files, in app-private storage (never synced): one per editor kind. Small, written on pause. */
class DraftFiles(dir: File) {
    private val root = File(dir, "drafts")
    val event = File(root, "event.properties")
    val contact = File(root, "contact.properties")

    fun write(f: File, text: String) {
        root.mkdirs()
        val tmp = File(root, ".${f.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun read(f: File): String? = if (f.isFile) f.readText() else null

    fun clear(f: File) { f.delete() }
}
