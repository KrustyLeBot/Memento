package app.memento.map

/**
 * An offline pack's manifest (`manifest.json` in its folder). [kinds] says what it holds: "ortho" tiles
 * (the IGN aerial photo), "dem" terrain tiles, "peaks". A pack from before the photo ("plan" instead of "ortho") is
 * [obsolete]: it covers nothing, is never resumed, and goes with the others when its 24 h are up. [complete] is false while downloading, and stays false when the download
 * was interrupted (process killed, no network, disk full): such a pack can be resumed.
 */
data class Pack(
    val id: String,
    val name: String,
    val created: Long,
    val square: Bbox,
    val zmax: Int,
    val kinds: Set<String>,
    val bytes: Long,
    val complete: Boolean,
    /** The pack carries the route it was made for (`route.json`), so it can be followed. */
    val hasRoute: Boolean,
    /**
     * Route packs from the corridor builds: the band along the track holding the zooms above [zmax]. [zmax] stays the
     * top zoom over the whole [square], so a manifest without it (older builds, zone packs) reads as before.
     */
    val corridor: Corridor? = null,
    /** 3D zone packs: where the relief and the peaks are, when that is not the whole [square]. */
    val relief: Bbox? = null,
) {
    /** Where the 3D view works offline from this pack (given [has3d]). */
    val reliefSquare: Bbox get() = relief ?: square

    val has3d: Boolean get() = KIND_DEM in kinds && !obsolete

    val obsolete: Boolean get() = KIND_ORTHO !in kinds

    fun expiresAt(): Long = created + LIFETIME_MS

    fun expired(now: Long): Boolean = now >= expiresAt()

    /** The tiles this pack downloads. */
    fun plan(): TilePlan = TilePlan(square, TilePlans.ORTHO_MIN_Z..zmax, if (has3d) TilePlans.DEM_ZOOMS else null, corridor, relief)

    fun toJson(): String = buildString {
        append('{')
        append("\"id\":").append(Json.quote(id))
        append(",\"name\":").append(Json.quote(name))
        append(",\"created\":").append(created)
        append(",\"square\":[").append(square.toList().joinToString(",") { Json.num(it, 7) }).append(']')
        append(",\"zmax\":").append(zmax)
        append(",\"kinds\":[").append(kinds.sorted().joinToString(",") { Json.quote(it) }).append(']')
        append(",\"bytes\":").append(bytes)
        append(",\"complete\":").append(complete)
        append(",\"route\":").append(hasRoute)
        relief?.let { r -> append(",\"relief\":[").append(r.toList().joinToString(",") { Json.num(it, 7) }).append(']') }
        corridor?.let { c ->
            append(",\"corridor\":{\"buffer\":").append(Json.num(c.bufferM, 1))
            append(",\"zooms\":[").append(c.zooms.first).append(',').append(c.zooms.last).append(']')
            append(",\"line\":[")
            for (i in c.lons.indices) {
                if (i > 0) append(',')
                append(Json.num(c.lons[i], 6)).append(',').append(Json.num(c.lats[i], 6))
            }
            append("]}")
        }
        append('}')
    }

    companion object {
        const val LIFETIME_MS = 24L * 3600 * 1000
        const val KIND_ORTHO = TileKey.KIND_ORTHO
        const val KIND_DEM = TileKey.KIND_DEM
        const val KIND_PEAKS = "peaks"

        fun parse(text: String): Pack {
            val o = Json.parse(text).asMap() ?: throw Json.JsonException("Manifeste invalide")
            val square = Bbox.fromList(o["square"].asList()?.mapNotNull { it.asDouble() }) ?: throw Json.JsonException("Manifeste sans carré")
            return Pack(
                id = o["id"] as? String ?: throw Json.JsonException("Manifeste sans id"),
                name = o["name"] as? String ?: "",
                created = o["created"].asDouble()?.toLong() ?: throw Json.JsonException("Manifeste sans date"),
                square = square,
                zmax = o["zmax"].asDouble()?.toInt() ?: TilePlans.ORTHO_MIN_Z,
                kinds = o["kinds"].asList()?.filterIsInstance<String>()?.toSet().orEmpty(),
                bytes = o["bytes"].asDouble()?.toLong() ?: 0L,
                complete = o["complete"] as? Boolean ?: false,
                hasRoute = o["route"] as? Boolean ?: false,
                corridor = o["corridor"].asMap()?.let(::parseCorridor),
                relief = Bbox.fromList(o["relief"].asList()?.mapNotNull { it.asDouble() }),
            )
        }

        private fun parseCorridor(c: Map<String, Any?>): Corridor? {
            val z = c["zooms"].asList()?.mapNotNull { it.asDouble()?.toInt() }?.takeIf { it.size == 2 } ?: return null
            val line = c["line"].asList()?.mapNotNull { it.asDouble() } ?: return null
            if (line.size < 2 || line.size % 2 != 0) return null
            return Corridor(
                line.filterIndexed { i, _ -> i % 2 == 0 }, line.filterIndexed { i, _ -> i % 2 == 1 },
                c["buffer"].asDouble() ?: return null, z[0]..z[1],
            )
        }

        /** Packs to delete at [now]: expired ones, whatever their state. */
        fun toPurge(packs: List<Pack>, now: Long): List<Pack> = packs.filter { it.expired(now) }

        /**
         * A complete pack with terrain covering the point: the 3D view works offline there. A [pinned] pack
         * (followed by the running recording) still counts past its 24 h: it is kept until the recording ends.
         */
        fun covers3d(packs: List<Pack>, lon: Double, lat: Double, now: Long, pinned: Set<String> = emptySet()): Boolean =
            packs.any { it.complete && it.has3d && (!it.expired(now) || it.id in pinned) && it.reliefSquare.contains(lon, lat) }
    }
}
