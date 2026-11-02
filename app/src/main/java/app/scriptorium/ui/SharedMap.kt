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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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

/**
 * What is drawn over the map: it sits on an aerial photo (snow, forest, night-dark rock), so every overlay gets a
 * near-opaque surface, an explicit content colour and a shadow. Never a transparent background and never the
 * ambient content colour, which outside a Scaffold (the full-screen map) is plain black. Contrast, measured on the
 * suite palette with the 6 % of photo showing through at its worst (black or white): onSurface on
 * surfaceContainerHighest is 8.7:1 or more in both themes, onPrimary on primary 7.7:1. onSurfaceVariant is not
 * used: 4.3:1 in the light theme over a dark photo.
 */
object MapOverlay {
    const val ALPHA = 0.94f
    val elevation = 3.dp

    val container: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = ALPHA)
    val content: Color @Composable get() = MaterialTheme.colorScheme.onSurface
}

/** A surface over the map, with [MapOverlay]'s colours. */
@Composable
fun MapOverlaySurface(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(50), content: @Composable () -> Unit) {
    Surface(
        shape = shape, color = MapOverlay.container, contentColor = MapOverlay.content,
        shadowElevation = MapOverlay.elevation, modifier = modifier, content = content,
    )
}

/** The 3D / 2D switch drawn over a map: the selected half in primary, the other in onSurface, both always legible. */
@Composable
fun MapModeSwitch(is3d: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    MapOverlaySurface(modifier) {
        Row(Modifier.padding(2.dp).clip(RoundedCornerShape(50))) {
            listOf(true to "3D", false to "2D").forEach { (v, label) ->
                val selected = v == is3d
                Surface(
                    onClick = { if (enabled && !selected) onChange(v) },
                    enabled = enabled,
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MapOverlay.content,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
