package app.scriptorium.contacts

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** What a `.vcf` file holds: the cards that became contacts, and what was left out. */
data class VCardResult(
    /** One draft per usable card (no id, no file yet). */
    val contacts: List<Contact>,
    /** Cards that could not be read (truncated, garbled): skipped. */
    val bad: Int = 0,
    /** Cards with nothing to import at all: skipped. */
    val empty: Int = 0,
    /** Properties the app does not keep, by name (`PHOTO`, `URL`, `X-…`), counted for the report. */
    val ignored: Map<String, Int> = emptyMap(),
)

/**
 * A small hand-written vCard 2.1 / 3.0 / 4.0 reader and a 3.0 writer, for what real exports contain:
 * quoted-printable with a CHARSET (Android's 2.1 exports), folded lines, `item1.TEL` grouped with
 * `item1.X-ABLabel` (Google, Apple), `tel:` URIs (4.0), several TEL of the same type, FN without N,
 * company-only cards, BDAY as `1990-05-14`, `19900514` or `--0514`. Same algorithm in `core/vcard.py`.
 */
object VCard {
    private class Prop(val group: String?, val name: String, val params: Map<String, List<String>>, val types: Set<String>, val raw: String) {
        val qp: Boolean get() = params["ENCODING"]?.any { it.equals("QUOTED-PRINTABLE", true) } == true || "QUOTED-PRINTABLE" in types
        val binary: Boolean get() = params["ENCODING"]?.any { it.equals("B", true) || it.equals("BASE64", true) } == true || "BASE64" in types
        val charset: String? get() = params["CHARSET"]?.firstOrNull()
    }

    private class CardError(message: String) : Exception(message)

    /** Properties that are read (or are structure) and therefore never reported as ignored. */
    private val READ = setOf("BEGIN", "END", "VERSION", "N", "FN", "TEL", "EMAIL", "ADR", "BDAY", "NOTE", "ORG", "TITLE", "UID", "X-ABLABEL", "X-SCRIPTORIUM-FAVORITE", "PRODID", "KIND")

    // ---- reading ----

    /** Reads a whole file: UTF-8, or Windows-1252 when the bytes are not UTF-8. */
    fun parse(bytes: ByteArray): VCardResult = parse(decodeFile(bytes))

    fun parse(text: String): VCardResult {
        val lines = unfold(text.removePrefix("\uFEFF"))
        val contacts = ArrayList<Contact>()
        val ignored = LinkedHashMap<String, Int>()
        var bad = 0
        var empty = 0
        fun finish(lines: List<String>) {
            try {
                val c = readCard(lines, ignored)
                if (c == null) empty++ else contacts.add(c)
            } catch (_: CardError) {
                bad++
            }
        }
        var card: MutableList<String>? = null
        for (line in lines) {
            val upper = line.trim().uppercase()
            if (upper == "BEGIN:VCARD") {
                if (card != null) bad++ // the previous card never ended
                card = ArrayList()
                continue
            }
            if (upper == "END:VCARD" && card != null) {
                finish(card)
                card = null
                continue
            }
            card?.add(line)
        }
        if (card != null) bad++ // truncated file
        return VCardResult(contacts, bad, empty, ignored)
    }

    private fun decodeFile(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1252"))
    }

    /**
     * Logical lines: a line starting with a space or a tab continues the previous one (that one character
     * removed); a quoted-printable value ending with `=` continues on the next line (soft line break).
     */
    private fun unfold(text: String): List<String> {
        val out = ArrayList<String>()
        for (line in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val last = out.lastIndex
            if (last >= 0 && (line.startsWith(" ") || line.startsWith("\t")) && !isQpOpen(out[last])) {
                out[last] = out[last] + line.substring(1)
            } else if (last >= 0 && isQpOpen(out[last])) {
                out[last] = out[last].dropLast(1) + line
            } else {
                out.add(line)
            }
        }
        return out.filter { it.isNotBlank() }
    }

