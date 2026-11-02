package app.memento.map

/**
 * The zone around the start of a recording without route: its two squares (sides in km, settings), which existing
 * pack serves it, and how a 2D zone pack becomes a 3D one. Pure logic.
 */
data class ZoneSpec(val lon: Double, val lat: Double, val side2dKm: Int, val side3dKm: Int) {
    fun plan(with3d: Boolean): TilePlan = if (with3d) TilePlans.forZone3d(lon, lat, side2dKm, side3dKm) else TilePlans.forZone(lon, lat, side2dKm)

    /** The middle of each square: a pack covering it serves a recording starting here (a second one from the same spot). */
    val near2d: Bbox get() = TilePlans.zoneSquare(lon, lat, side2dKm).half()
    val near3d: Bbox get() = TilePlans.zoneSquare(lon, lat, side3dKm).half()

    private fun Bbox.half(): Bbox {
        val dw = (e - w) / 4
        val dh = (n - s) / 4
        return Bbox(w + dw, s + dh, e - dw, n - dh)
    }
}

object Zones {
    val KINDS_3D = setOf(Pack.KIND_ORTHO, Pack.KIND_DEM, Pack.KIND_PEAKS)

    fun name(with3d: Boolean) = if (with3d) "Zone autour du départ (3D)" else "Zone autour du départ (2D)"

    /**
     * A live pack serving [z]: for 2D, any covering the middle of the 2D square; for 3D, one whose relief also covers
     * the middle of the 3D square, else a 2D zone pack to upgrade. Null when a new pack is needed.
     */
    fun reusable(packs: List<Pack>, z: ZoneSpec, with3d: Boolean, now: Long): Pack? {
        val near2d = z.near2d
        val live = packs.filter { !it.expired(now) && !it.obsolete && it.square.covers(near2d) }
        if (!with3d) return live.firstOrNull()
        val near3d = z.near3d
        return live.firstOrNull { it.has3d && it.reliefSquare.covers(near3d) } ?: live.firstOrNull { !it.hasRoute && !it.has3d }
    }

    fun needsUpgrade(p: Pack, z: ZoneSpec, with3d: Boolean) = with3d && !(p.has3d && p.reliefSquare.covers(z.near3d))

    /**
     * [p] as a 3D zone: its creation time (so its expiry) and its photo tiles kept, the photo widened to the 3D square
     * when that is larger, relief, peaks and the z16 disc around the start left to fetch.
     */
    fun upgrade(p: Pack, z: ZoneSpec): Pack {
        val plan = z.plan(true)
        return p.copy(
            name = name(true), kinds = KINDS_3D, square = TilePlans.union(p.square, plan.square), zmax = plan.orthoZooms.last,
            corridor = plan.corridor, relief = plan.demSquare, complete = false,
        )
    }
}
