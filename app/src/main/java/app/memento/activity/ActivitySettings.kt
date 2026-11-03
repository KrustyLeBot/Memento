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
    /** Where the map's relief comes from, on every platform. */
    val relief: Relief = Relief.AWS,
) {
    fun toJson(): String =
        "{\"refine_altitude\": $refineAltitude, \"totals\": [" + totals.joinToString(", ") { "\"${it.key}\"" } + "], " +
            "\"relief\": \"${relief.key}\"}\n"

    companion object {
        const val PATH = "activities/settings.json"

        fun parse(text: String): ActivitySettings {
            val refine = Regex("\"refine_altitude\"\\s*:\\s*(true|false)").find(text)?.groupValues?.get(1) == "true"
            val list = Regex("\"totals\"\\s*:\\s*\\[([^\\]]*)]").find(text)?.groupValues?.get(1)
            val sports = if (list == null) Sport.values().toSet() else
                Regex("\"(\\w+)\"").findAll(list).mapNotNull { m -> Sport.values().firstOrNull { it.key == m.groupValues[1] } }.toSet()
            val relief = Regex("\"relief\"\\s*:\\s*\"(\\w+)\"").find(text)?.groupValues?.get(1)
            return ActivitySettings(refine, sports, Relief.values().firstOrNull { it.key == relief } ?: Relief.AWS)
        }
    }
}

/**
 * The map's relief: AWS Terrarium (256 px PNG to z13, from sources of about 30 m in France) or Mapterhorn (512 px WebP,
 * IGN RGE ALTI 5 m everywhere in France and LiDAR HD 0.5–1 m where flown, asked to z15).
 */
enum class Relief(val key: String, val label: String) {
    AWS("aws", "AWS (standard)"),
    MAPTERHORN("mapterhorn", "Mapterhorn (précis)"),
}