    private fun isQpOpen(line: String): Boolean {
        if (!line.endsWith("=")) return false
        val colon = line.indexOf(':')
        return colon > 0 && line.substring(0, colon).uppercase().contains("QUOTED-PRINTABLE")
    }

    /** `[group.]NAME;PARAM=a,b;BARE:value` */
    private fun parseLine(line: String): Prop? {
        var colon = -1
        var quoted = false
        for (i in line.indices) {
            val ch = line[i]
            if (ch == '"') quoted = !quoted
            else if (ch == ':' && !quoted) { colon = i; break }
        }
        if (colon <= 0) return null
        val head = splitUnquoted(line.substring(0, colon), ';')
        val fullName = head[0].trim()
        val dot = fullName.lastIndexOf('.')
        val group = if (dot > 0) fullName.substring(0, dot) else null
        val name = fullName.substring(dot + 1).uppercase()
        val params = LinkedHashMap<String, MutableList<String>>()
        val types = LinkedHashSet<String>()
        for (p in head.drop(1)) {
            val eq = p.indexOf('=')
            if (eq < 0) { types.add(p.trim().uppercase()); continue } // vCard 2.1: TEL;CELL;VOICE
            val key = p.substring(0, eq).trim().uppercase()
            // TYPE="cell,voice" (4.0) and TYPE=cell,voice are the same list
            val values = splitUnquoted(p.substring(eq + 1), ',').flatMap { it.trim().removeSurrounding("\"").split(',') }
                .map { it.trim() }.filter { it.isNotEmpty() }
            params.getOrPut(key) { ArrayList() }.addAll(values)
            if (key == "TYPE") values.forEach { types.add(it.uppercase()) }
        }
        return Prop(group, name, params, types, line.substring(colon + 1))
    }

    private fun splitUnquoted(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        for (ch in s) {
            if (ch == '"') quoted = !quoted
            if (ch == sep && !quoted) { out.add(cur.toString()); cur.clear() } else cur.append(ch)
        }
        out.add(cur.toString())
        return out
    }

    /** The value as text: quoted-printable decoded with its CHARSET (UTF-8 by default). */
    private fun text(p: Prop): String = decoded(p).replace("\r\n", "\n").replace('\r', '\n')

