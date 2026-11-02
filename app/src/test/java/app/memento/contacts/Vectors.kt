package app.memento.contacts

import app.memento.Fs
import java.io.File

/** The shared test vectors of `testdata/contacts/` (also read by the Windows tests). */
object Vectors {
    private val dir: File = listOf(File("../testdata/contacts"), File("testdata/contacts")).first { it.isDirectory }

    fun bytes(name: String): ByteArray = File(dir, name).readBytes()
    fun text(name: String): String = File(dir, name).readText(Charsets.UTF_8)
    fun json(name: String): Any? = Json.parse(text(name))

    @Suppress("UNCHECKED_CAST")
    fun contact(m: Map<String, Any?>): Contact = Contact(
        id = m["id"] as String? ?: "",
        firstName = m["first_name"] as String? ?: "",
        lastName = m["last_name"] as String? ?: "",
        phones = (m["phones"] as List<String>? ?: emptyList()).map { Phone.decode(it) },
        emails = m["emails"] as List<String>? ?: emptyList(),
        address = m["address"] as String?,
        birthday = Birthday.parse(m["birthday"] as String?),
        favorite = m["favorite"] as Boolean? ?: false,
        uid = m["uid"] as String?,
        notes = m["notes"] as String? ?: "",
    )

    /** The comparable fields of a contact, in the vectors' shape. */
    fun fields(c: Contact): Map<String, Any?> = linkedMapOf(
        "first_name" to c.firstName, "last_name" to c.lastName, "phones" to c.phones.map { it.encode() }, "emails" to c.emails,
        "address" to c.address, "birthday" to c.birthday?.encode(), "notes" to c.notes, "uid" to c.uid, "favorite" to c.favorite,
    )
}

/** Just enough JSON for the vectors (android.jar's org.json is a stub in unit tests). */
object Json {
    fun parse(s: String): Any? = Reader(s).run { val v = value(); ws(); v }

    private class Reader(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> if (c == '-' || c.isDigit()) num() else error("bad json at $i")
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); i++ // ':'
                m[k] = value(); ws()
                if (s[i++] == '}') return m
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value()); ws()
                if (s[i++] == ']') return l
            }
        }
        fun str(): String {
            val sb = StringBuilder()
            i++
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (val e = s[i]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                    i++
                } else sb.append(s[i++])
            }
            i++
            return sb.toString()
        }
        fun num(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDouble()
        }
    }
}

/** An in-memory folder for [ContactBook]. */
class MemFs : Fs {
    val files = LinkedHashMap<String, String>()
    private val times = HashMap<String, Long>()
    private var clock = 1_700_000_000_000L

    override fun list(dir: String): List<Fs.Entry> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        return files.keys.filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
            .map { Fs.Entry(it.removePrefix(prefix), false, times[it] ?: 0) }
    }
    override fun readBytes(path: String): ByteArray? = files[path]?.toByteArray()
    override fun writeBytes(path: String, bytes: ByteArray) { files[path] = String(bytes); times[path] = ++clock }
    override fun delete(path: String) { files.remove(path); times.remove(path) }
    override fun exists(path: String) = path in files
    override fun lastModified(path: String) = times[path] ?: 0
    override fun describe() = "mem"
}
