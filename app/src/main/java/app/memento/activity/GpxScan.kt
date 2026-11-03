package app.memento.activity

/**
 * The points of a GPX file in one linear pass of `indexOf`, without regular expressions: Android's regex engine
 * (ICU) took minutes over a 15 MB file of 200 000 `<trkpt>` (a Strava export of a 60-hour race). A point's inner
 * text ends at its own closing tag and never runs past the next opening one, as in the Windows reader.
 *
 * Elements are handed over as ranges of the file's text, not as substrings: copying each point's attributes and
 * inner text was most of the garbage of a 15 MB import, which a weak phone pays for in collections.
 */
object GpxScan {
    /**
     * Calls [onPoint] with (start offset, attributes from, attributes to, inner from, inner to) of each `<tag ...>`
     * element, in file order: the attributes and the inner text are [text] between those offsets.
     */
    inline fun ranges(text: String, tag: String, onPoint: (Int, Int, Int, Int, Int) -> Unit) {
        val open = "<$tag"
        val close = "</$tag>"
        val starts = openings(text, open)
        for (i in starts.indices) {
            val start = starts[i]
            val limit = if (i + 1 < starts.size) starts[i + 1] else text.length
            val gt = text.indexOf('>', start + open.length)
            if (gt < 0 || gt >= limit) continue
            if (text[gt - 1] == '/') {
                onPoint(start, start + open.length, gt - 1, gt, gt)
                continue
            }
            val end = text.indexOf(close, gt + 1)
            if (end < 0 || end > limit) continue
            onPoint(start, start + open.length, gt, gt + 1, end)
        }
    }

    /** Calls [onPoint] with (start offset, attributes, inner text) of each `<tag ...>` element, in file order. */
    inline fun elements(text: String, tag: String, onPoint: (Int, String, String) -> Unit) {
        ranges(text, tag) { start, af, at, inf, int -> onPoint(start, text.substring(af, at), text.substring(inf, int)) }
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

    /** First [needle] starting in [from]..[to] and ending by [to], -1 when none: a search that never leaves its element. */
    fun find(text: String, needle: String, from: Int, to: Int): Int {
        if (needle.isEmpty()) return if (from <= to) from else -1
        val first = needle[0]
        val last = to - needle.length
        var i = from
        while (i <= last) {
            if (text[i] == first && text.regionMatches(i, needle, 0, needle.length)) return i
            i++
        }
        return -1
    }

    private fun find(text: String, c: Char, from: Int, to: Int): Int {
        for (i in from until to) if (text[i] == c) return i
        return -1
    }

    /** Last [c] in [from]..[at] (inclusive), -1 when none. */
    private fun findLast(text: String, c: Char, from: Int, at: Int): Int {
        var i = minOf(at, text.length - 1)
        while (i >= from) { if (text[i] == c) return i; i-- }
        return -1
    }

    /** The value of attribute [name] (`lat="…"` or `lat='…'`, spaces around `=` allowed), null when absent. */
    fun attr(attrs: String, name: String): String? = attr(attrs, 0, attrs.length, name)

    /** [attr] within [from]..[to] of [text]. */
    fun attr(text: String, from: Int, to: Int, name: String): String? {
        var at = from
        while (true) {
            val i = find(text, name, at, to)
            if (i < 0) return null
            at = i + name.length
            if (i > from && !text[i - 1].isWhitespace()) continue
            var j = at
            while (j < to && text[j].isWhitespace()) j++
            if (j >= to || text[j] != '=') continue
            j++
            while (j < to && text[j].isWhitespace()) j++
            if (j >= to || (text[j] != '"' && text[j] != '\'')) continue
            val end = find(text, text[j], j + 1, to)
            return if (end < 0) null else text.substring(j + 1, end)
        }
    }

    /** The trimmed text of the first `<tag>…</tag>` in [inner], null when absent or empty. */
    fun child(inner: String, tag: String): String? = child(inner, 0, inner.length, tag)

    /** [child] within [from]..[to] of [text]. */
    fun child(text: String, from: Int, to: Int, tag: String): String? {
        val open = find(text, "<$tag>", from, to)
        if (open < 0) return null
        val start = open + tag.length + 2
        val end = find(text, "</$tag>", start, to)
        if (end < 0) return null
        return text.substring(start, end).trim().ifEmpty { null }
    }

    /** `<hr>` or any namespaced `<x:hr>` (Garmin's gpxtpx, others). */
    fun heartRate(inner: String): Int? = heartRate(inner, 0, inner.length)

    /** [heartRate] within [from]..[to] of [text]. */
    fun heartRate(text: String, from: Int, to: Int): Int? {
        var at = from
        while (true) {
            val i = find(text, "hr>", at, to)
            if (i < 0) return null
            at = i + 3
            if (i == from) continue
            val before = text[i - 1]
            val opening = before == '<' || (before == ':' && findLast(text, '<', from, i) > findLast(text, '/', from, i))
            if (!opening) continue
            val end = find(text, '<', at, to)
            if (end < 0) return null
            return text.substring(at, end).trim().toIntOrNull()
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
