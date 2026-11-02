package app.memento.ui.screenshots

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.memento.LegacyImport
import app.memento.MainViewModel
import app.memento.Module
import app.memento.Screen
import app.memento.Snack
import app.memento.SnackDuration
import app.memento.ThemeMode
import app.memento.activity.Gpx
import app.memento.activity.Sport
import app.memento.agenda.AgendaView
import app.memento.module
import app.memento.suite.Release
import app.memento.suite.UpdateState
import app.memento.ui.ActivitiesScreen
import app.memento.ui.ActivityDetailScreen
import app.memento.ui.AgendaScreen
import app.memento.ui.AppFrame
import app.memento.ui.CalendarsScreen
import app.memento.ui.ContactDetailScreen
import app.memento.ui.ContactDuplicatesScreen
import app.memento.ui.ContactEditScreen
import app.memento.ui.ContactsScreen
import app.memento.ui.EditorScreen
import app.memento.ui.EventDetailScreen
import app.memento.ui.EventEditorScreen
import app.memento.ui.LegacyImportDialog
import app.memento.ui.ListScreen
import app.memento.ui.ModuleBar
import app.memento.ui.RecordScreen
import app.memento.ui.MementoTheme
import app.memento.ui.SettingsScreen
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Renders every screen of the app with synthetic data, dark and light, at a 412×915 dp phone size, to
 * look at them (contrast, clipping, insets). Not a regression test: it only writes PNGs.
 * Run: `./gradlew testDebugUnitTest -PuiAudit=/tmp/claude-0/ui-audit --tests '*ScreenshotAudit*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "fr-rFR-w412dp-h915dp-xhdpi")
class ScreenshotAudit {
    private val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<ComponentActivity>()

    /** createComposeRule launches a bare ComponentActivity, which the app's manifest does not declare. */
    @get:Rule
    val rules: TestRule = RuleChain.outerRule { base: Statement, _: Description ->
        object : Statement() {
            override fun evaluate() {
                val app = ApplicationProvider.getApplicationContext<Application>()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }.around(compose)

    private val out = File(System.getProperty("uiAudit") ?: "build/ui-audit")
    private lateinit var vm: MainViewModel

    @Before
    fun setUp() {
        out.mkdirs()
        val app = ApplicationProvider.getApplicationContext<Application>()
        // Android 10: the storage check is the plain WRITE_EXTERNAL_STORAGE permission (Robolectric cannot fake
        // isExternalStorageManager()); the screens are the same.
        shadowOf(app).grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        android.provider.Settings.System.putString(app.contentResolver, android.provider.Settings.System.TIME_12_24, "24")
        AuditData.seed()
        vm = MainViewModel(ApplicationProvider.getApplicationContext())
        settle { vm.repo.scans.value > 0 && vm.contacts.contacts.value.isNotEmpty() }
    }

    @After
    fun tearDown() {
        vm.theme = ThemeMode.SYSTEM
    }

    private fun settle(timeoutMs: Long = 5000, done: () -> Boolean = { true }) {
        val end = System.currentTimeMillis() + timeoutMs
        do {
            ShadowLooper.idleMainLooper()
            Thread.sleep(30)
            ShadowLooper.idleMainLooper()
        } while (!done() && System.currentTimeMillis() < end)
    }

    private fun show(content: @Composable () -> Unit = { App(vm) }) {
        // manual clock: indeterminate progress bars never let compose go idle otherwise
        compose.mainClock.autoAdvance = false
        compose.setContent(content)
        idle()
    }

    private fun idle() {
        repeat(4) {
            settle(80)
            compose.mainClock.advanceTimeBy(400)
        }
    }

    private fun shot(name: String) {
        idle()
        captureScreenRoboImage(File(out, "$name.png").path)
    }

    /** Runs [setup] once (open a dialog, scroll…), then captures [name] in dark and in light. */
    private fun both(name: String, setup: () -> Unit = {}) {
        // an audit: a step that cannot be reached is reported, the other screens are still drawn
        runCatching(setup).onFailure { println("AUDIT $name: setup failed: $it"); return }
        idle()
        for (dark in listOf(true, false)) {
            vm.theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT
            shot(name + if (dark) "_dark" else "_light")
        }
    }

    private fun click(text: String) {
        compose.onAllNodesWithText(text).onFirst().performClick()
        idle()
    }

    private fun clickIcon(description: String) {
        compose.onAllNodesWithContentDescription(description).onFirst().performClick()
        idle()
    }

    /** Scrolls the screen's (first) scrollable by about two thirds of its height, slowly so it does not fling. */
    private fun page() {
        compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput {
            swipeUp(startY = bottom * 0.85f, endY = bottom * 0.2f, durationMillis = 1500)
        }
        idle()
    }

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        idle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> setPrivateState(owner: Any, name: String, value: T) {
        val f = owner.javaClass.getDeclaredField("$name\$delegate")
        f.isAccessible = true
        (f.get(owner) as MutableState<T>).value = value
    }

    // ---- notes ----

    @Test
    fun notes() {
        vm.screen = Screen.LIST
        show()
        vm.changeGridView(true)
        both("notes_grid")
        vm.changeGridView(false)
        both("notes_list")
        vm.changeGridView(true)
        setPrivateState(vm, "syncing", true)
        setPrivateState(vm.updates, "state", UpdateState.Available(Release(142, "", 12_400_000, "")))
        both("notes_banners")
        setPrivateState(vm, "syncing", false)
        setPrivateState(vm.updates, "state", UpdateState.Failed("Réseau injoignable", Release(142, "", 12_400_000, "")))
        vm.selectLabel("Perso")
        both("notes_label_failed")
        vm.selectLabel(null)
        setPrivateState(vm.updates, "state", UpdateState.Idle)
        snackShot("notes_snackbar", Snack("Note supprimée", undo = {}))
        vm.query = "zzz"
        both("notes_empty_search")
    }

    @Test
    fun editor() {
        show()
        val notes = vm.notes.value
        vm.openNote(notes.first { it.title == "Courses" })
        both("editor_checklist")
        vm.openNote(notes.first { it.title == "Réunion lundi" })
        both("editor_text_blue")
        vm.openNote(notes.first { it.title == "Lien utile" })
        both("editor_link_purple")
        vm.openNote(notes.first { it.title == "Idées cadeaux" })
        both("editor_red")
        both("editor_colors_dialog") { clickIcon("Couleur") }
        runCatching { click("OK") }
        both("editor_labels_dialog") { clickIcon("Libellés") }
        runCatching { click("OK") }
        both("editor_date_dialog") { clickIcon("Ajouter à l'agenda") }
        runCatching { back() }
        both("editor_attach_menu") { clickIcon("Joindre un fichier") }
        runCatching { back() }
        vm.openNote(notes.first { it.title == "Vacances" })
        both("editor_gray")
    }

    @Test
    fun drawing() {
        show()
        vm.openNote(vm.notes.value.first { it.title == "Courses" })
        idle()
        vm.newDrawing()
        both("drawing")
    }

    // ---- settings, bars, dialogs ----

    @Test
    fun settings() {
        vm.openSettings()
        show()
        both("settings_1")
        for (i in 2..6) both("settings_$i") { page() }
    }

    @Test
    fun permission() {
        show { MementoTheme(vm.theme) { app.memento.ui.PermissionScreen(app.memento.StorageConfig.Default, {}, {}) } }
        both("permission")
    }

    @Test
    fun dialogs() {
        vm.screen = Screen.LIST
        show()
        vm.legacyImport = LegacyImport.Offer(128, 34_000_000)
        both("dialog_legacy_import")
        vm.legacyImport = LegacyImport.Copying(40, 128)
        both("dialog_legacy_copying")
        vm.legacyImport = LegacyImport.Failed("Espace insuffisant")
        both("dialog_legacy_failed")
        vm.legacyImport = null
    }

    @Test
    fun confirmDialog() {
        show {
            MementoTheme(vm.theme) {
                app.memento.suite.ConfirmDialog("Supprimer cette note ?", "Elle part à la corbeille du dossier.", "Supprimer", {}, {}, destructive = true)
            }
        }
        both("dialog_confirm")
    }

    // ---- activities ----

    private fun seedActivities() {
        val start = System.currentTimeMillis() - 86_400_000L
        val walk = AuditData.walkGpx(start)
        vm.activities.saveNew(Gpx.read(walk), Sport.WALK, "Balade au Semnoz", walk.toByteArray(), "gpx")
        val ski = AuditData.skiGpx(start - 3 * 86_400_000L)
        vm.activities.saveNew(Gpx.read(ski), Sport.SKI, "Journée aux Arcs", ski.toByteArray(), "gpx")
        val bike = AuditData.walkGpx(start - 6 * 86_400_000L, 40)
        vm.activities.saveNew(Gpx.read(bike), Sport.BIKE, "Tour du lac", bike.toByteArray(), "gpx")
    }

    @Test
    fun activities() {
        seedActivities()
        vm.openModule(Module.ACTIVITIES)
        show()
        both("activities_list")
        val ski = vm.repo.activities.value.first { it.title == "Journée aux Arcs" }
        vm.openActivity(ski)
        settle { vm.activities.detail?.analysis != null }
        both("activity_ski_top")
        both("activity_ski_stats") { page() }
        both("activity_ski_charts") { page() }
        val walk = vm.repo.activities.value.first { it.title == "Balade au Semnoz" }
        vm.openActivity(walk)
        settle { vm.activities.detail?.note?.id == walk.id && vm.activities.detail?.analysis != null }
        both("activity_walk_stats") { page() }
        both("activity_walk_charts") { page(); page() }
        both("activity_edit_dialog") { clickIcon("Modifier") }
        runCatching { click("Annuler") }
        both("activity_delete_dialog") { clickIcon("Supprimer") }
    }

    @Test
    fun record() {
        vm.openRecord()
        show()
        both("record_chooser")
    }

    // ---- agenda ----

    @Test
    fun agenda() {
        vm.openModule(Module.AGENDA)
        show()
        for (v in AgendaView.entries) {
            vm.agenda.changeView(v)
            both("agenda_${v.name.lowercase()}")
        }
        vm.agenda.changeView(AgendaView.AGENDA)
        both("agenda_menu") { clickIcon("Menu") }
        runCatching { back() }
        both("agenda_search") { clickIcon("Rechercher"); vm.agenda.query = "stand" }
    }

    @Test
    fun eventScreens() {
        vm.openModule(Module.AGENDA)
        show()
        val e = vm.repo.agenda.value.events.first { it.title == "Yoga" }
        vm.agenda.open(entryFor(e.id, e.start.dateTime().atZone(ZoneId.systemDefault()).toInstant()))
        both("event_detail")
        vm.agenda.newEvent(LocalDate.now())
        both("event_editor_new")
        both("event_date_picker") { click(compose.onAllNodes(hasText("nov.", substring = true)).onFirst().fetchText()) }
        runCatching { click("Annuler") }
        both("event_alert_dialog") { click("Ajouter un rappel") }
        runCatching { click("Annuler") }
        vm.agenda.cancelEditor()
        idle()
        val series = vm.repo.agenda.value.events.first { it.title == "Stand-up" }
        vm.agenda.edit(entryFor(series.id, series.start.dateTime().atZone(ZoneId.systemDefault()).toInstant()), app.memento.agenda.SeriesScope.ALL)
        both("event_editor_series")
        both("event_repeat_dialog") { click("Du lundi au vendredi") }
        both("event_repeat_custom") { click("Personnalisé…") }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.fetchText(): String =
        fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }

    private fun entryFor(eventId: String, at: java.time.Instant): app.memento.agenda.Entry =
        app.memento.agenda.AgendaLayout.entryFor(vm.agenda.sources.value, eventId, at, java.time.Instant.now(), ZoneId.systemDefault())!!

    @Test
    fun agendaImportAndCalendars() {
        vm.openModule(Module.AGENDA)
        show()
        vm.agenda.startImport(listOf(Uri.fromFile(File("../testdata/agenda/google_perso.ics").absoluteFile)))
        settle { vm.agenda.importState is app.memento.agenda.IcsImportState.Preview }
        both("agenda_import_preview")
        vm.agenda.cancelImport()
        vm.screen = Screen.AGENDA_CALENDARS
        both("agenda_calendars")
        both("agenda_calendar_edit") { click("Travail") }
    }

    // ---- contacts ----

    @Test
    fun contacts() {
        vm.openModule(Module.CONTACTS)
        show()
        both("contacts_list")
        both("contacts_menu") { clickIcon("Menu") }
        runCatching { back() }
        val marie = vm.contacts.contacts.value.first { it.firstName == "Marie" && it.favorite }
        vm.contacts.open(marie)
        both("contact_detail")
        vm.contacts.edit(marie)
        both("contact_edit")
        both("contact_edit_label") { click("mobile") }
        runCatching { back() }
        vm.contacts.closeEditor(save = false)
        vm.contacts.openDuplicates()
        both("contacts_duplicates")
        vm.openModule(Module.CONTACTS)
        vm.contacts.startImport(Uri.fromFile(File("../testdata/contacts/google.vcf").absoluteFile))
        settle { vm.contacts.importState != null && vm.contacts.importState !is app.memento.contacts.ContactImportState.Reading }
        both("contacts_import_preview")
    }

    // ---- the app-level snackbar: same place on every screen, above the bottom bar, FABs lifted ----

    /** Posts [snack], captures it dark and light, then clears the queue for the next one. */
    private fun snackShot(name: String, snack: Snack) {
        both(name) { vm.say(snack) }
        clearSnacks()
    }

    private fun clearSnacks() {
        while (true) vm.snacks.finish(vm.snacks.current ?: break, actionPerformed = false)
        idle()
    }

    @Test
    fun snackbars() {
        seedActivities()
        vm.screen = Screen.LIST
        show()
        // the real path: a note deleted from its editor, which closes onto the list while the snack is posted
        vm.openNote(vm.notes.value.first { it.title == "Vacances" })
        idle()
        both("snack_notes_deleted") { vm.deleteEditorNote(); settle { vm.snacks.current != null } }
        clearSnacks()
        vm.openNote(vm.notes.value.first { it.title == "Courses" })
        snackShot("snack_editor", Snack("Pièce jointe supprimée", undo = {}))
        vm.closeEditor()
        vm.openModule(Module.AGENDA)
        snackShot("snack_agenda", Snack("Événement supprimé", undo = {}))
        vm.agenda.newEvent(LocalDate.now())
        snackShot("snack_event_editor", Snack("Donne un titre à l'événement", duration = SnackDuration.LONG))
        vm.agenda.cancelEditor()
        vm.openModule(Module.ACTIVITIES)
        snackShot("snack_activities", Snack("Import impossible : fichier illisible", duration = SnackDuration.LONG))
        vm.openModule(Module.CONTACTS)
        snackShot("snack_contacts", Snack("Contact supprimé", undo = {}))
        vm.openSettings()
        snackShot("snack_settings", Snack("Cache des cartes vidé", duration = SnackDuration.LONG))
    }

    // ---- the app frame ----

    @Composable
    private fun App(vm: MainViewModel) {
        MementoTheme(vm.theme) {
            LegacyImportDialog(vm)
            AppFrame(vm) { Screens(vm) }
        }
    }

    @Composable
    private fun Screens(vm: MainViewModel) {
        when (vm.screen) {
            Screen.LIST -> ListScreen(vm, onScan = {})
            Screen.EDITOR -> vm.editor?.let { EditorScreen(vm, it) }
            Screen.SETTINGS -> SettingsScreen(vm, onPickFolder = {})
            Screen.ACTIVITIES -> ActivitiesScreen(vm)
            Screen.ACTIVITY -> vm.activities.detail?.let { ActivityDetailScreen(vm, it) }
            Screen.RECORD -> RecordScreen(vm)
            Screen.AGENDA -> AgendaScreen(vm)
            Screen.CONTACTS -> ContactsScreen(vm)
            Screen.CONTACT -> ContactDetailScreen(vm)
            Screen.CONTACT_EDIT -> ContactEditScreen(vm)
            Screen.CONTACT_DUPLICATES -> ContactDuplicatesScreen(vm)
            Screen.EVENT -> EventDetailScreen(vm)
            Screen.EVENT_EDIT -> vm.agenda.editor?.let { EventEditorScreen(vm, it) }
            Screen.AGENDA_CALENDARS -> CalendarsScreen(vm)
            Screen.DRAWING -> vm.drawing?.let { app.memento.ui.DrawingScreen(vm, it) }
            Screen.PDF -> Unit
        }
    }
}
