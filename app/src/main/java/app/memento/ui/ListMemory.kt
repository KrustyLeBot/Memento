package app.memento.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import app.memento.activity.Sport
import app.memento.agenda.AgendaSources
import app.memento.agenda.AgendaView
import app.memento.agenda.Entry
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * What the module lists keep while a detail (or another module) is shown. Their screens leave the composition
 * then, so `remember` and `rememberSaveable` would bring them back scrolled to the top; held by the view model,
 * this also survives a configuration change.
 */
class ListMemory {
    val notes = LazyStaggeredGridState()
    /** Id of the note last opened from the list, brought back into view when the list shows again. */
    var noteTapped: String? = null

    val activities = LazyListState()
    var activitySport by mutableStateOf<Sport?>(null)
    /** Id of the activity last opened from the list. */
    var activityTapped: String? = null

    val contacts = LazyListState()
    /** Section and id of the contact last opened from the list (a favourite is also listed under its letter). */
    var contactTapped: Pair<String, String>? = null

    val agenda = AgendaMemory()
}

/** The agenda's list view (its loaded months and position) and the week and day views' hour grid. */
class AgendaMemory {
    val list = LazyListState()
    /** False until the list view was first shown: it then starts on the selected day. */
    var started = false
    var first by mutableStateOf(YearMonth.now().minusMonths(1))
    var last by mutableStateOf(YearMonth.now().plusMonths(3))
    var months by mutableStateOf<Map<YearMonth, List<Entry>>>(emptyMap())
    var monthsOf by mutableStateOf<AgendaSources?>(null)
    var monthsZone by mutableStateOf<ZoneId>(ZoneId.systemDefault())
    var target by mutableStateOf<LocalDate?>(null)
    /** The last "scroll to the selected day" request the list followed (the "Aujourd'hui" button, a jump). */
    var scrollRequestSeen = 0
    /** The day whose entry was last opened from the list. */
    var dayTapped: LocalDate? = null

    /** Vertical position of the week and day hour grid, shared by their pages; -1 until first shown. */
    var hourScroll by mutableIntStateOf(-1)

    private var view: AgendaView? = null

    /**
     * Another view chosen: it starts afresh (the list on the selected day, the hours around now), as it always
     * did. Only coming back to the same view (from an entry, another module) finds it as it was left.
     */
    fun showing(v: AgendaView) {
        if (v == view) return
        view = v
        started = false
        hourScroll = -1
    }
}

/** Whether an item laid out over [start, end) is entirely inside the visible part [viewStart, viewEnd) of a list. */
fun fullyShown(start: Int, end: Int, viewStart: Int, viewEnd: Int): Boolean = start >= viewStart && end <= viewEnd

/**
 * Back on a list from a detail: the item that was tapped stays where it is when it is still in view, and is
 * scrolled to when it is not (the list changed meanwhile: an edit moved it, a sync added items above it).
 * [index] is its position in the lazy list now, null when nothing is to be revealed, -1 when it is gone.
 */
@Composable
fun RevealOnReturn(state: LazyListState, key: Any?, index: Int?, ready: Boolean = true, onDone: () -> Unit) {
    LaunchedEffect(key, index, ready) {
        if (key == null || index == null || !ready) return@LaunchedEffect
        // the first layout of the screen, not the one the state kept from before it was left
        withFrameNanos {}
        withFrameNanos {}
        if (index >= 0) {
            val info = state.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == key }
            if (item == null || !fullyShown(item.offset, item.offset + item.size, info.viewportStartOffset, info.viewportEndOffset - info.afterContentPadding)) {
                state.scrollToItem(index)
            }
        }
        onDone()
    }
}

@Composable
fun RevealOnReturn(state: LazyStaggeredGridState, key: Any?, index: Int?, ready: Boolean = true, onDone: () -> Unit) {
    LaunchedEffect(key, index, ready) {
        if (key == null || index == null || !ready) return@LaunchedEffect
        withFrameNanos {}
        withFrameNanos {}
        if (index >= 0) {
            val info = state.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == key }
            if (item == null || !fullyShown(item.offset.y, item.offset.y + item.size.height, info.viewportStartOffset, info.viewportEndOffset - info.afterContentPadding)) {
                state.scrollToItem(index)
            }
        }
        onDone()
    }
}
