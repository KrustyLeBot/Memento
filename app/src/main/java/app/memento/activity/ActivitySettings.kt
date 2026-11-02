package app.memento.activity

/**
 * Settings of the Activités folder, kept in `activities/settings.json` so they sync between the phone and
 * the PC like the rest of the notes folder.
 */
data class ActivitySettings(
    /** On saving a recording, also look the altitude up in the terrain model to correct the barometer's drift. */
    val refineAltitude: Boolean = false,
    /** Sports counted in the totals of the Activités screen. */
    val totals: Set<Sport> = Sport.values().toSet(),
) {
    fun toJson(): String =
        "{\"refine_altitude\": $refineAltitude, \"totals\": [" + totals.joinToString(", ") { "\"${it.key}\"" } + "]}\n"

    companion object {
        const val PATH = "activities/settings.json"

        fun parse(text: String): ActivitySettings {
            val refine = Regex("\"refine_altitude\"\\s*:\\s*(true|false)").find(text)?.groupValues?.get(1) == "true"
            val list = Regex("\"totals\"\\s*:\\s*\\[([^\\]]*)]").find(text)?.groupValues?.get(1)
            val sports = if (list == null) Sport.values().toSet() else
                Regex("\"(\\w+)\"").findAll(list).mapNotNull { m -> Sport.values().firstOrNull { it.key == m.groupValues[1] } }.toSet()
            return ActivitySettings(refine, sports)
        }
    }
}
