package app.memento

import app.memento.agenda.AgendaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** Paths from synced frontmatter never reach outside the notes folder, and a note only deletes what it owns. */
class SafePathTest {
    @get:Rule val tmp = TemporaryFolder()

    private val crafted = listOf(
        "../outside.txt", "/sdcard/x", "attachments/../../x", "C:\\x", "C:/x", "\\\\server\\share\\x",
        "attachments\\x.jpg", "attachments/x\u0000.jpg", "", "attachments//x", "./x", "attachments/.",
        "content://evil/x", "attachments/x.jpg:stream", "attachments/.x.jpg.synctmp-1234",
    )

    @Test
    fun refusesWhatLeavesTheFolder() {
        crafted.forEach { assertNull(it, SafePath.resolve(it)) }
        assertNull(SafePath.resolve(null))
    }

    @Test
    fun acceptsOrdinaryPaths() {
        listOf("courses-abc123.md", "attachments/photo-abc.jpg", "drawings/dessin-abc.svg", "activities/run-x.md", "agenda/2026/a-b.md", ".syncignore")
            .forEach { assertEquals(it, SafePath.resolve(it)) }
    }

    @Test
    fun ownsOnlyFilesDirectlyUnderAttachmentsOrDrawings() {
        assertTrue(SafePath.owned("attachments/photo-abc.jpg"))
        assertTrue(SafePath.owned("drawings/dessin-abc.svg"))
        listOf("courses-abc123.md", "contacts/bob-abc.md", "attachments/sub/x.jpg", "attachments/", "attachments/../x.md", "attachmentsx/y")
            .forEach { assertFalse(it, SafePath.owned(it)) }
        crafted.forEach { assertFalse(it, SafePath.owned(it)) }
    }

    private fun folder(): Pair<File, FileFs> {
        val base = tmp.newFolder("base")
        val root = File(base, "notes").apply { mkdirs() }
        File(base, "outside.txt").writeText("outside")
        return base to FileFs(root)
    }

    @Test
    fun fileFsRefusesCraftedPaths() {
        val (base, fs) = folder()
        assertNull(fs.readBytes("../outside.txt"))
        assertFalse(fs.exists("../outside.txt"))
        fs.delete("../outside.txt")
        fs.delete(File(base, "outside.txt").absolutePath)
        assertTrue(File(base, "outside.txt").exists())
        assertTrue(runCatching { fs.writeText("../outside.txt", "x") }.isFailure)
        assertEquals("outside", File(base, "outside.txt").readText())
        assertNull(fs.fileOf("attachments/../../outside.txt"))
    }

    @Test
    fun fileFsRefusesSymlinksLeadingOut() {
        val (base, fs) = folder()
        val attachments = File(fs.root, "attachments").apply { mkdirs() }
        Files.createSymbolicLink(File(attachments, "link.txt").toPath(), File(base, "outside.txt").toPath())
        Files.createSymbolicLink(File(fs.root, "drawings").toPath(), base.toPath())
        assertNull(fs.readBytes("attachments/link.txt"))
        assertNull(fs.readBytes("drawings/outside.txt"))
        fs.delete("attachments/link.txt")
        fs.delete("drawings/outside.txt")
        assertTrue(runCatching { fs.writeText("drawings/outside.txt", "x") }.isFailure)
        assertEquals("outside", File(base, "outside.txt").readText())
        // a symlink staying inside is fine
        fs.writeText("attachments/real.txt", "in")
        Files.createSymbolicLink(File(attachments, "inner.txt").toPath(), File(attachments, "real.txt").toPath())
        assertEquals("in", fs.readText("attachments/inner.txt"))
    }

    @Test
    fun deletingACraftedNoteDeletesOnlyItsOwnFiles() {
        val (base, fs) = folder()
        val other = Note(path = "other-abc123.md", id = "abc123", title = "Other", created = 1, updated = 1, attachments = listOf("attachments/shared.jpg"))
        val evil = Note(
            path = "evil-def456.md", id = "def456", title = "Evil", created = 1, updated = 1, pdf = "../outside.txt",
            attachments = listOf(
                "/sdcard/x", "attachments/../../outside.txt", "C:\\x", "other-abc123.md", "contacts/bob-abc.md",
                "attachments/shared.jpg", "attachments/mine.jpg",
            ),
        )
        listOf("other-abc123.md", "contacts/bob-abc.md", "attachments/shared.jpg", "attachments/mine.jpg").forEach { fs.writeText(it, it) }
        val owned = NoteRepository.ownedFiles(evil, listOf(other, evil))
        assertEquals(listOf("attachments/mine.jpg"), owned)
        owned.forEach { fs.delete(it) }
        assertTrue(File(base, "outside.txt").exists())
        assertTrue(fs.exists("other-abc123.md"))
        assertTrue(fs.exists("contacts/bob-abc.md"))
        assertTrue(fs.exists("attachments/shared.jpg"))
        assertFalse(fs.exists("attachments/mine.jpg"))
    }

    @Test
    fun deletingACraftedEventDeletesOnlyItsOwnFiles() {
        val (base, fs) = folder()
        val note = Note(path = "note-abc123.md", id = "abc123", title = "Note", created = 1, updated = 1, attachments = listOf("attachments/shared.jpg"))
        listOf("note-abc123.md", "attachments/shared.jpg", "attachments/mine.jpg").forEach { fs.writeText(it, it) }
        fs.writeText(
            "agenda/2026/evil-ex0001.md",
            """
            ---
            id: ex0001
            title: Evil
            created: 2026-10-01T10:00:00+02:00
            updated: 2026-10-01T10:00:00+02:00
            start: 2026-11-05T08:00
            pdf: ../outside.txt
            attachments: [note-abc123.md, attachments/shared.jpg, attachments/mine.jpg, "attachments/../../outside.txt"]
            ---
            """.trimIndent() + "\n",
        )
        val store = AgendaStore(fs) { listOf(note) }
        val event = store.scan().events.single()
        store.delete(event)
        assertFalse(fs.exists("agenda/2026/evil-ex0001.md"))
        assertFalse(fs.exists("attachments/mine.jpg"))
        assertTrue(fs.exists("attachments/shared.jpg"))
        assertTrue(fs.exists("note-abc123.md"))
        assertTrue(File(base, "outside.txt").exists())
    }
}
