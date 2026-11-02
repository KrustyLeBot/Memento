package app.memento

import org.junit.Assert.assertEquals
import org.junit.Test

/** Deleting a sync conflict copy (or its original) must leave the files the other still lists (NoteRepository.delete). */
class SharedAttachmentsTest {
    private val original = Note(
        path = "courses-abc123.md", id = "abc123", title = "Courses", created = 1, updated = 1,
        pdf = "attachments/scan-abc.pdf", attachments = listOf("attachments/photo-abc.jpg", "attachments/ticket-abc.pdf"),
    )
    private val copy = original.copy(
        path = "courses-abc123.md.conflict1", conflict = true, attachments = listOf("attachments/photo-abc.jpg", "attachments/new-abc.jpg"),
    )

    @Test
    fun aConflictCopyOwnsOnlyWhatTheOriginalDoesNotList() {
        assertEquals(listOf("attachments/new-abc.jpg"), NoteRepository.ownedFiles(copy, listOf(original, copy)))
        assertEquals(listOf("attachments/ticket-abc.pdf"), NoteRepository.ownedFiles(original, listOf(original, copy)))
    }

    @Test
    fun theLastNoteListingAFileOwnsIt() {
        assertEquals(
            listOf("attachments/scan-abc.pdf", "attachments/photo-abc.jpg", "attachments/ticket-abc.pdf"),
            NoteRepository.ownedFiles(original, listOf(original)),
        )
    }
}
