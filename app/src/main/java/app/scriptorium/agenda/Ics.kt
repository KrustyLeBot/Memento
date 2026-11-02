package app.scriptorium.agenda

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** One property line: NAME;PARAM=…:value. */
data class ContentLine(
    /** Upper case, without a group prefix ("item1."). */
    val name: String,
    /** Upper-case names; values unquoted. */
    val params: Map<String, List<String>>,
    /** Raw value (still escaped). */
    val value: String,
    /** The whole unfolded line, as read. */
    val raw: String,
) {
    fun param(key: String): String? = params[key]?.firstOrNull()
}

data class Component(
    val name: String,
    val lines: MutableList<ContentLine> = ArrayList(),
    val children: MutableList<Component> = ArrayList(),
    val lineNumber: Int = 0,
) {
    fun first(name: String): ContentLine? = lines.firstOrNull { it.name == name }
    fun all(name: String): List<ContentLine> = lines.filter { it.name == name }
}

class IcsSyntaxException(message: String) : IllegalArgumentException(message)

/**
 * iCalendar (RFC 5545) content lines, shared by the import and the export; same code as Python's
 * `core/agenda/ics.py`. A small reader on purpose: unknown properties are kept *verbatim* (`ical_extra`), which
 * needs the raw line, and both platforms must cut, unescape and fold alike. Read line by line from a stream;
 * unfolding is done on bytes (a fold may split a UTF-8 character); each logical line is decoded as UTF-8, or
 * Latin-1 when it is not valid UTF-8.
 */
object Ics {
    /** (line number, unfolded text) of each non-empty logical line. */
    fun logicalLines(stream: InputStream): Sequence<Pair<Int, String>> = sequence {
        val input = stream.buffered()
        var current: java.io.ByteArrayOutputStream? = null
        var start = 0
        var number = 0
        while (true) {
            val line = readRawLine(input) ?: break
            number++
            var bytes = line
            if (number == 1 && bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                bytes = bytes.copyOfRange(3, bytes.size)
            }
            if (bytes.isNotEmpty() && (bytes[0] == ' '.code.toByte() || bytes[0] == '\t'.code.toByte()) && current != null) {
                current.write(bytes, 1, bytes.size - 1)
                continue
            }
            current?.let { if (!isBlank(it.toByteArray())) yield(start to decode(it.toByteArray())) }
            current = java.io.ByteArrayOutputStream().also { it.write(bytes) }
            start = number
        }
        current?.let { if (!isBlank(it.toByteArray())) yield(start to decode(it.toByteArray())) }
    }

    private fun isBlank(b: ByteArray) = b.all { it == ' '.code.toByte() || it == '\t'.code.toByte() || it == '\r'.code.toByte() || it == '\n'.code.toByte() || it == 0x0B.toByte() || it == 0x0C.toByte() }

    /** One physical line without its CR/LF, null at the end. */
    private fun readRawLine(input: java.io.BufferedInputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var c = input.read()
        if (c < 0) return null
        while (c >= 0 && c != '\n'.code) {
            out.write(c)
            c = input.read()
        }
        var bytes = out.toByteArray()
        var end = bytes.size
        while (end > 0 && (bytes[end - 1] == '\r'.code.toByte() || bytes[end - 1] == '\n'.code.toByte())) end--
        if (end != bytes.size) bytes = bytes.copyOf(end)
        return bytes
    }

