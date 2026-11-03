package app.memento.map

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
        return fastNum(v, decimals) ?: exactNum(v, decimals)
    }

    /** The reference rounding (the exact binary value, half up), kept for near ties: several times slower. */
    internal fun exactNum(v: Double, decimals: Int): String {
        val s = java.math.BigDecimal(v).setScale(decimals, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return if (s == "-0") "0" else s
    }

    private val POW10 = LongArray(10).also { p -> p[0] = 1; for (i in 1 until p.size) p[i] = p[i - 1] * 10 }

    /**
     * [exactNum]'s digits without a BigDecimal (a 200 000-point track is a million numbers), or null when the value is
     * too large or so close to a tie that the double product could round the other way.
     */
    private fun fastNum(v: Double, decimals: Int): String? {
        if (decimals !in 0 until POW10.size) return null
        val scaled = kotlin.math.abs(v) * POW10[decimals]
        // below 1e9 the product is off by less than 2e-7: only a fraction within 1e-6 of one half can round differently
        if (scaled >= 1e9) return null
        val whole = kotlin.math.floor(scaled)
        val frac = scaled - whole
        if (kotlin.math.abs(frac - 0.5) < 1e-6) return null
        val units = whole.toLong() + if (frac > 0.5) 1 else 0
        if (units == 0L) return "0"
        val p = POW10[decimals]
        val intPart = units / p
        var fracPart = units % p
        val sb = StringBuilder(24)
        if (v < 0) sb.append('-')
        sb.append(intPart)
        if (fracPart != 0L) {
            var digits = decimals
            while (fracPart % 10 == 0L) { fracPart /= 10; digits-- }
            sb.append('.')
            val f = fracPart.toString()
            for (i in f.length until digits) sb.append('0')
            sb.append(f)
        }
        return sb.toString()
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
