package app.memento.agenda

import app.memento.NoteFormat
import app.memento.fold

/** A named, coloured group of events (`agenda/calendars.json`). */
data class Calendar(
    val id: String,
    val name: String,
    val color: String,
    val visible: Boolean = true,
    /** Set when an ICS import created it (undo removes it). */
    val importId: String? = null,
    val extra: Map<String, Any?> = emptyMap(),
)

/** A source of entries that is not an event file: activities, notes, birthdays. */
data class Layer(
    val visible: Boolean = true,
    val color: String = "#78858F",
    /** Activities only: sport keys shown; null = all. */
    val sports: List<String>? = null,
    val extra: Map<String, Any?> = emptyMap(),
)

/**
 * `agenda/calendars.json`, synced with the notes like `activities/settings.json`. Unknown keys are kept.
 * An unreadable file gives the defaults plus a [problem] (French) for the UI; it is never overwritten silently.
 * Same format and output as Python's `core/agenda/calendars.py`.
 */
data class CalendarSettings(
    val calendars: List<Calendar> = listOf(Calendar("perso", "Perso", PALETTE[0].first)),
    val default: String = "perso",
    val layers: Map<String, Layer> = defaultLayers(),
    val extra: Map<String, Any?> = emptyMap(),
    val problem: String? = null,
) {
    /** The calendar of an event; an unknown or missing id is the default calendar. */
    fun calendar(id: String?): Calendar = calendars.firstOrNull { it.id == id } ?: defaultCalendar()

    fun defaultCalendar(): Calendar =
        calendars.firstOrNull { it.id == default } ?: calendars.firstOrNull() ?: Calendar(default.ifEmpty { "perso" }, "Perso", PALETTE[0].first)

    fun layer(key: String): Layer = layers[key] ?: Layer(true, DEFAULT_LAYER_COLORS[key] ?: "#78858F")

    /** Reminders of a new event ("Rappels par défaut"), synced: `default_alerts` / `default_alerts_all_day` (`-15m`, …). */
    fun defaultAlerts(allDay: Boolean): List<Int> {
        val raw = extra[if (allDay) KEY_ALERTS_ALL_DAY else KEY_ALERTS] ?: return if (allDay) emptyList() else listOf(-15)
        return (raw as? List<*>).orEmpty().mapNotNull { v -> try { Alerts.parse(v.toString()) } catch (_: IllegalArgumentException) { null } }
    }

    fun withDefaultAlerts(allDay: Boolean, alerts: List<Int>): CalendarSettings =
        copy(extra = extra + ((if (allDay) KEY_ALERTS_ALL_DAY else KEY_ALERTS) to alerts.distinct().sorted().map { Alerts.format(it) }))

    /**
     * Reminders of every contact birthday, existing ones included (computed, nothing is written into the contacts),
     * synced: `birthday_alerts`. Same all-day offsets as an event (from 00:00 of the day); by default 1 week before
     * at 09:00. An empty list turns them off.
     */
    fun birthdayAlerts(): List<Int> {
        val raw = extra[KEY_BIRTHDAY_ALERTS] ?: return DEFAULT_BIRTHDAY_ALERTS
        return (raw as? List<*>).orEmpty().mapNotNull { v -> try { Alerts.parse(v.toString()) } catch (_: IllegalArgumentException) { null } }
    }

    fun withBirthdayAlerts(alerts: List<Int>): CalendarSettings =
        copy(extra = extra + (KEY_BIRTHDAY_ALERTS to alerts.distinct().sorted().map { Alerts.format(it) }))

    fun withLayer(key: String, transform: (Layer) -> Layer): CalendarSettings = copy(layers = layers + (key to transform(layer(key))))

    fun withCalendar(id: String, transform: (Calendar) -> Calendar): CalendarSettings =
        copy(calendars = calendars.map { if (it.id == id) transform(it) else it })

    /** A new calendar named [name] (unique id from the name, the next free palette colour unless [color]). */
    fun addCalendar(name: String, color: String? = null): Pair<CalendarSettings, Calendar> {
        val cal = Calendar(uniqueCalendarId(name, this), name.trim(), color ?: nextColor(this))
        return copy(calendars = calendars + cal) to cal
    }

    /** Without calendar [id]; the last calendar cannot go. The default moves to the first one left. */
    fun removeCalendar(id: String): CalendarSettings {
        val left = calendars.filter { it.id != id }
        require(left.isNotEmpty()) { "Il faut garder au moins un agenda" }
        return copy(calendars = left, default = if (defaultCalendar().id == id) left[0].id else default)
    }

    /** The files a calendar holds: its events (an unknown calendar id counts as the default) and their exception files. */
    fun eventsOf(id: String, events: List<Event>): List<Event> {
        val own = events.filter { calendar(it.calendar).id == id }
        val ids = own.map { it.id }.toSet()
        return own + events.filter { it.recurrenceOf in ids && it.id !in ids }
    }

    fun toJson(): String {
        val cals = calendars.map { c ->
            val d = linkedMapOf<String, Any?>("id" to c.id, "name" to c.name, "color" to c.color, "visible" to c.visible)
            c.importId?.let { d["import"] = it }
            c.extra.forEach { (k, v) -> if (k !in d) d[k] = v }
            d
        }
        val ls = LinkedHashMap<String, Any?>()
        for ((key, l) in layers) {
            val d = linkedMapOf<String, Any?>("visible" to l.visible, "color" to l.color)
            l.sports?.let { d["sports"] = it }
            l.extra.forEach { (k, v) -> if (k !in d) d[k] = v }
            ls[key] = d
        }
        val data = linkedMapOf<String, Any?>("calendars" to cals, "default" to default, "layers" to ls)
        extra.forEach { (k, v) -> if (k !in data) data[k] = v }
        return Json.write(data) + "\n"
    }

    companion object {
        const val PATH = "agenda/calendars.json"
        const val LAYER_ACTIVITIES = "activities"
        const val LAYER_NOTES = "notes"
        const val LAYER_BIRTHDAYS = "birthdays"
        const val KEY_ALERTS = "default_alerts"
        const val KEY_ALERTS_ALL_DAY = "default_alerts_all_day"
        const val KEY_BIRTHDAY_ALERTS = "birthday_alerts"
        /** 1 week before at 09:00 (minutes from 00:00 of the birthday). */
        val DEFAULT_BIRTHDAY_ALERTS = listOf(-7 * 1440 + 9 * 60)

        /** Fixed palette: each colour has a contrast of at least 3:1 against white and against #121212. */
        val PALETTE: List<Pair<String, String>> = listOf(
            "#4F8BF5" to "Bleu", "#2E9E5B" to "Vert", "#D9534F" to "Rouge", "#E0663A" to "Orange",
            "#B0851A" to "Ocre", "#1E9A8A" to "Sarcelle", "#2B8FC4" to "Ciel", "#5C6BC0" to "Indigo",
            "#8E63CE" to "Violet", "#C2549C" to "Prune", "#D64D6F" to "Framboise", "#78858F" to "Ardoise",
        )
        val DEFAULT_LAYER_COLORS = linkedMapOf(LAYER_ACTIVITIES to "#2E9E5B", LAYER_NOTES to "#8E63CE", LAYER_BIRTHDAYS to "#D64D6F")

        fun defaultLayers(): Map<String, Layer> = DEFAULT_LAYER_COLORS.mapValues { Layer(true, it.value) }

        fun parse(text: String): CalendarSettings = try {
            parseOrThrow(text)
        } catch (e: Exception) {
            if (e !is Json.JsonException && e !is IllegalArgumentException && e !is ClassCastException) throw e
            CalendarSettings(problem = "$PATH illisible : ${e.message}")
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseOrThrow(text: String): CalendarSettings {
            val data = Json.parse(text) as? Map<String, Any?> ?: throw IllegalArgumentException("pas un objet JSON")
            val cals = ArrayList<Calendar>()
            val rawCals = data["calendars"]
            if (rawCals != null && rawCals !is List<*>) throw IllegalArgumentException("calendars n'est pas une liste")
            for (raw in (rawCals as? List<Any?>).orEmpty()) {
                val m = raw as? Map<String, Any?>
                val id = m?.get("id") as? String
                if (m == null || id.isNullOrEmpty()) throw IllegalArgumentException("agenda sans id : $raw")
                cals.add(Calendar(
                    id = id,
                    name = (m["name"] as? String)?.ifEmpty { null } ?: m["name"]?.toString()?.ifEmpty { null } ?: id,
                    color = (m["color"] as? String)?.ifEmpty { null } ?: PALETTE[cals.size % PALETTE.size].first,
                    visible = m["visible"] != false,
                    importId = m["import"] as? String,
                    extra = m.filterKeys { it !in setOf("id", "name", "color", "visible", "import") },
                ))
            }
            val layers = LinkedHashMap(defaultLayers())
            for ((key, raw) in (data["layers"] as? Map<String, Any?>).orEmpty()) {
                val m = raw as? Map<String, Any?> ?: continue
                layers[key] = Layer(
                    visible = m["visible"] != false,
                    color = (m["color"] as? String)?.ifEmpty { null } ?: DEFAULT_LAYER_COLORS[key] ?: "#78858F",
                    sports = (m["sports"] as? List<Any?>)?.map { it.toString() },
                    extra = m.filterKeys { it !in setOf("visible", "color", "sports") },
                )
            }
            if (cals.isEmpty()) cals.add(Calendar("perso", "Perso", PALETTE[0].first))
            val default = data["default"] as? String ?: cals[0].id
            return CalendarSettings(cals, default, layers, data.filterKeys { it !in setOf("calendars", "default", "layers") })
        }

        /** The first palette colour no calendar uses yet (cycling when all are taken). */
        fun nextColor(settings: CalendarSettings): String {
            val used = settings.calendars.map { it.color.uppercase() }
            return PALETTE.firstOrNull { it.first.uppercase() !in used }?.first ?: PALETTE[settings.calendars.size % PALETTE.size].first
        }

        fun uniqueCalendarId(name: String, settings: CalendarSettings): String {
            val base = NoteFormat.slug(name)
            val taken = settings.calendars.map { it.id }.toSet()
            var candidate = base
            var n = 2
            while (candidate in taken) candidate = "$base-${n++}"
            return candidate
        }

        /** The existing calendar of the same name (accent- and case-insensitive), for re-imports. */
        fun byName(name: String, settings: CalendarSettings): Calendar? = settings.calendars.firstOrNull { it.name.fold() == name.fold() }
    }
}
