package app.memento.map

import app.memento.activity.Geo
import java.net.URLEncoder

/** A named summit shown on the 3D map. [importance] is IGN's 1 (major) to 6 (minor); 9 when unknown. */
data class Peak(val name: String, val ele: Double, val lon: Double, val lat: Double, val importance: Int)

/**
 * The peaks the map labels: IGN BD TOPO summits in the square, their altitude from the IGN altimetry
 * service, then at most [MAX] well spread ones, the most important first.
 */
object Peaks {
    const val MAX = 12
    const val WFS_COUNT = 300
    const val PRESELECT = 60

    fun wfsUrl(b: Bbox): String {
        val filter = "nature='Sommet' AND BBOX(geometrie,${b.w},${b.s},${b.e},${b.n},'CRS:84')"
        return "https://data.geopf.fr/wfs/ows?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature" +
            "&TYPENAMES=BDTOPO_V3:detail_orographique&OUTPUTFORMAT=application/json&COUNT=$WFS_COUNT" +
            "&CQL_FILTER=" + URLEncoder.encode(filter, "UTF-8").replace("+", "%20")
    }

    /** Named summits of a WFS GeoJSON answer, altitude still unknown (NaN). */
    fun parseWfs(text: String): List<Peak> {
        val features = Json.parse(text).asMap()?.get("features").asList() ?: throw Json.JsonException("Réponse WFS inattendue")
        return features.mapNotNull { f ->
            val o = f.asMap() ?: return@mapNotNull null
            val props = o["properties"].asMap() ?: return@mapNotNull null
            val name = (props["toponyme"] as? String)?.trim()?.ifEmpty { null } ?: return@mapNotNull null
            val c = o["geometry"].asMap()?.get("coordinates").asList() ?: return@mapNotNull null
            val lon = c.getOrNull(0).asDouble() ?: return@mapNotNull null
            val lat = c.getOrNull(1).asDouble() ?: return@mapNotNull null
            Peak(name, Double.NaN, lon, lat, props["importance"].asDouble()?.toInt() ?: 9)
        }
    }

    /** The [PRESELECT] most important candidates, whose altitude is then asked for. */
    fun preselect(candidates: List<Peak>): List<Peak> = candidates.sortedBy { it.importance }.take(PRESELECT)

    fun altiUrl(peaks: List<Peak>): String =
        "https://data.geopf.fr/altimetrie/1.0/calcul/alti/rest/elevation.json?resource=ign_rge_alti_wld&zonly=true" +
            "&lon=" + peaks.joinToString("|") { Json.num(it.lon) } + "&lat=" + peaks.joinToString("|") { Json.num(it.lat) }

    /** Altitudes in request order; the service's no-data value (-99999) becomes NaN. */
    fun parseAlti(text: String): List<Double> {
        val list = Json.parse(text).asMap()?.get("elevations").asList() ?: throw Json.JsonException("Réponse d'altitude inattendue")
        return list.map { v -> (v.asDouble() ?: v.asMap()?.get("z").asDouble())?.takeIf { it > -1000 } ?: Double.NaN }
    }

    /**
     * Sorted by importance then altitude, each kept only if it stands at least a seventh of the square's
     * side from those already kept, so the labels do not pile up on one ridge.
     */
    fun select(peaks: List<Peak>, square: Bbox, max: Int = MAX): List<Peak> {
        val spacing = maxOf(square.widthM, square.heightM) / 7
        val out = ArrayList<Peak>()
        for (p in peaks.filter { !it.ele.isNaN() }.sortedWith(compareBy<Peak> { it.importance }.thenByDescending { it.ele })) {
            if (out.size >= max) break
            if (out.all { Geo.distance(it.lat, it.lon, p.lat, p.lon) >= spacing }) out.add(p)
        }
        return out
    }

    fun toJson(peaks: List<Peak>): String = peaks.joinToString(",", "[", "]") {
        "{\"name\":${Json.quote(it.name)},\"ele\":${Json.num(it.ele, 0)},\"lon\":${Json.num(it.lon)},\"lat\":${Json.num(it.lat)},\"importance\":${it.importance}}"
    }

    fun fromJson(text: String): List<Peak> = Json.parse(text).asList().orEmpty().mapNotNull { peakOf(it) }

    private fun peakOf(v: Any?): Peak? {
        val o = v.asMap() ?: return null
        return Peak(
            o["name"] as? String ?: return null,
            o["ele"].asDouble() ?: return null,
            o["lon"].asDouble() ?: return null,
            o["lat"].asDouble() ?: return null,
            o["importance"].asDouble()?.toInt() ?: 9,
        )
    }

    /** A cached answer stores the bbox it was computed for. */
    fun cacheJson(b: Bbox, peaks: List<Peak>) =
        "{\"bbox\":[${b.toList().joinToString(",") { Json.num(it, 7) }}],\"peaks\":${toJson(peaks)}}"

    fun parseCache(text: String): Pair<Bbox, List<Peak>>? {
        val o = Json.parse(text).asMap() ?: return null
        val b = Bbox.fromList(o["bbox"].asList()?.mapNotNull { it.asDouble() }) ?: return null
        val peaks = o["peaks"].asList().orEmpty().mapNotNull { peakOf(it) }
        return b to peaks
    }

    /** Offline answer: the peaks of the first cached result whose bbox covers [req], kept inside it; null if none. */
    fun fromCovering(cached: Sequence<Pair<Bbox, List<Peak>>>, req: Bbox): List<Peak>? =
        cached.firstOrNull { it.first.covers(req) }?.second?.filter { req.contains(it.lon, it.lat) }

    /** File-name key of a bbox, stable to ~1 cm. */
    fun key(b: Bbox): String = b.toList().joinToString("_") { Json.num(it, 7) }
}
