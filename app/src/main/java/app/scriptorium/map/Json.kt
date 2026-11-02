package app.scriptorium.map

/**
 * A minimal JSON reader and string encoder. org.json is an Android stub in JVM unit tests, so the map
 * code (pack manifests, peaks, IGN answers) parses with this instead.
 */
object Json {
    class JsonException(message: String) : Exception(message)

    /** Objects become [Map], arrays [List], numbers [Double], plus [String], [Boolean] and null. */
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw JsonException("JSON : caractère inattendu à ${p.i}")
        return v
    }

    fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' || c == ' ' || c == ' ' -> append(String.format("\\u%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    /** A number as JSON: no exponent, no trailing ".0", [decimals] at most. */
    fun num(v: Double, decimals: Int = 6): String {
        if (v.isNaN() || v.isInfinite()) return "null"
        val s = java.math.BigDecimal(v).setScale(decimals, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return if (s == "-0") "0" else s
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            if (i >= s.length) throw JsonException("JSON tronqué")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw JsonException("JSON : caractère inattendu à $i")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw JsonException("JSON : mot inattendu à $i")
            i += word.length
            return v
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw JsonException("JSON : nombre invalide à $start")
        }

        private fun str(): String {
            i++ // opening quote
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonException("JSON : chaîne non terminée")
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        if (i >= s.length) throw JsonException("JSON : chaîne non terminée")
                        when (val e = s[i++]) {
                            'n' -> b.append('\n')
                            't' -> b.append('\t')
                            'r' -> b.append('\r')
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000c')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonException("JSON : échappement invalide")
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: throw JsonException("JSON : échappement invalide")
                                b.append(code.toChar())
                                i += 4
                            }
                            else -> b.append(e)
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                ws()
                out.add(value())
                ws()
                if (i >= s.length) throw JsonException("JSON tronqué")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw JsonException("JSON : ',' ou ']' attendu à ${i - 1}")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw JsonException("JSON : clé attendue à $i")
                val k = str()
                ws()
                if (i >= s.length || s[i++] != ':') throw JsonException("JSON : ':' attendu")
                ws()
                out[k] = value()
                ws()
                if (i >= s.length) throw JsonException("JSON tronqué")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw JsonException("JSON : ',' ou '}' attendu à ${i - 1}")
                }
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun Any?.asList(): List<Any?>? = this as? List<Any?>

internal fun Any?.asDouble(): Double? = when (this) {
    is Double -> this
    is String -> this.toDoubleOrNull()
    else -> null
}
