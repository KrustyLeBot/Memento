package app.memento

import java.text.Normalizer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Markdown + YAML frontmatter reader/writer. Pure Kotlin so it can be unit-tested on the JVM. */
object NoteFormat {
    private val KNOWN = setOf("id", "title", "color", "pinned", "labels", "reminder", "pdf", "attachments", "activity", "created", "updated")
    private val KEY_REGEX = Regex("""^([A-Za-z_][\w-]*):[ \t]*(.*)$""")
    private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun parse(path: String, rawText: String, lastModified: Long): Note {
        val text = rawText.removePrefix("\uFEFF").replace("\r\n", "\n")
        val fileName = path.substringAfterLast('/')
        val conflict = fileName.contains("conflict", ignoreCase = true)
        val lines = text.split("\n")

        var close = -1
        if (lines.isNotEmpty() && lines[0].trimEnd() == "---") {
            for (i in 1 until lines.size) {
                if (lines[i].trimEnd() == "---") { close = i; break }
            }
        }

        if (close < 0) {
            // Plain markdown file created elsewhere: title comes from the file name.
            val title = fileName.removeSuffix(".md").replace(Regex("[-_]+"), " ").trim()
            return Note(
                path = path, id = generateId(), hasId = false,
                title = title, created = lastModified, updated = lastModified,
                body = text.trim('\n'), conflict = conflict,
            )
        }

        // Group frontmatter lines into key blocks (key line + indented/list continuation lines).
        val blocks = ArrayList<Pair<String, MutableList<String>>>()
        for (line in lines.subList(1, close)) {
            val m = KEY_REGEX.matchEntire(line)
            if (m != null && !line.startsWith(" ") && !line.startsWith("-")) {
                blocks.add(m.groupValues[1] to mutableListOf(line))
            } else if (blocks.isNotEmpty()) {
                blocks.last().second.add(line)
            }
        }

        val values = HashMap<String, String>()
        val extra = ArrayList<String>()
        for ((key, blockLines) in blocks) {
            if (key in KNOWN) {
                val first = KEY_REGEX.matchEntire(blockLines[0])!!.groupValues[2].trim()
                values[key] = if (first.isEmpty() && blockLines.size > 1) {
                    "[" + blockLines.drop(1).filter { it.trim().startsWith("-") }
                        .joinToString(", ") { yamlQuote(unquote(it.trim().removePrefix("-").trim()), flow = true) } + "]"
                } else first
            } else {
                extra.addAll(blockLines)
            }
        }

        val body = lines.subList(close + 1, lines.size).joinToString("\n").trim('\n')
        val id = values["id"]?.let { unquote(it) }.orEmpty()
        return Note(
            path = path,
            id = id.ifEmpty { generateId() },
            hasId = id.isNotEmpty(),
            title = values["title"]?.let { unquote(it) }
                ?: fileName.removeSuffix(".md").replace(Regex("[-_]+"), " ").trim(),
            color = values["color"]?.let { unquote(it) }?.ifEmpty { null } ?: DEFAULT_COLOR,
            pinned = values["pinned"]?.trim()?.equals("true", ignoreCase = true) == true,
            labels = values["labels"]?.let { parseList(it) }.orEmpty(),
            reminder = values["reminder"]?.let { parseTime(it) },
            pdf = values["pdf"]?.let { unquote(it) }?.ifEmpty { null },
            attachments = values["attachments"]?.let { parseList(it) }.orEmpty(),
            activity = values["activity"]?.let { app.memento.activity.ActivityInfo.parse(it) },
            created = values["created"]?.let { parseTime(it) } ?: lastModified,
            updated = values["updated"]?.let { parseTime(it) } ?: lastModified,
            body = body,
            extra = extra,
            conflict = conflict,
        )
    }

    fun serialize(note: Note): String {
        val sb = StringBuilder("---\n")
        sb.append("id: ").append(note.id).append('\n')
        sb.append("title: ").append(yamlQuote(note.title)).append('\n')
        if (note.color != DEFAULT_COLOR) sb.append("color: ").append(note.color).append('\n')
        if (note.pinned) sb.append("pinned: true\n")
        if (note.labels.isNotEmpty()) {
            sb.append("labels: [").append(note.labels.joinToString(", ") { yamlQuote(it, flow = true) }).append("]\n")
        }
        note.reminder?.let { sb.append("reminder: ").append(formatTime(it)).append('\n') }
        note.pdf?.let { sb.append("pdf: ").append(yamlQuote(it)).append('\n') }
        if (note.attachments.isNotEmpty()) {
            sb.append("attachments: [").append(note.attachments.joinToString(", ") { yamlQuote(it, flow = true) }).append("]\n")
        }
        note.activity?.let { sb.append("activity: ").append(it.toJson()).append('\n') }
        sb.append("created: ").append(formatTime(note.created)).append('\n')
        sb.append("updated: ").append(formatTime(note.updated)).append('\n')
        note.extra.forEach { sb.append(it).append('\n') }
        sb.append("---\n")
        if (note.body.isNotBlank()) sb.append('\n').append(note.body.trim('\n')).append('\n')
        return sb.toString()
    }

    // ---- ids and file names ----

    fun generateId(): String = buildString { repeat(6) { append(ID_ALPHABET[Random.nextInt(ID_ALPHABET.length)]) } }

