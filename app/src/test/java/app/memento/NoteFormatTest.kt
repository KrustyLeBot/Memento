package app.memento

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteFormatTest {
    private val sample = """
        ---
        id: x81bq0
        title: Idées appli
        color: yellow
        pinned: true
        labels: [perso, dev]
        reminder: 2026-10-25T09:00:00+02:00
        created: 2026-10-23T14:30:00+02:00
        updated: 2026-10-23T15:02:11+02:00
        obsidian-tags:
          - a
          - b
        ---

        Texte libre avec un lien https://exemple.com

        - [ ] Item à faire
        - [x] Item fait

        ![](attachments/x81bq0-01.jpg)
    """.trimIndent() + "\n"

    @Test
    fun parsesKnownFields() {
        val n = NoteFormat.parse("idees-appli-x81bq0.md", sample, 0L)
        assertEquals("x81bq0", n.id)
        assertEquals("Idées appli", n.title)
        assertEquals("yellow", n.color)
        assertTrue(n.pinned)
        assertEquals(listOf("perso", "dev"), n.labels)
        assertEquals(NoteFormat.parseTime("2026-10-25T09:00:00+02:00"), n.reminder)
        assertFalse(n.conflict)
    }

    @Test
    fun roundTripKeepsUnknownFieldsAndBody() {
        val n = NoteFormat.parse("a.md", sample, 0L)
        val again = NoteFormat.parse("a.md", NoteFormat.serialize(n), 0L)
        assertEquals(n.copy(), again)
        assertEquals(listOf("obsidian-tags:", "  - a", "  - b"), again.extra)
        assertTrue(NoteFormat.serialize(n).contains("obsidian-tags:\n  - a\n  - b\n"))
    }

    @Test
    fun titleNeedingQuotesRoundTrips() {
        val base = NoteFormat.parse("a.md", sample, 0L)
        for (t in listOf("a: b", "# not a comment", "true", "42", "- dash", "say \"hi\"", "back\\slash", "")) {
            val n = base.copy(title = t, labels = listOf("x, y", "z"))
            val back = NoteFormat.parse("a.md", NoteFormat.serialize(n), 0L)
            assertEquals(t, back.title)
            assertEquals(listOf("x, y", "z"), back.labels)
        }
    }

    @Test
    fun fileWithoutFrontmatterIsAccepted() {
        val n = NoteFormat.parse("liste-courses.md", "Lait\nPain\n", 1000L)
        assertFalse(n.hasId)
        assertEquals("liste courses", n.title)
        assertEquals("Lait\nPain", n.body)
        assertEquals(6, n.id.length)
    }

    @Test
    fun conflictFilesAreFlagged() {
        val n = NoteFormat.parse("courses-k3f9a2.conflict-2.md", sample, 0L)
        assertTrue(n.conflict)
    }

    @Test
    fun bodySplitsAndRebuildsChecklist() {
        val parts = BodyFormat.parse("Intro\n\n- [x] fait\n- [ ] à faire\n\n![](a.png)")
        assertEquals("Intro", parts.before)
        assertEquals(listOf("fait", "à faire"), parts.items.map { it.text })
        assertEquals("![](a.png)", parts.after)
        // Checked items go to the bottom.
        assertEquals("Intro\n\n- [ ] à faire\n- [x] fait\n\n![](a.png)", BodyFormat.build(parts))
    }

    @Test
    fun slugAndFileName() {
        assertEquals("idees-appli", NoteFormat.slug("Idées  appli !"))
        assertEquals("note", NoteFormat.slug("???"))
        assertEquals("courses-k3f9a2.md", NoteFormat.fileNameFor("Courses", "k3f9a2"))
    }

    @Test
    fun sharedKeepChecklistBecomesTaskItems() {
        val shared = "Courses\n☐ Lait\n☑ Pain\n[ ] Oeufs\nTexte"
        val parts = BodyFormat.parse(BodyFormat.fromShared(shared))
        assertEquals("Courses", parts.before)
        assertEquals(listOf("Lait", "Pain", "Oeufs"), parts.items.map { it.text })
        assertEquals(listOf(false, true, false), parts.items.map { it.checked })
        assertEquals("Texte", parts.after)
    }

    @Test
    fun attachmentsRoundTrip() {
        val base = NoteFormat.parse("a.md", sample, 0L)
        assertTrue(base.attachments.isEmpty())
        val n = base.copy(attachments = listOf("attachments/photo-abc123.jpg", "attachments/facture, edf-k3f9a2.pdf"))
        val text = NoteFormat.serialize(n)
        assertTrue(text.contains("attachments: [attachments/photo-abc123.jpg, \"attachments/facture, edf-k3f9a2.pdf\"]\n"))
        assertEquals(n.attachments, NoteFormat.parse("a.md", text, 0L).attachments)
        val block = text.replace(Regex("attachments: \\[.*]"), "attachments:\n  - attachments/x-aaaaaa.png")
        assertEquals(listOf("attachments/x-aaaaaa.png"), NoteFormat.parse("a.md", block, 0L).attachments)
    }

    @Test
    fun attachmentPathKeepsAReadableNameAndExtension() {
        assertTrue(Regex("""attachments/facture-edf-[a-z0-9]{6}\.pdf""").matches(NoteFormat.attachmentPath("Facture EDF.PDF")))
        assertTrue(Regex("""attachments/img-2026-[a-z0-9]{6}\.jpg""").matches(NoteFormat.attachmentPath("/storage/IMG_2026.jpg")))
        assertTrue(Regex("""attachments/note-[a-z0-9]{6}""").matches(NoteFormat.attachmentPath("")))
    }
}
