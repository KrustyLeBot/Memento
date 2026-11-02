package app.memento.draw

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot

/** The drawing being edited: strokes, undo history, pen settings and the view (zoom, pan). */
class DrawingState(initial: Drawing, /** The file it came from; null for a new drawing. */ val path: String?) {
    var strokes by mutableStateOf(initial.strokes)
        private set
    private val undoStack = ArrayList<List<Stroke>>()
    private val redoStack = ArrayList<List<Stroke>>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var changed = false
        private set

    var rgb by mutableIntStateOf(0x1A1A1A)
    var width by mutableFloatStateOf(4f)
    var eraser by mutableStateOf(false)

    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)

    /** The stroke being drawn, in drawing coordinates. */
    val live = mutableStateListOf<Offset>()

    private var eraseStart: List<Stroke>? = null

    fun toWorld(p: Offset) = Offset((p.x - offsetX) / scale, (p.y - offsetY) / scale)

    /** Pinch and drag: zoom about [centroid] and move by [pan] (screen pixels). */
    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val world = toWorld(centroid)
        val s = (scale * zoom).coerceIn(0.15f, 10f)
        scale = s
        offsetX = centroid.x + pan.x - world.x * s
        offsetY = centroid.y + pan.y - world.y * s
    }

    fun addLive(p: Offset) {
        val last = live.lastOrNull()
        if (last == null || hypot(p.x - last.x, p.y - last.y) >= 1.2f / scale) live.add(p)
    }

    fun cancelLive() = live.clear()

    fun finishStroke() {
        if (live.isEmpty()) return
        val xs = FloatArray(live.size) { live[it].x }
        val ys = FloatArray(live.size) { live[it].y }
        live.clear()
        push()
        strokes = strokes + Stroke(rgb, width, xs, ys)
    }

    fun beginErase() { eraseStart = strokes }

    /** Removes the strokes under the finger (a 14 px radius on screen). */
    fun eraseAt(p: Offset) {
        val r = 14f / scale
        val keep = strokes.filter { it.distanceTo(p.x, p.y) > r + it.width / 2 }
        if (keep.size != strokes.size) strokes = keep
    }

    fun endErase() {
        val before = eraseStart ?: return
        eraseStart = null
        if (before.size != strokes.size) {
            undoStack.add(before)
            redoStack.clear()
            changed = true
            sync()
        }
    }

    fun clearAll() {
        if (strokes.isEmpty()) return
        push()
        strokes = emptyList()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.add(strokes)
        strokes = prev
        changed = true
        sync()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.add(strokes)
        strokes = next
        changed = true
        sync()
    }

    private fun push() {
        undoStack.add(strokes)
        redoStack.clear()
        changed = true
        sync()
    }

    private fun sync() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    /** Fits the whole drawing in a [w] × [h] view. */
    fun fit(w: Float, h: Float) {
        val b = Drawing(strokes).bounds()
        if (b == null || w <= 0f || h <= 0f) { scale = 1f; offsetX = 0f; offsetY = 0f; return }
        val bw = (b[2] - b[0]).coerceAtLeast(1f)
        val bh = (b[3] - b[1]).coerceAtLeast(1f)
        val s = (minOf(w * 0.9f / bw, h * 0.9f / bh)).coerceIn(0.15f, 4f)
        scale = s
        offsetX = w / 2 - (b[0] + bw / 2) * s
        offsetY = h / 2 - (b[1] + bh / 2) * s
    }
}
