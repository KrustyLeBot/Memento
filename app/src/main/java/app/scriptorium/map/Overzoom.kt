package app.scriptorium.map

/**
 * Stand-in for a photo tile missing offline: the part of an ancestor tile [dz] zooms up that covers the requested
 * one, scaled up. MapLibre does not overzoom a raster tile it could not get (it shows nothing), so the tile server
 * does it: outside a pack's corridor, zooming in past its square zoom shows the medium-definition photo enlarged.
 */
data class Overzoom(val ancestor: TileKey, val left: Int, val top: Int, val size: Int) {
    companion object {
        const val TILE_PX = 256

        /** Past 6 zooms the crop is 4 px wide: nothing left to see. */
        const val MAX_DZ = 6

        fun of(key: TileKey, dz: Int): Overzoom {
            require(dz in 1..key.z && dz <= 8) { "dz" }
            val size = TILE_PX shr dz
            val ax = key.x shr dz
            val ay = key.y shr dz
            val left = (key.x - (ax shl dz)) * size
            val top = (key.y - (ay shl dz)) * size
            return Overzoom(TileKey(key.kind, key.z - dz, ax, ay), left, top, size)
        }

        /** The ancestors to look for, nearest first. */
        fun candidates(key: TileKey): List<Overzoom> = (1..minOf(MAX_DZ, key.z)).map { of(key, it) }
    }
}
