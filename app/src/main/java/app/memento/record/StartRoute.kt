package app.memento.record

import app.memento.map.OfflinePacks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The 3D question of a recording started on a route not prepared beforehand: its 2D pack is already downloading,
 * a yes turns it into a 2D + 3D one ([OfflinePacks.upgradeRoute]). Unanswered, the pack stays 2D.
 */
data class RouteQuestion(val packId: String, val extraBytes: Long)

object StartRoute {
    private val _state = MutableStateFlow<RouteQuestion?>(null)
    val state: StateFlow<RouteQuestion?> = _state

    fun ask(packId: String, extraBytes: Long) {
        _state.value = RouteQuestion(packId, extraBytes.coerceAtLeast(0))
    }

    fun answer(yes: Boolean) {
        val q = _state.value ?: return
        _state.value = null
        if (yes) OfflinePacks.upgradeRoute(q.packId)
    }

    fun reset() {
        _state.value = null
    }
}
