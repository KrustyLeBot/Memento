package app.scriptorium.map

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Offline packs in `filesDir/packs/<id>/` (private storage the system does not purge mid-hike, never the
 * synced Documents folder): `manifest.json`, `ortho/` and `dem/` tiles, `peaks.json`, `route.json`.
 * Downloads run in a process-wide scope, so they go on while the recording service keeps the process
 * alive with no screen. A pack whose download stopped stays incomplete and can be resumed; every pack is
 * deleted 24 h after it was started.
 */
object OfflinePacks {
    private const val TAG = "OfflinePacks"
    private const val WORKERS = 4
    private const val MAX_FAILURES_IN_A_ROW = 15
    private const val SPARE_BYTES = 50L * 1024 * 1024

    class PackException(message: String) : Exception(message)

    /** A download in progress, or the reason the last one stopped. */
    data class Progress(val done: Long, val total: Long, val bytes: Long, val error: String? = null) {
        val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }

    private val _packs = MutableStateFlow<List<Pack>>(emptyList())
    val packs: StateFlow<List<Pack>> = _packs
    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress
    private val _running = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of the packs downloading now; the download service runs while this is not empty. */
    val running: StateFlow<Set<String>> = _running

    /** Called after a download starts, to bring up the foreground service that keeps the process alive. */
    @Volatile
    var onDownloadStarted: (() -> Unit)? = null

    private const val PURGE_EVERY_MS = 10L * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    /** Packs a recording is using: kept past their 24 h until it ends, not pulled from under the hiker. */
    private val pinned = ConcurrentHashMap.newKeySet<String>()
    private lateinit var dir: File

