package app.memento.map

import app.memento.activity.Relief

/**
 * The relief the map uses ([Relief], from the synced Activités settings), for what is planned and drawn outside the
 * screens too (a recording's zone pack). Set when the settings are read or changed.
 */
object MapRelief {
    @Volatile
    var current: Relief = Relief.AWS

    /** The synced "Bâtiments" setting: the buildings drawn in 3D online. */
    @Volatile
    var buildings: Boolean = true

    /** The tile kind of [relief]'s relief tiles. */
    fun kind(relief: Relief = current): String = if (relief == Relief.MAPTERHORN) TileKey.KIND_DEM_HD else TileKey.KIND_DEM
}
