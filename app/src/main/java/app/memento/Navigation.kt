package app.memento

enum class Screen {
    LIST, EDITOR, SETTINGS, PDF, ACTIVITIES, ACTIVITY, RECORD, DRAWING, AGENDA, CONTACTS, CONTACT, CONTACT_EDIT, CONTACT_DUPLICATES,
    EVENT, EVENT_EDIT, AGENDA_CALENDARS,
}

/** Screens that carry the bottom bar: the home screen of each module. */
val Screen.module: Module?
    get() = when (this) {
        Screen.LIST -> Module.NOTES
        Screen.AGENDA -> Module.AGENDA
        Screen.ACTIVITIES -> Module.ACTIVITIES
        Screen.CONTACTS -> Module.CONTACTS
        else -> null
    }

/** The module root a screen belongs to: where back goes when nothing says where it was opened from. */
val Screen.home: Screen?
    get() = when (this) {
        Screen.LIST, Screen.EDITOR, Screen.PDF, Screen.DRAWING -> Screen.LIST
        Screen.ACTIVITIES, Screen.ACTIVITY, Screen.RECORD -> Screen.ACTIVITIES
        Screen.AGENDA, Screen.EVENT, Screen.EVENT_EDIT, Screen.AGENDA_CALENDARS -> Screen.AGENDA
        Screen.CONTACTS, Screen.CONTACT, Screen.CONTACT_EDIT, Screen.CONTACT_DUPLICATES -> Screen.CONTACTS
        Screen.SETTINGS -> null
    }

/**
 * Where back goes: the screens the current one was reached from, most recent last (agenda → activity → back
 * → agenda, wherever the activity normally lives).
 *
 * - Reaching a module root starts a new chain: nothing before it is ever returned to.
 * - Reaching a screen already in the chain goes back to it (the event saved from its editor, the note editor
 *   when its drawing closes), dropping what was opened from it.
 * - The form editors (event, contact) are left forward into what they saved: back from there skips them.
 */
class Origins(private val max: Int = 12) {
    private val stack = ArrayList<Screen>()

    /**
     * Until this time (ms), the next move forgets its origin: a screen opened by a notification or a widget at a
     * cold start, whose screen behind was never seen. Bounded in time, so an intent that opened nothing (an
     * entry since deleted) does not make a later tap of the user forget where it came from.
     */
    private var forgetUntil = 0L

    fun forgetNextOrigin(now: Long, withinMs: Long = 15_000) { forgetUntil = now + withinMs }

    val chain: List<Screen> get() = stack.toList()

    fun moved(from: Screen, to: Screen, now: Long = System.currentTimeMillis()) {
        if (from == to) return
        val forget = now < forgetUntil
        forgetUntil = 0L
        val i = stack.lastIndexOf(to)
        when {
            to.module != null -> stack.clear()
            i >= 0 -> while (stack.size > i) stack.removeAt(stack.size - 1)
            forget -> stack.clear()
            from in FORMS -> {}
            else -> {
                stack.add(from)
                if (stack.size > max) stack.removeAt(0)
            }
        }
    }

    /** The screen back leads to from [current]: the last origin, else [fallback]. */
    fun pop(current: Screen, fallback: Screen): Screen {
        while (stack.isNotEmpty()) {
            val s = stack.removeAt(stack.size - 1)
            if (s != current) return s
        }
        return fallback
    }

    fun clear() = stack.clear()

    private companion object {
        val FORMS = setOf(Screen.EVENT_EDIT, Screen.CONTACT_EDIT)
    }
}
