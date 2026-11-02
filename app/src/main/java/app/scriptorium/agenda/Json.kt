package app.scriptorium.agenda

/**
 * Minimal JSON for the agenda's small files (`calendars.json`) and the test vectors: objects keep their key
 * order (LinkedHashMap), numbers are Long or Double. `org.json` is not usable in JVM unit tests (stubbed).
 * [write] produces exactly Python's `json.dumps(value, ensure_ascii=False, indent=2)`.
 */
object Json {
    class JsonException(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw JsonException("caractère inattendu à la position ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        fun value(): Any? {
            if (i >= s.length) throw JsonException("fin inattendue")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw JsonException("caractère inattendu à la position $i")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw JsonException("mot inconnu à la position $i")
            i += word.length
            return v
        }

        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return if (t.any { it in ".eE" }) t.toDoubleOrNull() ?: throw JsonException("nombre illisible : $t")
            else t.toLongOrNull() ?: throw JsonException("nombre illisible : $t")
        }

        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonException("chaîne non fermée")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw JsonException("chaîne non fermée")
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonException("échappement illisible")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                ws(); out.add(value()); ws()
                if (i >= s.length) throw JsonException("tableau non fermé")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw JsonException("« , » ou « ] » attendu à la position ${i - 1}")
                }
            }
        }

        fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw JsonException("clé attendue à la position $i")
                val k = str()
                ws()
                if (i >= s.length || s[i++] != ':') throw JsonException("« : » attendu à la position ${i - 1}")
                ws(); out[k] = value(); ws()
                if (i >= s.length) throw JsonException("objet non fermé")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw JsonException("« , » ou « } » attendu à la position ${i - 1}")
                }
            }
        }
    }

    fun write(value: Any?): String = StringBuilder().also { write(it, value, 0) }.toString()

    private fun write(sb: StringBuilder, v: Any?, depth: Int) {
        val pad = "  ".repeat(depth + 1)
        val close = "  ".repeat(depth)
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Double -> sb.append(if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e16) v.toLong().toString() + ".0" else v.toString())
            is String -> quote(sb, v)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(",\n")
                    first = false
                    sb.append(pad); quote(sb, k.toString()); sb.append(": "); write(sb, x, depth + 1)
                }
                sb.append('\n').append(close).append('}')
            }
            is List<*> -> {
                if (v.isEmpty()) { sb.append("[]"); return }
                sb.append("[\n")
                v.forEachIndexed { idx, x ->
                    if (idx > 0) sb.append(",\n")
                    sb.append(pad); write(sb, x, depth + 1)
                }
                sb.append('\n').append(close).append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' -> sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
