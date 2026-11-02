package app.scriptorium

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.Modifier
import app.scriptorium.ui.AgendaScreen
import app.scriptorium.ui.ContactsScreen
import app.scriptorium.ui.LegacyImportDialog
import app.scriptorium.ui.ModuleBar
import app.scriptorium.map.MapHost
import app.scriptorium.map.MapRuntime
import app.scriptorium.ui.ActivitiesScreen
import app.scriptorium.ui.isDarkTheme
import kotlinx.coroutines.delay
import app.scriptorium.ui.ActivityDetailScreen
import app.scriptorium.ui.DrawingScreen
import app.scriptorium.ui.RecordScreen
import app.scriptorium.ui.EditorScreen
import app.scriptorium.ui.ScriptoriumTheme
import app.scriptorium.ui.ListScreen
import app.scriptorium.ui.PdfViewerScreen
import app.scriptorium.ui.PermissionScreen
import app.scriptorium.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MapRuntime.init(this)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (handleAgendaIntent(intent)) {
            intent?.action = null // not again on configuration changes
            return
        }
        app.scriptorium.ui.vcardUri(intent)?.let { uri ->
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
    private fun handleAgendaIntent(intent: Intent?): Boolean {
        intent ?: return false
        when (intent.action) {
            ACTION_OPEN_ENTRY -> {
                app.scriptorium.reminders.Reminders.dismiss(this, intent.getStringExtra(app.scriptorium.reminders.Reminders.EXTRA_NOTIFY_KEY))
                vm.agenda.openExternal(
                    intent.getStringExtra(app.scriptorium.reminders.Reminders.EXTRA_KIND),
                    intent.getStringExtra(app.scriptorium.reminders.Reminders.EXTRA_TARGET),
                    intent.getLongExtra(app.scriptorium.reminders.Reminders.EXTRA_START, 0L),
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
        val uris = app.scriptorium.ui.calendarUris(intent)
        if (uris.isEmpty()) return false
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

        ScriptoriumTheme(vm.theme) {
            val dark = isDarkTheme()
            LaunchedEffect(Unit) {
                // the map page loads once in the background, so the first map opens at once
                delay(1500)
                MapHost.warmUp(applicationContext, dark, vm.settings.map3d)
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
                val module = vm.screen.module
                if (module != null || vm.screen == Screen.SETTINGS) {
                    Column(Modifier.fillMaxSize()) {
                        // The bar takes the navigation-bar inset; screens above must not pad for it again.
                        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) { Screens() }
                        ModuleBar(vm.visibleModules, module, onSelect = { vm.openModule(it) }, onSettings = { vm.openSettings() })
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
            Screen.CONTACT -> app.scriptorium.ui.ContactDetailScreen(vm)
            Screen.CONTACT_EDIT -> app.scriptorium.ui.ContactEditScreen(vm)
            Screen.CONTACT_DUPLICATES -> app.scriptorium.ui.ContactDuplicatesScreen(vm)
            Screen.EVENT -> app.scriptorium.ui.EventDetailScreen(vm)
            Screen.EVENT_EDIT -> vm.agenda.editor?.let { app.scriptorium.ui.EventEditorScreen(vm, it) }
            Screen.AGENDA_CALENDARS -> app.scriptorium.ui.CalendarsScreen(vm)
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
        const val ACTION_OPEN_ENTRY = "app.scriptorium.action.OPEN_ENTRY"
        const val ACTION_AGENDA_TODAY = "app.scriptorium.action.AGENDA_TODAY"
        const val ACTION_NEW_EVENT = "app.scriptorium.action.NEW_EVENT"
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
