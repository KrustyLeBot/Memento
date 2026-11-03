package app.memento.activity

/**
 * The points of a GPX file in one linear pass of `indexOf`, without regular expressions: Android's regex engine
 * (ICU) took minutes over a 15 MB file of 200 000 `<trkpt>` (a Strava export of a 60-hour race). A point's inner
 * text ends at its own closing tag and never runs past the next opening one, as in the Windows reader.
 */
object GpxScan {
    /** Calls [onPoint] with (start offset, attributes, inner text) of each `<tag ...>` element, in file order. */
    inline fun elements(text: String, tag: String, onPoint: (Int, String, String) -> Unit) {
        val open = "<$tag"
        val close = "</$tag>"
        val starts = openings(text, open)
        for (i in starts.indices) {
            val start = starts[i]
            val limit = if (i + 1 < starts.size) starts[i + 1] else text.length
            val gt = text.indexOf('>', start + open.length)
            if (gt < 0 || gt >= limit) continue
            if (text[gt - 1] == '/') {
                onPoint(start, text.substring(start + open.length, gt - 1), "")
                continue
            }
            val end = text.indexOf(close, gt + 1)
            if (end < 0 || end > limit) continue
            onPoint(start, text.substring(start + open.length, gt), text.substring(gt + 1, end))
        }
    }

    /** Offsets of `<tag` followed by a space, `>` or `/` (so `<trkpt` never matches `<trkptx`). */
    fun openings(text: String, open: String): IntArray {
        var out = IntArray(1024)
        var n = 0
        var from = 0
        while (true) {
            val i = text.indexOf(open, from)
            if (i < 0) break
            from = i + open.length
            val next = if (from < text.length) text[from] else '>'
            if (next != '>' && next != '/' && !next.isWhitespace()) continue
            if (n == out.size) out = out.copyOf(n * 2)
            out[n++] = i
        }
        return out.copyOf(n)
    }

    /** The value of attribute [name] (`lat="…"` or `lat='…'`, spaces around `=` allowed), null when absent. */
    fun attr(attrs: String, name: String): String? {
        var from = 0
        while (true) {
            val i = attrs.indexOf(name, from)
            if (i < 0) return null
            from = i + name.length
            if (i > 0 && !attrs[i - 1].isWhitespace()) continue
            var j = from
            while (j < attrs.length && attrs[j].isWhitespace()) j++
            if (j >= attrs.length || attrs[j] != '=') continue
            j++
            while (j < attrs.length && attrs[j].isWhitespace()) j++
            if (j >= attrs.length || (attrs[j] != '"' && attrs[j] != '\'')) continue
            val end = attrs.indexOf(attrs[j], j + 1)
            return if (end < 0) null else attrs.substring(j + 1, end)
        }
    }

    /** The trimmed text of the first `<tag>…</tag>` in [inner], null when absent or empty. */
    fun child(inner: String, tag: String): String? {
        val open = inner.indexOf("<$tag>")
        if (open < 0) return null
        val from = open + tag.length + 2
        val end = inner.indexOf("</$tag>", from)
        if (end < 0) return null
        return inner.substring(from, end).trim().ifEmpty { null }
    }

    /** `<hr>` or any namespaced `<x:hr>` (Garmin's gpxtpx, others). */
    fun heartRate(inner: String): Int? {
        var from = 0
        while (true) {
            val i = inner.indexOf("hr>", from)
            if (i < 0) return null
            from = i + 3
            if (i == 0) continue
            val before = inner[i - 1]
            val opening = before == '<' || (before == ':' && inner.lastIndexOf('<', i) > inner.lastIndexOf('/', i))
            if (!opening) continue
            val end = inner.indexOf('<', from)
            if (end < 0) return null
            return inner.substring(from, end).trim().toIntOrNull()
        }
    }

    /** The `<name>` of the first `<tag>` element (CDATA unwrapped), null when absent or empty. */
    fun name(text: String, tag: String): String? {
        val start = openings(text, "<$tag").firstOrNull() ?: return null
        val open = text.indexOf("<name>", start)
        if (open < 0) return null
        val end = text.indexOf("</name>", open + 6)
        if (end < 0) return null
        var name = text.substring(open + 6, end).trim()
        if (name.startsWith("<![CDATA[")) name = name.removePrefix("<![CDATA[").removeSuffix("]]>")
        return name.trim().ifEmpty { null }
    }
}
