package app.memento.agenda

/** A link of an event's body as the detail lists it: [label] is what the row shows. */
data class LinkItem(val url: String, val label: String)

/**
 * The http(s) links of a text, same rules as Python's `core/agenda/links.py`: `<…>`, quotes and brackets end a
 * link, trailing punctuation and an unbalanced `)` are not part of it, duplicates are dropped, at most [MAX].
 */
object Links {
    const val MAX = 10
    private val URL = Regex("""https?://[^\s<>"'\[\]{}]+""", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!?*"
    private val MEETINGS = listOf(
        "meet.proton.me" to "Proton Meet", "meet.google.com" to "Google Meet", "teams.microsoft.com" to "Microsoft Teams",
        "teams.live.com" to "Microsoft Teams", "zoom.us" to "Zoom",
    )

    private fun clean(raw: String): String {
        var s = raw
        while (true) {
            s = s.trimEnd { it in TRAILING }
            if (s.endsWith(")") && s.count { it == '(' } < s.count { it == ')' }) s = s.dropLast(1) else return s
        }
    }

    /** Each link of [text] with its range, in order (duplicates kept): what the detail makes clickable. */
    fun spans(text: String): List<Pair<IntRange, String>> = URL.findAll(text).mapNotNull { m ->
        val url = clean(m.value)
        if (url.substringAfter("://").isEmpty()) null else (m.range.first until m.range.first + url.length) to url
    }.toList()

    fun host(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@').substringBefore(':').lowercase()

    /** The service of a video-meeting link (Proton Meet, Google Meet, Teams, Zoom), else null. */
    fun meetingService(url: String): String? {
        val host = host(url)
        return MEETINGS.firstOrNull { (h, _) -> host == h || host.endsWith(".$h") }?.second
    }

    /** The first video-meeting link of [text], else null. */
    fun meetingLink(text: String): String? = spans(text).map { it.second }.firstOrNull { meetingService(it) != null }

    /** "Rejoindre la visio (Proton Meet)", else the domain and the path, shortened: "exemple.fr/agenda/2026/…". */
    fun label(url: String): String {
        meetingService(url)?.let { return "Rejoindre la visio ($it)" }
        val host = host(url).removePrefix("www.")
        val path = url.substringAfter("://").let { r -> r.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) "" else r.substring(it) } }
            .trimEnd('/')
        val shown = if (path.length > 30) path.take(29) + "…" else path
        return host + shown
    }

    /** The links of [text], the first meeting link first, each once, at most [max]. */
    fun extract(text: String, max: Int = MAX): List<LinkItem> {
        val urls = LinkedHashSet<String>()
        meetingLink(text)?.let { urls.add(it) }
        spans(text).forEach { urls.add(it.second) }
        return urls.take(max).map { LinkItem(it, label(it)) }
    }
}
