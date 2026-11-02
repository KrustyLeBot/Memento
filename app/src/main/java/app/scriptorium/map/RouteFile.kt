package app.scriptorium.map

import app.scriptorium.activity.Fit
import app.scriptorium.activity.Track

/**
 * Reads a route to follow from a GPX or FIT file. Unlike an activity, a planned route has no times:
 * GPX `<trkpt>` and `<rtept>` are taken as they come, with their `<ele>` when present.
 */
object RouteFile {
    private val PT = Regex("""<(trkpt|rtept)\b([^>]*?)(/>|>(.*?)</\1>)""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val ATTR_LAT = Regex("""\blat\s*=\s*["']([^"']+)["']""")
    private val ATTR_LON = Regex("""\blon\s*=\s*["']([^"']+)["']""")
    private val ELE = Regex("""<ele>\s*([^<\s]+)\s*</ele>""")
    private val NAME = Regex("""<(?:trk|rte|metadata)\b[^>]*>\s*<name>\s*(?:<!\[CDATA\[)?(.*?)(?:]]>)?\s*</name>""", setOf(RegexOption.DOT_MATCHES_ALL))

    fun read(bytes: ByteArray, fallbackName: String): Route {
        val route = if (Fit.looksLikeFit(bytes)) {
            fromTrack(Fit.read(bytes), fallbackName)
        } else {
            val text = String(bytes, Charsets.UTF_8)
            if (!text.contains("<gpx", ignoreCase = true)) throw Fit.FitException("Format non reconnu : il faut un fichier GPX ou FIT")
            readGpx(text, fallbackName)
        }
        if (route.size < 2) throw Fit.FitException("Aucun tracé dans ce fichier")
        return route
    }

    fun readGpx(text: String, fallbackName: String): Route {
        val trk = ArrayList<Triple<Double, Double, Double?>>()
        val rte = ArrayList<Triple<Double, Double, Double?>>()
        for (m in PT.findAll(text)) {
            val attrs = m.groupValues[2]
            // "NaN" parses as a number: one such point would make the route's box, and its pack, NaN
            val lat = ATTR_LAT.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: continue
            val lon = ATTR_LON.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: continue
            val ele = ELE.find(m.groupValues[4])?.groupValues?.get(1)?.toDoubleOrNull()
            (if (m.groupValues[1] == "trkpt") trk else rte).add(Triple(lat, lon, ele))
        }
        val name = NAME.find(text)?.groupValues?.get(1)?.let { unescape(it) }?.trim()?.ifEmpty { null } ?: fallbackName
        // a file with both usually carries the detailed track and a few route waypoints
        return Route.of(name, if (trk.size >= rte.size) trk else rte)
    }

    /** The XML entities a name can carry ("Tour &amp; crêtes"); `&amp;` last, so "&amp;lt;" stays "&lt;". */
    private fun unescape(s: String) =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    fun fromTrack(track: Track, fallbackName: String): Route =
        Route.of(track.name ?: fallbackName, track.points.map { Triple(it.lat, it.lon, it.alt) })

    /** `route.json` of a pack: name and `[lon, lat, ele|null]` points. */
    fun toJson(r: Route): String = buildString {
        append("{\"name\":").append(Json.quote(r.name)).append(",\"points\":[")
        for (i in 0 until r.size) {
            if (i > 0) append(',')
            append('[').append(Json.num(r.lons[i])).append(',').append(Json.num(r.lats[i])).append(',')
            append(r.eles?.let { Json.num(it[i], 1) } ?: "null").append(']')
        }
        append("]}")
    }

    fun fromJson(text: String): Route {
        val o = Json.parse(text).asMap() ?: throw Json.JsonException("Parcours invalide")
        val pts = o["points"].asList().orEmpty().mapNotNull { p ->
            val a = p.asList() ?: return@mapNotNull null
            val lon = a.getOrNull(0).asDouble() ?: return@mapNotNull null
            val lat = a.getOrNull(1).asDouble() ?: return@mapNotNull null
            Triple(lat, lon, a.getOrNull(2).asDouble())
        }
        return Route.of(o["name"] as? String ?: "", pts)
    }
}
