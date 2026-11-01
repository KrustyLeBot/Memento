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
import app.scriptorium.ui.ActivitiesScreen
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
        if (savedInstanceState == null) handleIntent(intent)
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            vm.handleShare(
                intent.getStringExtra(Intent.EXTRA_SUBJECT),
                intent.getStringExtra(Intent.EXTRA_TEXT),
            )
            intent.action = null // do not recreate the note on configuration changes
        }
    }

    @Composable
    private fun App() {
        val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
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
        val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
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
            BackHandler(enabled = vm.screen != Screen.LIST || vm.query.isNotEmpty() || vm.labelFilter != null) {
                vm.back()
            }
            if (!vm.storageReady) {
                PermissionScreen(
                    storage = vm.settings.storage,
                    onGrant = { requestFileAccess { legacyPermission.launch(it) } },
                    onPickFolder = { pickTree.launch(null) },
                )
            } else {
                when (vm.screen) {
                    Screen.LIST -> ListScreen(vm, onScan = { startScan { scanner.launch(it) } })
                    Screen.EDITOR -> vm.editor?.let { EditorScreen(vm, it) }
                    Screen.SETTINGS -> SettingsScreen(vm, onPickFolder = { pickTree.launch(null) })
                    Screen.PDF -> vm.viewingPdf?.let { PdfViewerScreen(vm, it) }
                    Screen.ACTIVITIES -> ActivitiesScreen(vm)
                    Screen.ACTIVITY -> vm.activities.detail?.let { ActivityDetailScreen(vm, it) }
                    Screen.RECORD -> RecordScreen(vm)
                    Screen.DRAWING -> vm.drawing?.let { DrawingScreen(vm, it) }
                }
            }
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