    private fun decode(data: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data)).toString()
    } catch (_: CharacterCodingException) {
        String(data, Charsets.ISO_8859_1)
    }

    /** NAME;PARAM=a,"b:c";X=y:value -> [ContentLine]. [IcsSyntaxException] when there is no ':'. */
    fun parseLine(text: String): ContentLine {
        var i = 0
        val n = text.length
        while (i < n && text[i] != ';' && text[i] != ':') i++
        if (i >= n) throw IcsSyntaxException("ligne sans « : » : '${text.take(60)}'")
        var name = text.substring(0, i).trim().uppercase()
        if ('.' in name) name = name.substringAfterLast('.')
        val params = LinkedHashMap<String, MutableList<String>>()
        while (i < n && text[i] == ';') {
            i++
            var j = i
            while (j < n && text[j] != '=' && text[j] != ';' && text[j] != ':') j++
            val key = text.substring(i, j).trim().uppercase()
            val values = ArrayList<String>()
            if (j < n && text[j] == '=') {
                j++
                while (true) {
                    if (j < n && text[j] == '"') {
                        val k = text.indexOf('"', j + 1)
                        if (k < 0) throw IcsSyntaxException("guillemet non fermé : '${text.take(60)}'")
                        values.add(text.substring(j + 1, k))
                        j = k + 1
                    } else {
                        var k = j
                        while (k < n && text[k] != ',' && text[k] != ';' && text[k] != ':') k++
                        values.add(text.substring(j, k))
                        j = k
                    }
                    if (j < n && text[j] == ',') { j++; continue }
                    break
                }
            }
            params.getOrPut(key) { ArrayList() }.addAll(values)
            i = j
        }
        if (i >= n || text[i] != ':') throw IcsSyntaxException("ligne sans « : » : '${text.take(60)}'")
        return ContentLine(name, params, text.substring(i + 1), text)
    }

    /**
     * Each VCALENDAR of the stream with its components. Unreadable lines are skipped and reported in [errors]
     * (French, with the line number); they never stop the reading.
     */
    fun readCalendars(stream: InputStream, errors: MutableList<String>): List<Component> {
        val out = ArrayList<Component>()
        val stack = ArrayList<Component>()
        for ((number, text) in logicalLines(stream)) {
            val line = try {
                parseLine(text)
            } catch (e: IcsSyntaxException) {
                errors.add("ligne $number : ${e.message}"); continue
            }
            when {
                line.name == "BEGIN" -> {
                    val c = Component(line.value.trim().uppercase(), lineNumber = number)
                    stack.lastOrNull()?.children?.add(c)
                    stack.add(c)
                }
                line.name == "END" -> {
                    val name = line.value.trim().uppercase()
                    if (stack.isEmpty() || stack.last().name != name) {
                        errors.add("ligne $number : END:$name inattendu")
                        while (stack.isNotEmpty() && stack.last().name != name) stack.removeAt(stack.size - 1)
                        if (stack.isEmpty()) continue
                    }
                    val c = stack.removeAt(stack.size - 1)
                    if (stack.isEmpty()) out.add(c)
                }
                stack.isNotEmpty() -> stack.last().lines.add(line)
                else -> errors.add("ligne $number : propriété hors de BEGIN:VCALENDAR")
            }
        }
        if (stack.isNotEmpty()) {
            errors.add("fin de fichier dans ${stack.last().name}")
            out.add(stack[0])
        }
        return out
    }

    // ---- text values ----

    fun unescapeText(value: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                val n = value[i + 1]
                sb.append(if (n == 'n' || n == 'N') '\n' else n)
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    fun escapeText(value: String): String =
        value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\n").replace("\n", "\\n")

    /** Comma list of a value (EXDATE, CATEGORIES), honouring "\,". */
    fun splitList(value: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                cur.append(value, i, i + 2); i += 2; continue
            }
            if (c == ',') { out.add(cur.toString()); cur.clear() } else cur.append(c)
            i++
        }
        out.add(cur.toString())
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** One content line folded at 75 octets (CRLF + space), never inside a character. */
    fun fold(line: String): String {
        val sb = StringBuilder()
        var size = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val chars = Character.charCount(cp)
            val width = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (size + width > limit) {
                sb.append("\r\n ")
                size = 0
                limit = 74
            }
            sb.append(line, i, i + chars)
            size += width
            i += chars
        }
        return sb.append("\r\n").toString()
    }

    // ---- durations ----

    private val DURATION = Regex("""^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""")

    /** RFC 5545 duration -> (days, seconds), both carrying the sign. */
    fun parseDuration(value: String): Pair<Long, Long> {
        val v = value.trim().uppercase()
        val m = DURATION.matchEntire(v)
        if (m == null || v in setOf("P", "PT", "+P", "-P")) throw IllegalArgumentException("durée illisible : '$value'")
        val g = m.groupValues
        val sign = if (g[1] == "-") -1L else 1L
        val days = (g[2].ifEmpty { "0" }.toLong()) * 7 + g[3].ifEmpty { "0" }.toLong()
        val seconds = g[4].ifEmpty { "0" }.toLong() * 3600 + g[5].ifEmpty { "0" }.toLong() * 60 + g[6].ifEmpty { "0" }.toLong()
        return sign * days to sign * seconds
    }

    fun formatAlertTrigger(minutes: Int): String {
        val sign = if (minutes < 0) "-" else ""
        val n = Math.abs(minutes)
        return when {
            n == 0 -> "PT0M"
            n % 1440 == 0 -> "${sign}P${n / 1440}D"
            n % 60 == 0 -> "${sign}PT${n / 60}H"
            else -> "${sign}PT${n}M"
        }
    }

    // ---- HTML descriptions ----

    private val HTML = Regex("""<(br|p|div|a|b|i|u|ul|ol|li|span|strong|em|html|body|font|table|tr|td|h[1-6])\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val LINK = Regex("""<a\b[^>]*?href\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))[^>]*>(.*?)</a\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ENTITY = Regex("""&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);""")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

    fun looksHtml(text: String): Boolean = HTML.containsMatchIn(text)

    private fun entities(text: String): String = ENTITY.replace(text) { m ->
        val e = m.groupValues[1]
        if (e.startsWith("#")) {
            val code = (if (e.length > 1 && (e[1] == 'x' || e[1] == 'X')) e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull())
            if (code != null && code in 1 until 0x110000) String(Character.toChars(code)) else m.value
        } else NAMED[e.lowercase()] ?: m.value
    }

    /** Plain text of an HTML description: line breaks kept, links as "texte (url)", tags dropped. */
    fun htmlToText(text: String): String {
        var s = Regex("""(?is)<(script|style)\b.*?</\1\s*>""").replace(text, "")
        s = LINK.replace(s) { m ->
            val g = m.groupValues
            val hrefRaw = when {
                m.groups[1] != null -> g[1]
                m.groups[2] != null -> g[2]
                else -> g[3]
            }
            val href = entities(hrefRaw).trim()
            val label = entities(g[4].replace(Regex("<[^>]+>"), "")).trim()
            if (label.isEmpty() || label == href) href else "$label ($href)"
        }
        s = Regex("""(?i)<br\s*/?>""").replace(s, "\n")
        s = Regex("""(?i)<li\b[^>]*>""").replace(s, "\n- ")
        s = Regex("""(?i)</(p|div|ul|ol|tr|h[1-6])\s*>""").replace(s, "\n")
        s = Regex("""(?i)<(p|div|ul|ol|tr|h[1-6])\b[^>]*>""").replace(s, "\n")
        s = Regex("<[^>]+>").replace(s, "")
        s = entities(s).replace('\u00a0', ' ')
        s = s.replace("\r\n", "\n").split("\n").joinToString("\n") { it.trim() }
        s = Regex("\n{3,}").replace(s, "\n\n")
        return s.trim()
    }
}
