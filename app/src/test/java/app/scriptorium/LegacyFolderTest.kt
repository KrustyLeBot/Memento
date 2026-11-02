package app.scriptorium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LegacyFolderTest {
    private fun tmp(): File = Files.createTempDirectory("legacy").toFile()

    @Test
    fun copiesThenDeletes() {
        val root = tmp()
        val old = File(root, "Griffonote").apply { mkdirs() }
        val new = File(root, "Scriptorium")
        File(old, "courses-abc123.md").writeText("---\nid: abc123\n---\nlait")
        File(old, "activities").mkdirs()
        File(old, "activities/sortie-def456.md").writeText("x")
        File(old, ".courses-abc123.md.synctmp-1").writeText("partial")
        assertTrue(LegacyFolder.needsImport(old, new))
        val s = LegacyFolder.copy(old, new)
        assertEquals(2, s.files)
        assertTrue(File(new, "activities/sortie-def456.md").isFile)
        assertFalse(File(new, ".courses-abc123.md.synctmp-1").exists())
        assertFalse(LegacyFolder.needsImport(old, new))
        LegacyFolder.deleteIfCopied(old, new)
        assertFalse(old.exists())
    }

    @Test
    fun emptyFoldersAndSyncLeftoversDoNotCountAsImported() {
        val root = tmp()
        val old = File(root, "Griffonote").apply { mkdirs() }
        val new = File(root, "Scriptorium")
        assertFalse(LegacyFolder.needsImport(old, new)) // nothing to bring
        File(old, "a-abc123.md").writeText("x")
        // the app makes the folder (and the watch its subfolders) before the offer; a sync may leave a temp file
        File(new, "agenda/2026").mkdirs()
        File(new, ".a-abc123.md.synctmp-9").writeText("partial")
        assertTrue(LegacyFolder.needsImport(old, new))
        File(new, "agenda/2026/b-def456.md").writeText("y")
        assertFalse(LegacyFolder.needsImport(old, new))
    }

    @Test
    fun neverOverwritesAndRefusesIncompleteDelete() {
        val root = tmp()
        val old = File(root, "Griffonote").apply { mkdirs() }
        val new = File(root, "Scriptorium").apply { mkdirs() }
        File(old, "a.md").writeText("old")
        File(old, "b.md").writeText("bb")
        File(new, "b.md").writeText("b")
        val failed = runCatching { LegacyFolder.copy(old, new) }.isFailure
        assertTrue(failed)
        assertEquals("b", File(new, "b.md").readText())
        assertTrue(runCatching { LegacyFolder.deleteIfCopied(old, new) }.isFailure)
        assertTrue(old.exists())
    }
}
