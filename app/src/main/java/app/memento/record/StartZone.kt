package app.memento.record

import app.memento.Zone3dPolicy
import app.memento.map.NetState
import app.memento.map.OfflinePacks
import app.memento.map.TilePlans
import app.memento.map.ZoneSpec
import app.memento.map.Zones
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The offline zone around the start of a recording without route: where and how large ([zone]), the answer to the
 * 3D question and what has been asked of [OfflinePacks] so far. The 2D zone is always asked for; the 3D one only
 * after a yes.
 */
data class StartZone(
    val zone: ZoneSpec,
    /** What the 3D adds to the 2D zone, shown in the question. */
    val extraBytes: Long,
    val answer: Answer,
    val asked2d: Boolean = false,
    val asked3d: Boolean = false,
) {
    enum class Answer { PENDING, YES, NO }

    /** The download to ask for now (true = 3D), null when nothing is left to ask. */
    fun next(): Boolean? = when {
        answer == Answer.YES && !asked3d -> true
        !asked2d -> false
        else -> null
    }

    fun requested(with3d: Boolean) = if (with3d) copy(asked2d = true, asked3d = true) else copy(asked2d = true)

    companion object {
        /** [already3d]: a pack with relief already covers the zone, so there is nothing to ask. */
        fun of(zone: ZoneSpec, policy: Zone3dPolicy, already3d: Boolean = false) = StartZone(
            zone, TilePlans.zone3dExtraBytes(zone.lon, zone.lat, zone.side2dKm, zone.side3dKm),
            when {
                policy == Zone3dPolicy.NEVER -> Answer.NO
                policy == Zone3dPolicy.ALWAYS || already3d -> Answer.YES
                else -> Answer.PENDING
            },
        )
    }
}

/** The [StartZone] of the running recording, and the zone packs it pins until the recording is saved or dropped. */
object StartZones {
    private val _state = MutableStateFlow<StartZone?>(null)
    val state: StateFlow<StartZone?> = _state
    private val pinned = HashSet<String>()
    // a pack id coming back after the recording ended must not pin anything
    private var generation = 0

    /** First fix of a recording without route. Settles the question at once unless the setting is "Demander". */
    @Synchronized
    fun begin(zone: ZoneSpec, policy: Zone3dPolicy) {
        if (_state.value != null) return
        val served = Zones.reusable(OfflinePacks.packs.value, zone, true, System.currentTimeMillis())
        _state.value = StartZone.of(zone, policy, served != null && !Zones.needsUpgrade(served, zone, true))
    }

    @Synchronized
    fun answer(yes: Boolean) {
        val s = _state.value ?: return
        if (s.answer != StartZone.Answer.PENDING) return
        _state.value = s.copy(answer = if (yes) StartZone.Answer.YES else StartZone.Answer.NO)
        pump()
    }

    /** Asks for what is due when online; offline, it waits for a later fix. Always around the start. */
    @Synchronized
    fun pump() {
        val s = _state.value ?: return
        val with3d = s.next() ?: return
        if (!NetState.online.value) return
        _state.value = s.requested(with3d)
        val gen = generation
        OfflinePacks.ensureZone(s.zone, with3d) { id -> pin(id, gen) }
    }

    @Synchronized
    private fun pin(id: String, gen: Int) {
        if (gen != generation || !pinned.add(id)) return
        OfflinePacks.pin(id)
    }

    @Synchronized
    fun reset() {
        generation++
        pinned.forEach { OfflinePacks.unpin(it) }
        pinned.clear()
        _state.value = null
    }
}
