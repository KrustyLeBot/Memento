package app.memento

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.Modifier
import app.memento.ui.AgendaScreen
import app.memento.ui.ContactsScreen
import app.memento.ui.LegacyImportDialog
import app.memento.ui.ModuleBar
import app.memento.map.MapHost
import app.memento.map.MapRuntime
import app.memento.ui.ActivitiesScreen
import app.memento.ui.isDarkTheme
import kotlinx.coroutines.delay
import app.memento.ui.ActivityDetailScreen
import app.memento.ui.DrawingScreen
import app.memento.ui.RecordScreen
import app.memento.ui.EditorScreen
import app.memento.ui.MementoTheme
import app.memento.ui.ListScreen
import app.memento.ui.PdfViewerScreen
import app.memento.ui.PermissionScreen
import app.memento.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MapRuntime.init(this)
        if (savedInstanceState == null) {
            // started just for another app's contact action: done, it hands back to that app (like the stock editor)
            if (handleContactRequest(intent)) vm.contacts.requests.finishWhenDone = true
            else handleIntent(intent, coldStart = true)
        }
        setContent { App() }
    }

    /** "Créer un contact", "Ajouter aux contacts", a contact opened from another app (see contacts/ContactRequestFlow). */
    private fun handleContactRequest(intent: Intent?): Boolean {
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return false
        val request = app.memento.contacts.ContactRequestFlow.request(intent) ?: return false
        vm.leaveEditors()
        vm.openModule(Module.CONTACTS)
        vm.contacts.requests.handle(request)
        intent.action = null // not again on configuration changes
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!handleContactRequest(intent)) handleIntent(intent)
    }

    /**
     * At a [coldStart], the screen behind the one an intent opens was never seen: back from it goes to the module
     * root of what was opened (the agenda for an event), not to the module the app happened to start on.
     */
    private fun handleIntent(intent: Intent?, coldStart: Boolean = false) {
        // Reopened from the recents after the task was left with back: Android hands the first intent again
        // (the shared text, the .ics...), which was already handled.
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (intent.action == ACTION_OPEN_RECORD || intent.action == ACTION_MAP_DOWNLOADS) {
            vm.leaveEditors()
            if (coldStart) vm.openedFromOutside()
            vm.openRecord()
            // the record screen lists the offline maps ("Itinéraires préparés") and their downloads
            if (intent.action == ACTION_MAP_DOWNLOADS) vm.showPreparedRoutes = true
            intent.action = null
            return
        }
        if (handleAgendaIntent(intent, coldStart)) {
            intent?.action = null // not again on configuration changes
            return
        }
        app.memento.ui.vcardUri(intent)?.let { uri ->
            vm.leaveEditors()
            vm.contacts.startImport(uri)
            intent?.action = null
            return
        }
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            vm.handleShare(
                intent.getStringExtra(Intent.EXTRA_SUBJECT),
                intent.getStringExtra(Intent.EXTRA_TEXT),
            )
            intent.action = null // do not recreate the note on configuration changes
        }
    }

    /** Reminder notifications, widget rows and buttons, and `.ics` files opened or shared from other apps. */
    private fun handleAgendaIntent(intent: Intent?, coldStart: Boolean): Boolean {
        intent ?: return false
        if (intent.action in listOf(ACTION_OPEN_ENTRY, ACTION_AGENDA_TODAY, ACTION_NEW_EVENT)) vm.leaveEditors()
        when (intent.action) {
            ACTION_OPEN_ENTRY -> {
                app.memento.reminders.Reminders.dismiss(this, intent.getStringExtra(app.memento.reminders.Reminders.EXTRA_NOTIFY_KEY))
                if (coldStart) vm.openedFromOutside()
                vm.agenda.openExternal(
                    intent.getStringExtra(app.memento.reminders.Reminders.EXTRA_KIND),
                    intent.getStringExtra(app.memento.reminders.Reminders.EXTRA_TARGET),
                    intent.getLongExtra(app.memento.reminders.Reminders.EXTRA_START, 0L),
                )
                return true
            }
            ACTION_AGENDA_TODAY -> {
                vm.openModule(Module.AGENDA)
                vm.agenda.goToday()
                return true
            }
            ACTION_NEW_EVENT -> {
                vm.openModule(Module.AGENDA)
                vm.agenda.newEvent(java.time.LocalDate.now())
                return true
            }
        }
        val uris = app.memento.ui.calendarUris(intent)
        if (uris.isEmpty()) return false
        vm.leaveEditors()
        vm.openModule(Module.AGENDA)
        vm.agenda.startImport(uris)
        return true
    }

    private var pickTree: androidx.activity.result.ActivityResultLauncher<Uri?>? = null
    private var scanner: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>? = null

    @Composable
    private fun App() {
        pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                vm.pickedTree(uri)
                if (!vm.storageReady) vm.confirmStorageChange(move = false)
            }
        }
        val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            vm.onResume()
        }
        scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val pages = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages.orEmpty()
                vm.saveScan(pages.map { it.imageUri })
            }
        }

        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> vm.onResume()
                    Lifecycle.Event.ON_PAUSE -> vm.onPause()
                    else -> {}
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(Unit) { vm.updates.checkOnOpen() }
        LaunchedEffect(vm.contacts.finishRequested) {
            if (!vm.contacts.finishRequested) return@LaunchedEffect
            vm.contacts.finishRequested = false
            if (vm.contacts.requests.finishWhenDone) finish()
        }

        MementoTheme(vm.theme) {
            val dark = isDarkTheme()
            LaunchedEffect(dark) {
                // The bar icons follow the app's theme (Paramètres → Thème), not only the phone's: dark icons on
                // the dark theme were invisible when the two differ.
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(bars, bars)
            }
            LaunchedEffect(Unit) {
                // the map page loads once in the background, so the first map opens at once
                delay(1500)
                // only the Activités module shows maps: hidden, the WebView is not worth its memory
                if (Module.ACTIVITIES !in vm.hiddenModules) MapHost.warmUp(applicationContext, dark, vm.settings.map3d)
            }
            BackHandler(enabled = vm.screen != Screen.LIST || vm.query.isNotEmpty() || vm.labelFilter != null) {
                vm.back()
            }
            if (!vm.storageReady) {
                PermissionScreen(
                    storage = vm.settings.storage,
                    onGrant = { requestFileAccess { legacyPermission.launch(it) } },
                    onPickFolder = { pickTree?.launch(null) },
                )
            } else {
                LegacyImportDialog(vm)
                app.memento.ui.ContactRequestDialogs(vm)
                val module = vm.screen.module
                if (module != null || vm.screen == Screen.SETTINGS) {
                    // The keyboard (search fields of the lists) lifts the screen and hides the bar, so the
                    // results and the FAB stay above it.
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    val typing = WindowInsets.isImeVisible
                    Column(Modifier.fillMaxSize().imePadding()) {
                        // The bar takes the navigation-bar inset; screens above must not pad for it again.
                        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) { Screens() }
                        // a hidden module opened by a widget or a notification shows in the bar while it is open
                        val modules = Module.entries.filter { it !in vm.hiddenModules || it == module }
                        if (!typing) ModuleBar(modules, module, onSelect = { vm.openModule(it) }, onSettings = { vm.openSettings() })
                    }
                } else {
                    Screens()
                }
            }
        }
    }

    @Composable
    private fun Screens() {
        when (vm.screen) {
            Screen.LIST -> ListScreen(vm, onScan = { startScan { scanner?.launch(it) } })
            Screen.EDITOR -> vm.editor?.let { EditorScreen(vm, it) }
            Screen.SETTINGS -> SettingsScreen(vm, onPickFolder = { pickTree?.launch(null) })
            Screen.PDF -> vm.viewingPdf?.let { PdfViewerScreen(vm, it) }
            Screen.ACTIVITIES -> ActivitiesScreen(vm)
            Screen.ACTIVITY -> vm.activities.detail?.let { ActivityDetailScreen(vm, it) }
            Screen.RECORD -> RecordScreen(vm)
            Screen.DRAWING -> vm.drawing?.let { DrawingScreen(vm, it) }
            Screen.AGENDA -> AgendaScreen(vm)
            Screen.CONTACTS -> ContactsScreen(vm)
            Screen.CONTACT -> app.memento.ui.ContactDetailScreen(vm)
            Screen.CONTACT_EDIT -> app.memento.ui.ContactEditScreen(vm)
            Screen.CONTACT_DUPLICATES -> app.memento.ui.ContactDuplicatesScreen(vm)
            Screen.EVENT -> app.memento.ui.EventDetailScreen(vm)
            Screen.EVENT_EDIT -> vm.agenda.editor?.let { app.memento.ui.EventEditorScreen(vm, it) }
            Screen.AGENDA_CALENDARS -> app.memento.ui.CalendarsScreen(vm)
        }
    }

    /** Google's document scanner: camera, automatic page corners, several pages. Photos only, no gallery. */
    private fun startScan(launch: (IntentSenderRequest) -> Unit) {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(30)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { vm.snack = Snack("Scanner indisponible : ${it.message}") }
    }

    companion object {
        const val ACTION_OPEN_ENTRY = "app.memento.action.OPEN_ENTRY"
        const val ACTION_AGENDA_TODAY = "app.memento.action.AGENDA_TODAY"
        const val ACTION_NEW_EVENT = "app.memento.action.NEW_EVENT"
        const val ACTION_OPEN_RECORD = "app.memento.action.OPEN_RECORD"
        const val ACTION_MAP_DOWNLOADS = "app.memento.action.MAP_DOWNLOADS"
    }

    private fun requestFileAccess(legacy: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
            )
        } else {
            legacy(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}