    fun slug(title: String): String {
        val folded = Normalizer.normalize(title.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val slug = folded.replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-')
        return slug.ifEmpty { "note" }
    }

    fun fileNameFor(title: String, id: String): String = "${slug(title)}-$id.md"

    /** Where a file attached under [original] name is stored: `attachments/<slug>-<id6>.<ext>` (random, the caller checks it is free). */
    fun attachmentPath(original: String): String {
        val name = original.substringAfterLast('/').substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0) name.substring(dot + 1).lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(10) else ""
        val stem = slug(if (dot > 0) name.substring(0, dot) else name)
        return "attachments/$stem-${generateId()}" + if (ext.isNotEmpty()) ".$ext" else ""
    }

    // ---- YAML scalars ----

    fun unquote(raw: String): String {
        val s = raw.trim()
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') {
            val inner = s.substring(1, s.length - 1)
            val sb = StringBuilder()
            var i = 0
            while (i < inner.length) {
                val c = inner[i]
                if (c == '\\' && i + 1 < inner.length) {
                    sb.append(
                        when (val n = inner[i + 1]) {
                            'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; else -> n
                        },
                    )
                    i += 2
                } else {
                    sb.append(c); i++
                }
            }
            return sb.toString()
        }
        if (s.length >= 2 && s.first() == '\'' && s.last() == '\'') {
            return s.substring(1, s.length - 1).replace("''", "'")
        }
        return s
    }

    fun yamlQuote(s: String, flow: Boolean = false): String {
        val reserved = setOf("true", "false", "null", "yes", "no", "on", "off", "~")
        val needs = s.isEmpty() || s != s.trim() || s.contains('\n') || s.contains(": ") || s.contains(" #") ||
            s.endsWith(":") || s[0] in "[]{}&*!|>'\"%@`#,?-:" || s.lowercase() in reserved ||
            Regex("-?\\d+(\\.\\d+)?").matches(s) || (flow && s.any { it in ",[]{}" }) || s.contains('\\')
        if (!needs) return s
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t")
        return "\"$escaped\""
    }

    fun parseList(raw: String): List<String> {
        val s = raw.trim()
        val inner = if (s.startsWith("[") && s.endsWith("]")) s.substring(1, s.length - 1) else s
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            when {
                quote != null -> {
                    cur.append(c)
                    if (c == '\\' && quote == '"' && i + 1 < inner.length) { cur.append(inner[++i]) }
                    else if (c == quote) quote = null
                }
                c == '"' || c == '\'' -> { quote = c; cur.append(c) }
                c == ',' -> { out.add(cur.toString()); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out.map { unquote(it) }.filter { it.isNotEmpty() }
    }

    // ---- timestamps ----

    fun formatTime(ms: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
            .truncatedTo(ChronoUnit.SECONDS)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun parseTime(raw: String): Long? {
        val s = unquote(raw)
        return try {
            OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }
}

// ---- Body <-> editor model ----

data class CheckItem(val id: Long, val text: String, val checked: Boolean)

/**
 * The editor sees a body as free text, then one checklist, then trailing text
 * (images and anything else that must survive untouched).
 */
data class BodyParts(val before: String, val items: List<CheckItem>, val after: String)

object ItemIds {
    private val counter = AtomicLong(1)
    fun next(): Long = counter.getAndIncrement()
}

object BodyFormat {
    private val SHARED_OPEN = Regex("""^\s*(?:[-*•]\s*)?(?:☐|□|⬜|\[ ?\])\s*(.*)$""")
    private val SHARED_DONE = Regex("""^\s*(?:[-*•]\s*)?(?:☑|☒|✅|✔|\[[xX]\])\s*(.*)$""")

    /** Turns checklist lines of shared text (Google Keep: "☐ item") into Markdown task items. */
    fun fromShared(text: String): String = text.replace("\r\n", "\n").lines().joinToString("\n") { line ->
        SHARED_DONE.matchEntire(line)?.let { "- [x] ${it.groupValues[1]}" }
            ?: SHARED_OPEN.matchEntire(line)?.let { "- [ ] ${it.groupValues[1]}" }
            ?: line
    }

    fun parse(body: String): BodyParts {
        val lines = body.split("\n")
        val first = lines.indexOfFirst { CHECK_REGEX.matches(it) }
        if (first < 0) return BodyParts(body.trim('\n'), emptyList(), "")

        val items = ArrayList<CheckItem>()
        var i = first
        var pendingBlank = 0
        while (i < lines.size) {
            val line = lines[i]
            val m = CHECK_REGEX.matchEntire(line)
            if (m != null) {
                items.add(CheckItem(ItemIds.next(), m.groupValues[2], m.groupValues[1] != " "))
                pendingBlank = 0
            } else if (line.isBlank()) {
                pendingBlank++
            } else {
                break
            }
            i++
        }
        val before = lines.subList(0, first).joinToString("\n").trim('\n')
        val after = lines.subList(i, lines.size).joinToString("\n").trim('\n')
        return BodyParts(before, items, after)
    }

    fun build(parts: BodyParts): String {
        // Checked items always sit at the bottom of the list, like in Keep.
        val ordered = parts.items.filter { !it.checked } + parts.items.filter { it.checked }
        val list = ordered.joinToString("\n") { "- [${if (it.checked) "x" else " "}] ${it.text}".trimEnd() }
        return listOf(parts.before.trim('\n'), list, parts.after.trim('\n'))
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }
}

/** Lower-case and accent-free, for search. */
fun String.fold(): String =
    Normalizer.normalize(this.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
