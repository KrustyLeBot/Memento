package app.memento.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import app.memento.MainViewModel
import app.memento.Module
import app.memento.Screen
import app.memento.SnackQueue
import app.memento.module
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where the app-level snackbar goes. It sits right above the bottom bar on screen (the module bar, or a screen's
 * own toolbar marked with [snackbarAnchor]), else above the navigation bar and the keyboard; the floating buttons
 * marked with [liftAboveSnackbar] move up while it shows, as Material's FAB does. Same place on every screen.
 */
@Stable
class SnackbarPlacement {
    internal val anchors = mutableStateMapOf<Any, Float>()
    internal var height by mutableIntStateOf(0)
}

val LocalSnackbarPlacement = staticCompositionLocalOf { SnackbarPlacement() }

/** This bottom bar keeps the snackbar above it while it is on screen. */
fun Modifier.snackbarAnchor(): Modifier = composed {
    val placement = LocalSnackbarPlacement.current
    val token = remember { Any() }
    DisposableEffect(placement) { onDispose { placement.anchors.remove(token) } }
    onGloballyPositioned { placement.anchors[token] = it.positionInRoot().y }
}

/** Moves this floating button (or column of them) up by the snackbar's height while one shows. */
fun Modifier.liftAboveSnackbar(): Modifier = composed {
    val placement = LocalSnackbarPlacement.current
    val target = with(LocalDensity.current) { placement.height.toDp() }
    val lift by animateDpAsState(target, label = "snackbar lift")
    padding(bottom = lift)
}

/**
 * The app's frame: the screens, the module bar on the module roots and the settings, and the one snackbar of the
 * app drawn over all of them. [screens] draws the current screen.
 */
@Composable
fun AppFrame(vm: MainViewModel, screens: @Composable () -> Unit) {
    val placement = remember { SnackbarPlacement() }
    CompositionLocalProvider(LocalSnackbarPlacement provides placement) {
        Box(Modifier.fillMaxSize()) {
            val module = vm.screen.module
            if (module != null || vm.screen == Screen.SETTINGS) {
                // The keyboard (search fields of the lists) lifts the screen and hides the bar, so the
                // results and the FAB stay above it. Its height, not isImeVisible: that one starts out true
                // until the first insets arrive (the screenshot tests never get any).
                val ime = WindowInsets.ime
                val density = LocalDensity.current
                val typing by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
                Column(Modifier.fillMaxSize().imePadding()) {
                    // The bar takes the navigation-bar inset; screens above must not pad for it again.
                    Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) { screens() }
                    // a hidden module opened by a widget or a notification shows in the bar while it is open
                    val modules = Module.entries.filter { it !in vm.hiddenModules || it == module }
                    if (!typing) {
                        ModuleBar(modules, module, onSelect = { vm.openModule(it) }, onSettings = { vm.openSettings() }, Modifier.snackbarAnchor())
                    }
                }
            } else {
                screens()
            }
            AppSnackbar(vm.snacks, placement)
        }
    }
}

@Composable
private fun AppSnackbar(queue: SnackQueue, placement: SnackbarPlacement) {
    val host = remember { SnackbarHostState() }
    val a11y = LocalAccessibilityManager.current
    val entry = queue.current
    LaunchedEffect(entry) {
        entry ?: return@LaunchedEffect
        val s = entry.snack
        val left = queue.start(entry)
        val timeout = a11y?.calculateRecommendedTimeoutMillis(left, containsIcons = false, containsText = true, containsControls = s.actionLabel != null) ?: left
        var acted: Boolean? = null
        try {
            // our own timer, so a snack interrupted by a rotation resumes with the time it had left
            acted = withTimeoutOrNull(timeout) {
                host.showSnackbar(s.text, s.actionLabel, duration = SnackbarDuration.Indefinite) == SnackbarResult.ActionPerformed
            } ?: false
        } finally {
            if (acted == null) queue.interrupt(entry)
        }
        queue.finish(entry, acted == true)
    }
    var rootHeight by remember { mutableIntStateOf(0) }
    val anchor = placement.anchors.values.minOrNull()
    val density = LocalDensity.current
    val position = if (anchor != null && rootHeight > 0) {
        Modifier.padding(bottom = with(density) { (rootHeight - anchor).coerceAtLeast(0f).toDp() })
    } else {
        Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
    }
    Box(Modifier.fillMaxSize().onSizeChanged { rootHeight = it.height }) {
        SnackbarHost(
            host,
            Modifier.align(Alignment.BottomCenter).then(position).onSizeChanged { placement.height = it.height },
        ) { SwipeableSnackbar(it) }
    }
}

/** Swiped sideways, the snackbar is dismissed (its action not taken). */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SwipeableSnackbar(data: SnackbarData) {
    key(data) {
        val state = rememberSwipeToDismissBoxState(confirmValueChange = {
            if (it != SwipeToDismissBoxValue.Settled) data.dismiss()
            true
        })
        SwipeToDismissBox(state, backgroundContent = {}) { Snackbar(data) }
    }
}
