package app.memento.map

import android.content.Context
import app.memento.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Process-wide start of the map machinery, from the activity or from the recording service, whichever comes first. */
object MapRuntime {
    @Volatile private var started = false

    @Synchronized
    fun init(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        // before anything fetches or plans: the tile source and the photo's top zoom chosen in the settings
        Settings(app).let { MapConfig.set(it.tuileries, it.photoMaxZoom) }
        NetState.init(app)
        TileServer.init(app)
        OfflinePacks.onDownloadStarted = { PackDownloadService.start(app) }
        OfflinePacks.init(app)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // the hand-drawn OSM map's cache, replaced by the browsing cache
            File(app.cacheDir, "tiles").deleteRecursively()
            // the peaks answers' former folder, now inside the browsing cache
            File(app.cacheDir, "peaks").deleteRecursively()
            // tiles of a former base layer (Plan IGN), never read again; once gone, later startups find nothing
            for (kind in TileKey.OLD_KINDS) File(File(app.cacheDir, "maptiles"), kind).deleteRecursively()
            // queued with the trims the first tiles ask for, so the folder is walked once, not once each
            TileServer.cache.trimLater()
        }
    }
}
