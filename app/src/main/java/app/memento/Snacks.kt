package app.memento

import androidx.compose.runtime.mutableStateListOf

/** How long a snack stays on screen. Nothing is indefinite: a message nobody dismisses must still go away. */
enum class SnackDuration(val millis: Long) { SHORT(4_000), LONG(10_000) }

/**
 * A message of the app-level snackbar. [undo] is its action, labelled [action]; without [duration] it stays
 * [SnackDuration.LONG] when there is an action to take, [SnackDuration.SHORT] otherwise.
 */
data class Snack(
    val text: String,
    val action: String = "Annuler",
    val duration: SnackDuration? = null,
    val undo: (() -> Unit)? = null,
) {
    val actionLabel: String? get() = if (undo != null) action else null
    val millis: Long get() = (duration ?: if (undo != null) SnackDuration.LONG else SnackDuration.SHORT).millis
}

/**
 * The messages waiting for the snackbar, oldest first; [current] is the one on screen. It lives in the view
 * model, so a message survives screen changes and rotation: it leaves the queue only when [finish]ed (timed out,
 * swiped, or its action tapped), and a host torn down while showing it [interrupt]s it, the next host showing it
 * again for the time it had left. Callable from any thread.
 */
class SnackQueue(private val clock: () -> Long = { System.currentTimeMillis() }) {
    /** One posted message; two equal [Snack]s are still two entries. */
    class Entry internal constructor(val snack: Snack) {
        internal var shownMs = 0L
        internal var since: Long? = null
    }

    private val lock = Any()
    private val entries = mutableStateListOf<Entry>()

    val current: Entry? get() = entries.firstOrNull()
    val size: Int get() = entries.size

    /**
     * Queues [snack]. The same plain message already waiting or shown (a failure repeated by a retry) is not queued
     * again; a message with an action always is, since each undo puts back something different.
     */
    fun post(snack: Snack) {
        synchronized(lock) {
            if (snack.undo == null && entries.any { it.snack.undo == null && it.snack.text == snack.text }) return
            entries.add(Entry(snack))
        }
    }

    /** [entry] goes on screen: the milliseconds it has left. */
    fun start(entry: Entry): Long = synchronized(lock) {
        entry.since = clock()
        (entry.snack.millis - entry.shownMs).coerceAtLeast(0)
    }

    /** [entry] left the screen without being finished (its host went away): it is shown again, for what it had left. */
    fun interrupt(entry: Entry) {
        synchronized(lock) {
            entry.since?.let { entry.shownMs += clock() - it }
            entry.since = null
        }
    }

    /** [entry] is done; its undo runs when [actionPerformed]. A second call for the same entry does nothing. */
    fun finish(entry: Entry, actionPerformed: Boolean) {
        val removed = synchronized(lock) { entries.remove(entry) }
        if (removed && actionPerformed) entry.snack.undo?.invoke()
    }
}
