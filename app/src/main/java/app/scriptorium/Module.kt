package app.scriptorium

/** The top-level sections of the app, reached from the bottom bar. Notes is always shown. */
enum class Module(val key: String, val label: String) {
    NOTES("notes", "Notes"),
    AGENDA("agenda", "Agenda"),
    ACTIVITIES("activities", "Activités"),
    CONTACTS("contacts", "Contacts");

    companion object {
        fun of(key: String?): Module? = entries.firstOrNull { it.key == key }

        /** Modules the user can hide; Notes holds the settings entry and stays. */
        val hideable = listOf(AGENDA, ACTIVITIES, CONTACTS)
    }
}