    @Synchronized
    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.applicationContext.filesDir, "packs")
        // the process can live for days (a recording keeps it up): expiry is not only checked at startup
        scope.launch {
            while (true) {
                refresh()
                delay(PURGE_EVERY_MS)
            }
        }
    }

    /** Uses [packs] folder directly, without a Context (JVM tests). */
    @Synchronized
    internal fun initDir(packs: File) {
        dir = packs
    }

    fun pin(id: String) { pinned.add(id) }

    fun unpin(id: String) { pinned.remove(id) }

    fun pinnedIds(): Set<String> = pinned.toSet()

    /** Reloads the manifests and deletes expired packs and leftovers of a pack that never got a manifest. */
    @Synchronized
    fun refresh(now: Long = System.currentTimeMillis()) {
        if (!::dir.isInitialized) return
        val out = ArrayList<Pack>()
        for (d in dir.listFiles().orEmpty()) {
            if (!d.isDirectory) continue
            val m = File(d, "manifest.json")
            val pack = if (m.isFile) {
                try { Pack.parse(m.readText()) } catch (e: Exception) { Log.w(TAG, "${d.name}: $e"); null }
            } else null
            if (pack == null || (pack.expired(now) && pack.id !in pinned)) {
                if (jobs[d.name]?.isActive != true || pack != null) {
                    jobs.remove(d.name)?.cancel()
                    d.deleteRecursively()
                    _progress.update { it - d.name }
                }
                continue
            }
            out.add(pack)
        }
        _packs.value = out.sortedByDescending { it.created }
    }

    fun isRunning(id: String) = jobs[id]?.isActive == true

    fun findTile(rel: String): File? {
        if (!::dir.isInitialized) return null
        for (p in _packs.value) {
            if (p.obsolete) continue
            val f = File(dir, "${p.id}/$rel")
            if (f.isFile && f.length() > 0) return f
        }
        return null
    }

    fun peaksFiles(): Sequence<File> =
        if (!::dir.isInitialized) emptySequence() else _packs.value.asSequence().map { File(dir, "${it.id}/peaks.json") }.filter { it.isFile }

    fun route(id: String): Route = RouteFile.fromJson(File(dir, "$id/route.json").readText())

    /** Starts downloading what [route] needs offline: the aerial photo, relief and peaks of its square. Returns the pack id. */
    fun prepareRoute(route: Route): String {
        val box = route.bbox ?: throw PackException("Parcours vide")
        val square = MapSquare.of(box)
        val plan = TilePlans.forRoute(square) ?: throw PackException("Parcours trop étendu pour une carte hors ligne")
        val pack = Pack(
            newId("r"), route.name.ifBlank { "Parcours" }, System.currentTimeMillis(), square, plan.orthoZooms.last,
            setOf(Pack.KIND_ORTHO, Pack.KIND_DEM, Pack.KIND_PEAKS), 0, complete = false, hasRoute = true,
        )
        create(pack, plan.estimatedBytes) { TileCache.writeAtomic(File(it, "route.json"), RouteFile.toJson(route).toByteArray()) }
        return pack.id
    }

    /**
     * The 2D pack of a recording without route: the aerial photo of a 20 km square around the start. Skipped when
     * offline or when a pack already covers the place (a second recording from the same spot). Runs in
     * the background: the caller is the GPS callback.
     */
    fun prepareZone(lon: Double, lat: Double) {
        if (!::dir.isInitialized || !NetState.online.value) return
        scope.launch {
            val now = System.currentTimeMillis()
            val near = MapSquare.centered(lon, lat, TilePlans.ZONE_HALF_M / 2)
            if (_packs.value.any { !it.expired(now) && !it.obsolete && it.square.covers(near) }) return@launch
            val plan = TilePlans.forZone(lon, lat)
            val pack = Pack(newId("z"), "Zone autour du départ (2D)", now, plan.square, plan.orthoZooms.last, setOf(Pack.KIND_ORTHO), 0, complete = false, hasRoute = false)
            try {
                create(pack, plan.estimatedBytes) {}
            } catch (e: PackException) {
                Log.w(TAG, "zone: ${e.message}")
            }
        }
    }

    /** Resumes an interrupted pack: tiles already there are kept. */
    fun resume(id: String) {
        val pack = _packs.value.firstOrNull { it.id == id } ?: return
        if (pack.complete || isRunning(id)) return
        if (pack.obsolete) throw PackException("Carte d'un ancien format : à préparer de nouveau")
        if (!NetState.online.value) throw PackException("Pas de connexion")
        start(pack)
    }

    fun delete(id: String) {
        scope.launch {
            jobs.remove(id)?.cancelAndJoin()
            File(dir, id).deleteRecursively()
            _progress.update { it - id }
            refresh()
        }
    }

    private fun newId(prefix: String) = "$prefix-${System.currentTimeMillis()}-${(1000..9999).random()}"

    // synchronized with refresh(): until its manifest is written, the new folder looks like a leftover
    @Synchronized
    private fun create(pack: Pack, estimate: Long, extra: (File) -> Unit) {
        if (!::dir.isInitialized) throw PackException("Cartes hors ligne indisponibles")
        if (!NetState.online.value) throw PackException("Pas de connexion : impossible de télécharger la carte")
        dir.mkdirs()
        if (dir.usableSpace < estimate + SPARE_BYTES) {
            throw PackException("Espace insuffisant : environ ${(estimate + SPARE_BYTES) / 1_000_000} Mo nécessaires")
        }
        val d = File(dir, pack.id)
        d.mkdirs()
        extra(d)
        writeManifest(pack)
        refresh()
        start(pack)
    }

    private fun writeManifest(p: Pack) = TileCache.writeAtomic(File(dir, "${p.id}/manifest.json"), p.toJson().toByteArray())

    @Synchronized
    private fun start(pack: Pack) {
        if (isRunning(pack.id)) return
        _progress.update { it + (pack.id to Progress(0, pack.plan().tileCount, pack.bytes)) }
        _running.update { it + pack.id }
        jobs[pack.id] = scope.launch {
            try {
                val done = download(pack)
                writeManifest(done)
                _progress.update { it - pack.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${pack.id}: $e")
                val msg = when {
                    dir.usableSpace < SPARE_BYTES / 5 -> "Espace de stockage plein"
                    e is PackException -> e.message
                    else -> "Téléchargement interrompu : ${e.message}"
                }
                _progress.update { m -> m + (pack.id to (m[pack.id] ?: Progress(0, 0, 0)).copy(error = msg)) }
            } finally {
                jobs.remove(pack.id)
                _running.update { it - pack.id }
                refresh()
            }
        }
        onDownloadStarted?.invoke()
    }

    /** Stops every download (the notification's "Annuler"); the packs stay incomplete and can be resumed. */
    fun cancelAll() {
        for ((id, job) in jobs) {
            job.cancel()
            _progress.update { m -> m + (id to (m[id] ?: Progress(0, 0, 0)).copy(error = "Téléchargement annulé")) }
        }
    }

    /**
     * Relief first (the 3D view needs it), then the aerial photo zoom by zoom. Should the real tiles outgrow the
     * budget, the zoom being fetched is dropped and the pack stops at the one below: the 500 MB holds
     * even when the estimate was low.
     */
    private suspend fun download(pack: Pack): Pack {
        val pdir = File(dir, pack.id)
        pdir.walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.forEach { it.delete() }
        val plan = pack.plan()
        val groups = plan.demRanges.map { TileKey.KIND_DEM to it } + plan.orthoRanges.map { TileKey.KIND_ORTHO to it }
        val total = plan.tileCount
        val done = AtomicLong(0)
        val bytes = AtomicLong(pdir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        val budget = if (pack.hasRoute) TilePlans.ROUTE_BUDGET else Long.MAX_VALUE
        var zmax = pack.zmax
        var missed = 0
        for ((kind, range) in groups) {
            val missedHere = AtomicInteger(0)
            val queue = ConcurrentLinkedQueue<TileKey>()
            range.forEach { x, y -> queue.add(TileKey(kind, range.z, x, y)) }
            val over = AtomicBoolean(false)
            val failures = AtomicInteger(0)
            coroutineScope {
                repeat(WORKERS) {
                    launch {
                        while (!over.get()) {
                            val key = queue.poll() ?: break
                            if (!fetchOne(pdir, key, bytes, failures)) missedHere.incrementAndGet()
                            if (bytes.get() > budget) over.set(true)
                            val n = done.incrementAndGet()
                            if (n % 25 == 0L) _progress.update { it + (pack.id to Progress(n, total, bytes.get())) }
                        }
                    }
                }
            }
            if (over.get()) {
                if (kind != TileKey.KIND_ORTHO || range.z <= TilePlans.ORTHO_MIN_Z) throw PackException("Parcours trop étendu pour une carte hors ligne")
                File(pdir, "${TileKey.KIND_ORTHO}/${range.z}").deleteRecursively()
                zmax = range.z - 1
                // said at once: a resume after a later failure must not fetch this zoom again, only to drop it again
                writeManifest(pack.copy(zmax = zmax, bytes = bytes.get()))
                break
            }
            missed += missedHere.get()
            writeManifest(pack.copy(zmax = zmax, bytes = bytes.get()))
        }
        if (missed > 0) throw PackException("$missed tuiles manquantes : reprendre le téléchargement")
        bytes.set(pdir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        var kinds = pack.kinds
        if (Pack.KIND_PEAKS in kinds) {
            try {
                val peaks = TileServer.computePeaks(pack.square)
                TileCache.writeAtomic(File(pdir, "peaks.json"), Peaks.cacheJson(pack.square, peaks).toByteArray())
            } catch (e: IOException) {
                // the map stays usable without labels; the pack says it has none
                Log.w(TAG, "${pack.id} peaks: $e")
                kinds = kinds - Pack.KIND_PEAKS
            }
        }
        return pack.copy(zmax = zmax, kinds = kinds, bytes = bytes.get(), complete = true)
    }

    /** False when the tile could not be fetched this time (a transient error). */
    private fun fetchOne(pdir: File, key: TileKey, bytes: AtomicLong, failures: AtomicInteger): Boolean {
        val f = File(pdir, key.path)
        if (f.isFile && f.length() > 0) return true
        if (!NetState.online.value) throw PackException("Connexion perdue : téléchargement interrompu")
        val body = try {
            TileServer.fetch(key, allowOsm = false)?.bytes.also { failures.set(0) }
        } catch (e: IOException) {
            if (failures.incrementAndGet() > MAX_FAILURES_IN_A_ROW) throw PackException("Trop d'erreurs réseau : ${e.message}")
            return false
        } ?: return true // no tile exists there (no IGN photo outside France)
        TileCache.writeAtomic(f, body)
        bytes.addAndGet(body.size.toLong())
        return true
    }
}