    private fun decoded(p: Prop): String {
        if (!p.qp) return p.raw
        val bytes = ByteArrayOutputStream()
        var i = 0
        val s = p.raw
        while (i < s.length) {
            val ch = s[i]
            val hex = if (ch == '=' && i + 2 < s.length) s.substring(i + 1, i + 3).takeIf { h -> h.all { it in "0123456789abcdefABCDEF" } }?.toInt(16) else null
            if (hex != null) {
                bytes.write(hex)
                i += 3
            } else if (ch == '=' && i == s.length - 1) {
                i++ // trailing soft break
            } else {
                val b = ch.toString().toByteArray(Charsets.UTF_8)
                bytes.write(b, 0, b.size)
                i++
            }
        }
        val charset = try { p.charset?.let { Charset.forName(it) } ?: Charsets.UTF_8 } catch (_: Exception) { Charsets.UTF_8 }
        return try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: CharacterCodingException) {
            String(bytes.toByteArray(), Charset.forName("windows-1252"))
        }
    }

    /** Splits a structured value on unescaped `;` (N, ADR, ORG), then unescapes each part. */
    private fun components(value: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) { cur.append(ch).append(value[i + 1]); i += 2; continue }
            if (ch == ';') { out.add(unescape(cur.toString())); cur.clear() } else cur.append(ch)
            i++
        }
        out.add(unescape(cur.toString()))
        return out
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(ch); i++
            }
        }
        return sb.toString()
    }

    private fun phoneLabel(types: Set<String>, abLabel: String?): String? {
        abLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val l = raw.removePrefix("_\$!<").removeSuffix(">!\$_").trim()
            return when (l.lowercase()) {
                "mobile", "cell", "iphone", "portable", "mobile phone" -> PhoneLabels.MOBILE
                "home", "domicile", "maison" -> PhoneLabels.HOME
                "work", "travail", "bureau" -> PhoneLabels.WORK
                "other", "autre", "main" -> PhoneLabels.OTHER
                else -> l
            }
        }
        val t = types - setOf("PREF", "VOICE", "INTERNET", "X-INTERNET", "QUOTED-PRINTABLE", "BASE64")
        return when {
            "CELL" in t || "MOBILE" in t || "IPHONE" in t -> PhoneLabels.MOBILE
            "WORK" in t -> PhoneLabels.WORK
            "HOME" in t -> PhoneLabels.HOME
            t.isEmpty() -> if ("VOICE" in types) PhoneLabels.OTHER else null
            else -> PhoneLabels.OTHER
        }
    }

    private fun address(parts: List<String>): String? {
        fun p(i: Int) = parts.getOrNull(i)?.replace("\n", ", ")?.trim().orEmpty()
        val street = listOf(p(1), p(2)).filter { it.isNotEmpty() }.joinToString(", ")
        val city = listOf(p(5), p(3)).filter { it.isNotEmpty() }.joinToString(" ")
        val all = listOf(p(0), street, city, p(4), p(6)).filter { it.isNotEmpty() }.joinToString(", ")
        return all.ifEmpty { null }
    }

    /** One card to a contact draft; null when there is nothing in it. */
    private fun readCard(lines: List<String>, ignored: MutableMap<String, Int>): Contact? {
        val props = ArrayList<Prop>()
        var lastBinary = false
        for (line in lines) {
            val p = parseLine(line)
            if (p == null) {
                if (lastBinary) continue // unindented base64 continuation of a photo
                throw CardError("Ligne illisible")
            }
            lastBinary = p.binary
            props.add(p)
        }
        val labels = props.filter { it.name == "X-ABLABEL" && it.group != null }.associate { it.group!!.uppercase() to unescape(text(it)) }
        val version = props.firstOrNull { it.name == "VERSION" }?.raw?.trim()

        var first = ""
        var last = ""
        var fn = ""
        var org = ""
        var title = ""
        val phones = ArrayList<Phone>()
        val emails = ArrayList<String>()
        val addresses = ArrayList<String>()
        var birthday: Birthday? = null
        val notes = ArrayList<String>()
        var uid: String? = null
        var favorite = false
        for (p in props) {
            when (p.name) {
                "N" -> {
                    val c = components(text(p))
                    last = c.getOrElse(0) { "" }.trim()
                    first = listOf(c.getOrElse(1) { "" }, c.getOrElse(2) { "" }).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
                }
                "FN" -> fn = unescape(text(p)).trim()
                "ORG" -> org = components(text(p)).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
                "TITLE" -> title = unescape(text(p)).trim()
                "TEL" -> {
                    val number = unescape(text(p)).trim().removePrefix("tel:").removePrefix("TEL:").trim()
                    if (number.isNotEmpty()) phones.add(Phone(number, phoneLabel(p.types, p.group?.let { labels[it.uppercase()] })))
                }
                "EMAIL" -> unescape(text(p)).trim().removePrefix("mailto:").takeIf { it.isNotEmpty() }?.let { e ->
                    if (emails.none { it.equals(e, ignoreCase = true) }) emails.add(e)
                }
                "ADR" -> address(components(text(p)))?.let { addresses.add(it) }
                "BDAY" -> {
                    val b = Birthday.parse(unescape(text(p)))
                    if (b != null) birthday = b else ignored.merge("BDAY", 1, Int::plus)
                }
                "NOTE" -> unescape(text(p)).trim().takeIf { it.isNotEmpty() }?.let { notes.add(it) }
                "UID" -> uid = unescape(text(p)).trim().ifEmpty { null }
                "X-SCRIPTORIUM-FAVORITE" -> favorite = text(p).trim().equals("TRUE", ignoreCase = true)
                in READ -> Unit
                else -> {
                    val key = if (p.name.startsWith("X-")) "X-…" else p.name
                    ignored.merge(key, 1, Int::plus)
                }
            }
        }
        if (version != null && version !in setOf("2.1", "3.0", "4.0")) throw CardError("Version $version")
        var companyName = false
        if (first.isEmpty() && last.isEmpty()) {
            when {
                fn.isNotEmpty() && fn == org -> { first = org; companyName = true }
                fn.isNotEmpty() -> {
                    val sp = fn.lastIndexOf(' ')
                    if (sp > 0) { first = fn.substring(0, sp).trim(); last = fn.substring(sp + 1).trim() } else first = fn
                }
                org.isNotEmpty() -> { first = org; companyName = true }
            }
        }
        val body = ArrayList<String>()
        val company = if (companyName) "" else org
        when {
            company.isNotEmpty() && title.isNotEmpty() -> body.add("Société : $company — $title")
            company.isNotEmpty() -> body.add("Société : $company")
            title.isNotEmpty() -> body.add("Fonction : $title")
        }
        body.addAll(notes)
        addresses.drop(1).forEach { body.add("Adresse : $it") }
        val c = Contact(
            firstName = first, lastName = last, phones = phones, emails = emails, address = addresses.firstOrNull(),
            birthday = birthday, favorite = favorite, uid = uid, notes = body.joinToString("\n\n"),
        )
        return if (c.isEmpty) null else c
    }

    // ---- writing (vCard 3.0, the widest compatibility) ----

    fun write(contacts: List<Contact>): String = contacts.joinToString("") { write(it) }

    fun write(c: Contact): String {
        val out = ArrayList<String>()
        out.add("BEGIN:VCARD")
        out.add("VERSION:3.0")
        out.add("N:" + listOf(c.lastName, c.firstName, "", "", "").joinToString(";") { escape(it.trim()) })
        // empty for a contact without a name: a number in FN would come back as a name
        out.add("FN:" + escape(c.name))
        var item = 0
        for (p in c.phones) {
            when (p.label) {
                null, "" -> out.add("TEL:" + escape(p.number))
                PhoneLabels.MOBILE -> out.add("TEL;TYPE=CELL:" + escape(p.number))
                PhoneLabels.HOME -> out.add("TEL;TYPE=HOME:" + escape(p.number))
                PhoneLabels.WORK -> out.add("TEL;TYPE=WORK:" + escape(p.number))
                PhoneLabels.OTHER -> out.add("TEL;TYPE=VOICE:" + escape(p.number))
                else -> {
                    item++
                    out.add("item$item.TEL:" + escape(p.number))
                    out.add("item$item.X-ABLabel:" + escape(p.label))
                }
            }
        }
        c.emails.forEach { out.add("EMAIL;TYPE=INTERNET:" + escape(it)) }
        c.address?.takeIf { it.isNotBlank() }?.let { out.add("ADR:;;" + escape(it) + ";;;;") }
        c.birthday?.let { out.add("BDAY:" + it.encode()) }
        if (c.notes.isNotBlank()) out.add("NOTE:" + escape(c.notes.trim('\n')))
        c.uid?.let { out.add("UID:" + escape(it)) }
        if (c.favorite) out.add("X-SCRIPTORIUM-FAVORITE:TRUE")
        out.add("END:VCARD")
        return out.joinToString("") { fold(it) + "\r\n" }
    }

    private fun escape(s: String) =
        s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\r\n", "\n").replace("\n", "\\n")

    /** Lines of at most 75 octets, continued with a space, never splitting a character. */
    private fun fold(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= 75) return line
        val sb = StringBuilder()
        var size = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val chars = Character.charCount(cp)
            val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (size + bytes > limit) {
                sb.append("\r\n ")
                size = 0
                limit = 74
            }
            sb.appendCodePoint(cp)
            size += bytes
            i += chars
        }
        return sb.toString()
    }
}
