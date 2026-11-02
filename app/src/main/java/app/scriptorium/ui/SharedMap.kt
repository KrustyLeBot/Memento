package app.scriptorium.ui

import android.widget.FrameLayout
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.scriptorium.map.Json
import app.scriptorium.map.MapHost

/**
 * Shows the shared map WebView here. [onShown] runs each time the map becomes visible again (screen back
 * on): the screen resends what changed meanwhile. [onEvent] gets the page's events.
 */
@Composable
fun SharedMap(
    modifier: Modifier,
    mode3d: Boolean,
    onEvent: (String, Map<String, Any?>) -> Unit = { _, _ -> },
    onShown: () -> Unit = {},
) {
    val dark = isDarkTheme()
    val event by rememberUpdatedState(onEvent)
    val shown by rememberUpdatedState(onShown)
    val owner = LocalLifecycleOwner.current

    DisposableEffect(Unit) {
        val l: (String, Map<String, Any?>) -> Unit = { t, e -> event(t, e) }
        MapHost.listener = l
        onDispose { if (MapHost.listener === l) MapHost.listener = null }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> { MapHost.setStarted(true); shown() }
                Lifecycle.Event.ON_STOP -> MapHost.setStarted(false)
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer) // replays ON_START when already started
        onDispose {
            owner.lifecycle.removeObserver(observer)
            MapHost.setStarted(false)
        }
    }
    LaunchedEffect(dark) { MapHost.call("setTheme", Json.quote(if (dark) "dark" else "light")) }

    key(MapHost.generation) {
        AndroidView(
            factory = { ctx -> FrameLayout(ctx).also { MapHost.attach(it, dark, mode3d) } },
            modifier = modifier,
            onRelease = { MapHost.detach(it) },
        )
    }
}

/** The 3D / 2D switch drawn over a map. */
@Composable
fun MapModeSwitch(is3d: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), modifier = modifier) {
        Row(Modifier.clip(RoundedCornerShape(50))) {
            listOf(true to "3D", false to "2D").forEach { (v, label) ->
                val selected = v == is3d
                Surface(
                    onClick = { if (enabled && !selected) onChange(v) },
                    enabled = enabled,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
