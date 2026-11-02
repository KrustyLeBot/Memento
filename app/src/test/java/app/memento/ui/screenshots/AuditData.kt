package app.memento.ui.screenshots

import app.memento.FileFs
import app.memento.Note
import app.memento.NoteFormat
import app.memento.Settings
import app.memento.agenda.AgendaStore
import app.memento.agenda.Event
import app.memento.agenda.Stamp
import app.memento.contacts.Birthday
import app.memento.contacts.Contact
import app.memento.contacts.ContactFormat
import app.memento.contacts.Phone
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

/** Synthetic folder for the screenshot audit: notes, agenda, contacts written as the app writes them. */
object AuditData {
    fun seed(root: File = Settings.defaultDir()) {
        root.deleteRecursively()
        root.mkdirs()
        val fs = FileFs(root)
        val now = System.currentTimeMillis()
        var n = 0
        fun note(title: String, body: String, color: String = "default", pinned: Boolean = false, labels: List<String> = emptyList()) {
            val id = "n%05d".format(n++)
            val note = Note(path = NoteFormat.fileNameFor(title.ifBlank { "note" }, id), id = id, title = title, color = color, pinned = pinned,
                labels = labels, created = now - n * 3_600_000L, updated = now - n * 3_600_000L, body = body)
            fs.writeText(note.path, NoteFormat.serialize(note))
        }
        note("Courses", "- [ ] Pain\n- [ ] Lait demi-écrémé\n- [x] Pommes\n- [ ] Café", pinned = true, labels = listOf("Maison"))
        note("Idées cadeaux", "Pour Noël : un livre de randonnée, des gants de ski chauds.", color = "red", pinned = true, labels = listOf("Perso", "Noël"))
        note("Réunion lundi", "Ordre du jour :\n- budget 2027\n- planning des congés\n**Important** : préparer les slides.", color = "blue", labels = listOf("Travail"))
        note("", "Une note sans titre, juste quelques mots pour voir l'aperçu.", color = "yellow")
        note("Recette crêpes", "250 g de farine, 4 œufs, 50 cl de lait, une pincée de sel. Laisser reposer une heure.", color = "green", labels = listOf("Cuisine"))
        note("Lien utile", "https://example.org/documentation", color = "purple")
        note("Vacances", "Réserver le gîte avant fin novembre.", color = "gray", labels = listOf("Perso"))
        note("Liste de lecture", "Le Comte de Monte-Cristo\nL'Étranger\nLes Misérables", color = "teal")
        note("Rendez-vous garage", "Révision des 60 000 km", color = "orange")
        note("Mots de passe Wi-Fi", "Salon : voir étiquette de la box", color = "brown")

        // agenda
        val cal = File(root, "agenda/calendars.json")
        cal.parentFile!!.mkdirs()
        cal.writeText(
            """{"calendars":[{"id":"perso","name":"Perso","color":"#4F8BF5","visible":true},{"id":"travail","name":"Travail","color":"#E0663A","visible":true},""" +
                """{"id":"sport","name":"Sport","color":"#F6E58D","visible":true}],"default":"perso",""" +
                """"layers":{"activities":{"visible":true,"color":"#2E9E5B"},"notes":{"visible":true,"color":"#8E63CE"},"birthdays":{"visible":true,"color":"#D64D6F"}}}""",
        )
        val store = AgendaStore(fs)
        val today = LocalDate.now()
        var e = 0
        fun event(title: String, day: LocalDate, from: LocalTime?, to: LocalTime?, calendar: String, rrule: String? = null, location: String? = null, body: String = "") {
            val id = "e%05d".format(e++)
            val note = Note(path = "", id = id, title = title, created = now, updated = now, body = body)
            store.save(
                Event(note, if (from == null) Stamp.of(day) else Stamp(day, from), to?.let { Stamp(day, it) } ?: if (from == null) Stamp.of(day) else null,
                    calendar = calendar, rrule = rrule, location = location, alerts = listOf(-15)),
            )
        }
        event("Stand-up", today, LocalTime.of(9, 30), LocalTime.of(9, 45), "travail", rrule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR")
        event("Déjeuner avec Paul", today, LocalTime.of(12, 30), LocalTime.of(14, 0), "perso", location = "Brasserie du Port")
        event("Yoga", today, LocalTime.of(18, 30), LocalTime.of(19, 30), "sport", location = "Salle B, 2e étage", body = "Tapis fourni.")
        event("Dentiste", today.plusDays(1), LocalTime.of(10, 0), LocalTime.of(10, 45), "perso")
        event("Revue de projet très longue avec un titre qui déborde", today.plusDays(2), LocalTime.of(14, 0), LocalTime.of(16, 0), "travail")
        event("Week-end à la montagne", today.plusDays(4), null, null, "perso")
        event("Anniversaire Lucie", today.plusDays(6), null, null, "perso")
        event("Course à pied", today.plusDays(3), LocalTime.of(7, 0), LocalTime.of(8, 0), "sport")
        event("Conférence", today.minusDays(2), LocalTime.of(9, 0), LocalTime.of(17, 0), "travail")

        // contacts
        val contacts = listOf(
            Contact(firstName = "Marie", lastName = "Dupont", phones = listOf(Phone("+33 6 12 34 56 78", "mobile")), emails = listOf("marie@example.org"),
                address = "12 rue de la Paix, 75002 Paris", birthday = Birthday(1988, today.plusDays(5).monthValue, today.plusDays(5).dayOfMonth), favorite = true,
                notes = "Rencontrée au club de lecture."),
            Contact(firstName = "Marie", lastName = "Dupont", phones = listOf(Phone("06 12 34 56 78"))),
            Contact(firstName = "Antoine", lastName = "Bernard", phones = listOf(Phone("+33 7 11 22 33 44", "travail"))),
            Contact(firstName = "Camille", lastName = "Martin", emails = listOf("camille.martin@example.com"), favorite = true),
            Contact(firstName = "Élodie", lastName = "Petit", phones = listOf(Phone("+33 6 98 76 54 32"))),
            Contact(firstName = "Hugo", lastName = "Leroy"),
            Contact(firstName = "Julien", lastName = "Moreau", birthday = Birthday(null, 3, 14)),
            Contact(firstName = "Léa", lastName = "Fournier"),
            Contact(firstName = "Nicolas", lastName = "Girard"),
            Contact(firstName = "Pauline", lastName = "Roux"),
            Contact(firstName = "Sophie", lastName = "Vincent"),
            Contact(firstName = "Thomas", lastName = "Zimmermann"),
            Contact(firstName = "Yann", lastName = "Gautier"),
            Contact(phones = listOf(Phone("+33 1 40 00 00 00"))),
        )
        contacts.forEachIndexed { i, c0 ->
            val id = "c%05d".format(i)
            val c = c0.copy(id = id, path = ContactFormat.fileNameFor(c0, id), created = now, updated = now)
            fs.writeText(c.path, NoteFormat.serialize(ContactFormat.toNote(c)))
        }
    }

    private val ISO = DateTimeFormatter.ISO_INSTANT

    /** A walk with altitude and heart rate: a loop of [minutes] with a stop in the middle. */
    fun walkGpx(start: Long, minutes: Int = 70): String = buildString {
        append("<?xml version=\"1.0\"?><gpx version=\"1.1\" creator=\"audit\" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\"><trk><name>Balade</name><trkseg>")
        val steps = minutes * 6
        val total = steps + 30
        for (i in 0..total) {
            val a = i.toDouble() / steps * 2 * Math.PI
            // a 5-minute stop half-way: the position holds, then the loop goes on from there
            val k = when {
                i < steps / 2 -> i
                i < steps / 2 + 30 -> steps / 2
                else -> i - 30
            }
            val b = k.toDouble() / steps * 2 * Math.PI
            val lat = 45.90 + 0.02 * sin(b)
            val lon = 6.12 + 0.03 * (1 - cos(b))
            val ele = 450 + 120 * sin(b / 2)
            val t = java.time.Instant.ofEpochMilli(start + i * 10_000L)
            append("<trkpt lat=\"$lat\" lon=\"$lon\"><ele>${"%.1f".format(java.util.Locale.ROOT, ele)}</ele><time>${ISO.format(t)}</time>")
            append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>${110 + (30 * sin(a)).toInt()}</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>")
        }
        append("</trkseg></trk></gpx>")
    }

    /** Ski day: three lift rides up then runs down. */
    fun skiGpx(start: Long): String = buildString {
        append("<?xml version=\"1.0\"?><gpx version=\"1.1\" creator=\"audit\"><trk><name>Ski</name><trkseg>")
        var t = start
        var lat = 45.30
        val lon = 6.58
        var ele = 1800.0
        fun pt() {
            append("<trkpt lat=\"$lat\" lon=\"$lon\"><ele>${"%.1f".format(java.util.Locale.ROOT, ele)}</ele><time>${ISO.format(java.time.Instant.ofEpochMilli(t))}</time></trkpt>")
        }
        repeat(3) {
            repeat(300) { t += 2_000; lat += 0.000036; ele += 1.6; pt() } // lift: 4 m/s, +480 m
            repeat(90) { t += 2_000; lat -= 0.00012; ele -= 5.33; pt() } // run: ~13 m/s, -480 m
        }
        append("</trkseg></trk></gpx>")
    }

    @Suppress("unused")
    private val utc = ZoneOffset.UTC
}
