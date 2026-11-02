package app.memento.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.memento.MainViewModel
import app.memento.draw.DrawingState
import app.memento.draw.Stroke
import app.memento.suite.SuiteTopBar

private val Palette = listOf(0x1A1A1A, 0xE53935, 0x1E88E5, 0x43A047, 0xFB8C00, 0x8E24AA)
private val Widths = listOf(2.5f to "Fin", 5f to "Moyen", 10f to "Épais")

/**
 * A page to sketch on with a finger: one finger draws (or erases), two fingers zoom and move the page.
 * Leaving the screen saves the drawing into the note.
 */
@Composable
fun DrawingScreen(vm: MainViewModel, state: DrawingState) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(size) {
        if (size.width > 0 && state.path != null) state.fit(size.width.toFloat(), size.height.toFloat())
    }
    Scaffold(
        topBar = {
            SuiteTopBar("Dessin", onBack = { vm.back() }) {
                IconButton(onClick = { state.undo() }, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Annuler") }
                IconButton(onClick = { state.redo() }, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Rétablir") }
                IconButton(onClick = { vm.back() }) { Icon(Icons.Default.Check, "Terminer") }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = !state.eraser, onClick = { state.eraser = false }, label = { Text("Stylo") })
                        FilterChip(selected = state.eraser, onClick = { state.eraser = true }, label = { Text("Gomme") })
                        Widths.forEach { (w, label) ->
                            FilterChip(selected = state.width == w, onClick = { state.width = w; state.eraser = false }, label = { Text(label) })
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Palette.forEach { c ->
                            Box(
                                Modifier.size(34.dp).background(Color(0xFF000000.toInt() or c), CircleShape)
                                    .border(if (state.rgb == c && !state.eraser) 3.dp else 1.dp, if (state.rgb == c && !state.eraser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                                    .clickable { state.rgb = c; state.eraser = false },
                            )
                        }
                        TextButton(onClick = { state.fit(size.width.toFloat(), size.height.toFloat()) }) { Text("Recentrer") }
                        IconButton(onClick = { state.clearAll() }, enabled = state.strokes.isNotEmpty()) { Icon(Icons.Default.Delete, "Tout effacer") }
                    }
                }
            }
        },
    ) { padding ->
        Canvas(
            Modifier.padding(padding).fillMaxSize().onSizeChanged { size = it }.pointerInput(state) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    var multi = false
                    if (state.eraser) { state.beginErase(); state.eraseAt(state.toWorld(first.position)) }
                    else state.addLive(state.toWorld(first.position))
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            if (!multi) { multi = true; state.cancelLive() } // it was the start of a pinch, not a stroke
                            state.transform(event.calculateCentroid(), event.calculatePan(), event.calculateZoom())
                            event.changes.forEach { it.consume() }
                        } else if (!multi && pressed.size == 1) {
                            val p = state.toWorld(pressed[0].position)
                            if (state.eraser) state.eraseAt(p) else state.addLive(p)
                            pressed[0].consume()
                        }
                    } while (event.changes.any { it.pressed })
                    if (state.eraser) state.endErase() else if (!multi) state.finishStroke()
                }
            },
        ) {
            drawRect(Color.White)
            withTransform({
                translate(state.offsetX, state.offsetY)
                scale(state.scale, state.scale, Offset.Zero)
            }) {
                for (s in state.strokes) drawStrokeLine(s.xs, s.ys, s.rgb, s.width)
                if (state.live.isNotEmpty()) {
                    drawStrokeLine(FloatArray(state.live.size) { state.live[it].x }, FloatArray(state.live.size) { state.live[it].y }, state.rgb, state.width)
                }
            }
        }
    }
}

private fun DrawScope.drawStrokeLine(xs: FloatArray, ys: FloatArray, rgb: Int, width: Float) {
    if (xs.isEmpty()) return
    val color = Color(0xFF000000.toInt() or rgb)
    if (xs.size == 1) {
        drawCircle(color, width / 2, Offset(xs[0], ys[0]))
        return
    }
    val path = Path().apply {
        moveTo(xs[0], ys[0])
        for (i in 1 until xs.size) lineTo(xs[i], ys[i])
    }
    drawPath(path, color, style = DrawStroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
