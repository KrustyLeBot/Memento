package app.memento.agenda

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import app.memento.NoteRepository
import app.memento.Settings
import app.memento.StorageConfig
import app.memento.contacts.Contact
import app.memento.contacts.ContactBook

/**
 * What the agenda reads, for the parts of the app that run without the screens (reminders, widgets): the
 * app publishes its in-memory sources on every change; with no app running (boot, widget update, alarm) the
 * folder is read here, on the caller's thread (never the main thread). Nothing is written.
 */
object AgendaData {
    private class Snapshot(val sources: AgendaSources, val at: Long)

    @Volatile private var published: Snapshot? = null
    private val lock = Any()
    private var repo: NoteRepository? = null
    private var book: ContactBook? = null
    private var repoStorage: String? = null

    /** Called by the app whenever events, notes, activities, contacts or calendars changed. */
    fun publish(sources: AgendaSources) {
        published = Snapshot(sources, System.currentTimeMillis())
    }

    /** The next [load] reads the folder again (the widget's ⟳). */
    fun invalidate() {
        published = null
    }

    fun birthdaysOf(contacts: List<Contact>): List<Birthday> {
        val paths = contacts.associate { it.id to it.path }
        return app.memento.contacts.birthdays(contacts).map { Birthday(it.contactId, it.name, it.birthday.encode(), paths[it.contactId]) }
    }

    fun sourcesOf(repo: NoteRepository, contacts: List<Contact>): AgendaSources = AgendaSources(
        events = repo.agenda.value.events, notes = repo.notes.value, activities = repo.activities.value,
        birthdays = birthdaysOf(contacts), settings = repo.calendars.value,
    )

    /** The app may read its folder (same test as the app's permission screen). */
    fun storageReady(context: Context, settings: Settings): Boolean = when (val c = settings.storage) {
        StorageConfig.Default ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
            else ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        is StorageConfig.Tree -> context.contentResolver.persistedUriPermissions.any { it.uri == c.uri && it.isReadPermission }
    }

    /**
     * The sources: the published ones when younger than [maxAgeMs], else read from the folder (blocking; only
     * changed files are parsed again). Null when the folder cannot be read (no permission yet).
     */
    fun load(context: Context, maxAgeMs: Long = 15 * 60_000L): AgendaSources? {
        published?.let { if (System.currentTimeMillis() - it.at < maxAgeMs) return it.sources }
        synchronized(lock) {
            published?.let { if (System.currentTimeMillis() - it.at < maxAgeMs) return it.sources }
            val app = context.applicationContext
            val settings = Settings(app)
            if (!storageReady(app, settings)) return null
            val key = settings.storageUri ?: "default"
            if (repo == null || repoStorage != key) {
                val r = NoteRepository(app, settings)
                repo = r
                book = ContactBook { r.fs }
                repoStorage = key
            }
            val r = repo!!
            r.scan()
            val b = book!!
            b.scan()
            val s = sourcesOf(r, b.contacts.value)
            published = Snapshot(s, System.currentTimeMillis())
            return s
        }
    }
}
