package app.scriptorium.map

import android.content.Context
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
        NetState.init(app)
        TileServer.init(app)
        OfflinePacks.init(app)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // the hand-drawn OSM map's cache, replaced by the browsing cache
            File(app.cacheDir, "tiles").deleteRecursively()
            TileServer.cache.trim()
        }
    }
}
