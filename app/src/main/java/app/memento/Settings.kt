package app.memento

import android.content.Context
import android.net.Uri
import android.os.Environment
import app.memento.map.TilePlans
import app.memento.scan.ScanFormat
import java.io.File

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class GpsMode(val label: String) {
    NORMAL("Normale"),
    ECONOMY("Économie de batterie"),
}

/** Whether a recording without route also downloads the 3D map around its start. */
enum class Zone3dPolicy(val label: String) {
    ASK("Demander"),
    ALWAYS("Toujours"),
    NEVER("Jamais"),
}

/** Where notes are stored: the shared Documents folder, or a SAF tree picked by the user. */
sealed class StorageConfig {
    data object Default : StorageConfig()
    data class Tree(val uri: Uri) : StorageConfig()
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var storageUri: String?
        get() = prefs.getString("storage_uri", null)
        set(v) = prefs.edit().putString("storage_uri", v).apply()

    var gridView: Boolean
        get() = prefs.getBoolean("grid_view", true)
        set(v) = prefs.edit().putBoolean("grid_view", v).apply()

    var theme: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)
        set(v) = prefs.edit().putString("theme", v.name).apply()

    var scanFormat: ScanFormat
        get() = runCatching { ScanFormat.valueOf(prefs.getString("scan_format", "AUTO")!!) }.getOrDefault(ScanFormat.AUTO)
        set(v) = prefs.edit().putString("scan_format", v.name).apply()

    /** GPS fix rate while recording: [GpsMode.ECONOMY] trades precision for battery. */
    var gpsMode: GpsMode
        get() = runCatching { GpsMode.valueOf(prefs.getString("gps_mode", "NORMAL")!!) }.getOrDefault(GpsMode.NORMAL)
        set(v) = prefs.edit().putString("gps_mode", v.name).apply()

    var zone3d: Zone3dPolicy
        get() = runCatching { Zone3dPolicy.valueOf(prefs.getString("zone_3d", "ASK")!!) }.getOrDefault(Zone3dPolicy.ASK)
        set(v) = prefs.edit().putString("zone_3d", v.name).apply()

    /** Side in km of the 2D offline square downloaded around the start of a recording without route. */
    var zone2dKm: Int
        get() = TilePlans.snapKm(prefs.getInt("zone_2d_km", TilePlans.ZONE_KM_DEFAULT), TilePlans.ZONE_2D_KM)
        set(v) = prefs.edit().putInt("zone_2d_km", TilePlans.snapKm(v, TilePlans.ZONE_2D_KM)).apply()

    /** Side in km of the optional 3D offline square. */
    var zone3dKm: Int
        get() = TilePlans.snapKm(prefs.getInt("zone_3d_km", TilePlans.ZONE_KM_DEFAULT), TilePlans.ZONE_3D_KM)
        set(v) = prefs.edit().putInt("zone_3d_km", TilePlans.snapKm(v, TilePlans.ZONE_3D_KM)).apply()

    /** Latitude of the last recording start, for the zone estimates shown in the settings. */
    var lastStartLat: Double
        get() = prefs.getFloat("last_start_lat", Float.NaN).toDouble().takeIf { !it.isNaN() } ?: TilePlans.DEFAULT_LAT
        set(v) = prefs.edit().putFloat("last_start_lat", v.toFloat()).apply()

    /** Module shown when the app opens: the last one used. */
    var lastModule: Module
        get() = Module.of(prefs.getString("last_module", null)) ?: Module.NOTES
        set(v) = prefs.edit().putString("last_module", v.key).apply()

    var hiddenModules: Set<Module>
        get() = prefs.getStringSet("hidden_modules", emptySet()).orEmpty().mapNotNull { Module.of(it) }.toSet()
        set(v) = prefs.edit().putStringSet("hidden_modules", v.map { it.key }.toSet()).apply()

    /** The user declined the import from a former folder: do not ask again. */
    var legacyImportDismissed: Boolean
        get() = prefs.getBoolean("legacy_import_dismissed", false)
        set(v) = prefs.edit().putBoolean("legacy_import_dismissed", v).apply()
    /** Maps open in 3D (relief) unless the user chose 2D; the choice is kept for the next map. */
    var map3d: Boolean
        get() = prefs.getBoolean("map_3d", true)
        set(v) = prefs.edit().putBoolean("map_3d", v).apply()

    val storage: StorageConfig
        get() = storageUri?.let { StorageConfig.Tree(Uri.parse(it)) } ?: StorageConfig.Default

    companion object {
        fun defaultDir(): File =
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Memento")

        /** Folders of the app's former names, newest first; the first one holding files is imported into [defaultDir]. */
        private val LEGACY_NAMES = listOf("Scriptorium", "Griffonote")

        fun legacyDir(): File {
            val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            return LegacyFolder.pick(LEGACY_NAMES.map { File(docs, it) })
        }
    }
}
