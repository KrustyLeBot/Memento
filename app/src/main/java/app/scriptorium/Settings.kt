package app.scriptorium

import android.content.Context
import android.net.Uri
import android.os.Environment
import app.scriptorium.scan.ScanFormat
import java.io.File

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class GpsMode(val label: String) {
    NORMAL("Normale"),
    ECONOMY("Économie de batterie"),
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

    /** Module shown when the app opens: the last one used. */
    var lastModule: Module
        get() = Module.of(prefs.getString("last_module", null)) ?: Module.NOTES
        set(v) = prefs.edit().putString("last_module", v.key).apply()

    var hiddenModules: Set<Module>
        get() = prefs.getStringSet("hidden_modules", emptySet()).orEmpty().mapNotNull { Module.of(it) }.toSet()
        set(v) = prefs.edit().putStringSet("hidden_modules", v.map { it.key }.toSet()).apply()

    /** The user declined the import from the former Griffonote folder: do not ask again. */
    var legacyImportDismissed: Boolean
        get() = prefs.getBoolean("legacy_import_dismissed", false)
        set(v) = prefs.edit().putBoolean("legacy_import_dismissed", v).apply()

    val storage: StorageConfig
        get() = storageUri?.let { StorageConfig.Tree(Uri.parse(it)) } ?: StorageConfig.Default

    companion object {
        fun defaultDir(): File =
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Scriptorium")

        /** Folder of the app's former name, imported once into [defaultDir]. */
        fun legacyDir(): File =
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Griffonote")
    }
}
