package app.scriptorium

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.sp

val URL_REGEX = Regex("""https?://[^\s<>"]+""")

fun findUrls(text: String): List<String> =
    URL_REGEX.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '!', '?', ')') }.distinct().toList()

/**
 * Hides Markdown markers (`**`, `*`, `#`) and styles the text accordingly, so the user
 * never sees Markdown while the stored text stays plain Markdown.
 */
class MarkdownTransformation(private val linkColor: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        val n = s.length
        val out = StringBuilder()
        val o2t = IntArray(n + 1)
        val t2o = ArrayList<Int>(n + 1)
        val spans = ArrayList<Triple<SpanStyle, Int, Int>>()

        fun keep(i: Int) {
            o2t[i] = out.length
            t2o.add(i)
            out.append(s[i])
        }

        fun hide(i: Int) {
            o2t[i] = out.length
        }

        var lineStart = 0
        while (lineStart <= n) {
            var lineEnd = s.indexOf('\n', lineStart)
            if (lineEnd < 0) lineEnd = n
            var i = lineStart
            var headingSize: Int? = null

            // Line prefixes.
            var level = 0
            while (level < 3 && i + level < lineEnd && s[i + level] == '#') level++
            if (level > 0 && i + level < lineEnd && s[i + level] == ' ') {
                for (k in 0..level) hide(i + k)
                i += level + 1
                headingSize = when (level) { 1 -> 24; 2 -> 20; else -> 18 }
            } else if (i + 1 < lineEnd && (s[i] == '-' || s[i] == '*') && s[i + 1] == ' ') {
                o2t[i] = out.length; t2o.add(i); out.append('•')
                keep(i + 1)
                i += 2
            }
            val contentStart = out.length

            while (i < lineEnd) {
                if (s.startsWith("**", i)) {
                    val close = s.indexOf("**", i + 3)
                    if (close >= 0 && close + 2 <= lineEnd) {
                        hide(i); hide(i + 1)
                        val start = out.length
                        for (k in i + 2 until close) keep(k)
                        spans.add(Triple(SpanStyle(fontWeight = FontWeight.Bold), start, out.length))
                        hide(close); hide(close + 1)
                        i = close + 2
                        continue
                    }
                } else if (s[i] == '*' && i + 1 < lineEnd && s[i + 1] != '*' && s[i + 1] != ' ') {
                    val close = s.indexOf('*', i + 1)
                    if (close in (i + 2) until lineEnd && s[close - 1] != ' ' && (close + 1 >= n || s[close + 1] != '*')) {
                        hide(i)
                        val start = out.length
                        for (k in i + 1 until close) keep(k)
                        spans.add(Triple(SpanStyle(fontStyle = FontStyle.Italic), start, out.length))
                        hide(close)
                        i = close + 1
                        continue
                    }
                }
                keep(i)
                i++
            }
            if (headingSize != null && out.length > contentStart) {
                spans.add(Triple(SpanStyle(fontSize = headingSize.sp, fontWeight = FontWeight.Bold), contentStart, out.length))
            }
            if (lineEnd < n) {
                keep(lineEnd) // the newline
                lineStart = lineEnd + 1
            } else {
                break
            }
        }
        o2t[n] = out.length
        t2o.add(n)

        val transformed = out.toString()
        val builder = AnnotatedString.Builder(transformed)
        spans.forEach { (style, a, b) -> builder.addStyle(style, a, b) }
        URL_REGEX.findAll(transformed).forEach {
            builder.addStyle(
                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                it.range.first, it.range.last + 1,
            )
        }

        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = o2t[offset.coerceIn(0, n)]
            override fun transformedToOriginal(offset: Int) = t2o[offset.coerceIn(0, t2o.size - 1)]
        }
        return TransformedText(builder.toAnnotatedString(), mapping)
    }
}

/** Toolbar actions. They work on the raw Markdown held by the text field. */
object MarkdownEdit {
    fun toggleWrap(tv: TextFieldValue, marker: String): TextFieldValue {
        val text = tv.text
        var start = tv.selection.min
        var end = tv.selection.max
        if (start == end) { // no selection: act on the word under the cursor
            while (start > 0 && !text[start - 1].isWhitespace()) start--
            while (end < text.length && !text[end].isWhitespace()) end++
            if (start == end) return tv
        }
        val m = marker.length
        val wrapped = start >= m && end + m <= text.length &&
            text.substring(start - m, start) == marker && text.substring(end, end + m) == marker &&
            (marker.length == 2 || !(start >= 2 && text[start - 2] == '*') )
        return if (wrapped) {
            val newText = text.removeRange(end, end + m).removeRange(start - m, start)
            tv.copy(text = newText, selection = TextRange(start - m, end - m))
        } else {
            val newText = text.substring(0, start) + marker + text.substring(start, end) + marker + text.substring(end)
            tv.copy(text = newText, selection = TextRange(start + m, end + m))
        }
    }

    /** Cycles the line's heading level: none -> `# ` -> `## ` -> none. */
    fun cycleHeading(tv: TextFieldValue): TextFieldValue {
        val (ls, le) = lineBounds(tv.text, tv.selection.min)
        val line = tv.text.substring(ls, le)
        val (newLine, delta) = when {
            line.startsWith("## ") -> line.removePrefix("## ") to -3
            line.startsWith("# ") -> "## " + line.removePrefix("# ") to 1
            else -> "# $line" to 2
        }
        return replaceLine(tv, ls, le, newLine, delta)
    }

    fun toggleBullet(tv: TextFieldValue): TextFieldValue {
        val (ls, le) = lineBounds(tv.text, tv.selection.min)
        val line = tv.text.substring(ls, le)
        val (newLine, delta) = if (line.startsWith("- ")) line.removePrefix("- ") to -2 else "- $line" to 2
        return replaceLine(tv, ls, le, newLine, delta)
    }

    private fun lineBounds(text: String, pos: Int): Pair<Int, Int> {
        val p = pos.coerceIn(0, text.length)
        val s = text.lastIndexOf('\n', p - 1).let { if (p == 0) 0 else it + 1 }
        val e = text.indexOf('\n', p).let { if (it < 0) text.length else it }
        return s to e
    }

    private fun replaceLine(tv: TextFieldValue, ls: Int, le: Int, newLine: String, delta: Int): TextFieldValue {
        val newText = tv.text.substring(0, ls) + newLine + tv.text.substring(le)
        val sel = TextRange(
            (tv.selection.start + delta).coerceIn(0, newText.length),
            (tv.selection.end + delta).coerceIn(0, newText.length),
        )
        return tv.copy(text = newText, selection = sel)
    }
}
