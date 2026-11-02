package app.scriptorium.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.scriptorium.activity.Analysis
import app.scriptorium.activity.Sport
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun sportEmoji(s: Sport) = when (s) {
    Sport.WALK -> "🚶"
    Sport.BIKE -> "🚴"
    Sport.EBIKE -> "⚡"
    Sport.SKI -> "⛷️"
    Sport.GPS -> "📍"
    Sport.PLAIN -> "⏱️"
}

fun formatDate(ms: Long): String = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.FRANCE).format(Date(ms))
fun formatHour(ms: Long): String = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(ms))

fun startOfWeek(now: Long = System.currentTimeMillis()): Long = Calendar.getInstance(Locale.FRANCE).apply {
    firstDayOfWeek = Calendar.MONDAY
    timeInMillis = now
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
}.timeInMillis

fun startOfMonth(now: Long = System.currentTimeMillis()): Long = Calendar.getInstance(Locale.FRANCE).apply {
    timeInMillis = now
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Label above, figure below. */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** Figures in two columns. */
@Composable
fun StatGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (l, v) -> Stat(l, v, Modifier.weight(1f)) }
                if (row.size == 1) Column(Modifier.weight(1f)) {}
            }
        }
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/** A filled line chart of [ys] against [xs] (both already thinned), with its extremes written above. */
@Composable
fun ProfileChart(title: String, xs: DoubleArray, ys: DoubleArray, low: String, high: String, color: Color) {
    if (xs.size < 2) return
    Section(title) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("min $low", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("max $high", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val minY = ys.min()
            val maxY = ys.max()
            val rangeY = (maxY - minY).coerceAtLeast(1e-6)
            val minX = xs.first()
            val rangeX = (xs.last() - minX).coerceAtLeast(1e-6)
            val line = Path()
            for (i in xs.indices) {
                val x = ((xs[i] - minX) / rangeX * size.width).toFloat()
                val y = (size.height - (ys[i] - minY) / rangeY * size.height * 0.9 - size.height * 0.05).toFloat()
                if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.02f))))
            drawPath(line, color, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

/** At most [max] evenly spaced indices of a series of [n]. */
fun thin(n: Int, max: Int = 300): IntArray {
    if (n <= max) return IntArray(n) { it }
    return IntArray(max) { (it.toLong() * (n - 1) / (max - 1)).toInt() }
}

// slow to fast: violet, magenta, red-orange (stands out on the pale map)
private val Slow = Color(0xFF6A00F4)
private val Mid = Color(0xFFE500A4)
private val Fast = Color(0xFFFF3D00)

private fun speedColor(f: Float): Color = if (f < 0.5f) lerp(Slow, Mid, f * 2) else lerp(Mid, Fast, (f - 0.5f) * 2)

enum class SkiMap { BOTH, DOWN, ALL }

/** A coloured polyline on the map. */
class MapLine(val lats: DoubleArray, val lons: DoubleArray, val color: Color)

private val Climb = Color(0xFF00B0FF)

/**
 * The analysis as map lines: chunks coloured from violet (slow) to red (fast). Skiing: descents in that
 * gradient and climbs in blue, or descents only, or everything (the rest in grey). Gaps (pauses, lost
 * signal) cut the line.
 */
fun mapLines(a: Analysis, ski: Boolean, mode: SkiMap = SkiMap.BOTH): List<MapLine> {
    val s = a.samples
    if (s.size < 2) return emptyList()
    val sorted = s.map { it.speed }.sorted()
    val top = sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)].coerceAtLeast(0.5)
    val maxGap = (a.quality.medianIntervalSec * 6).coerceAtLeast(30.0) * 1000
    // 0 = descent (or any point of a non-ski activity), 1 = climb, 2 = the rest of a ski day
    fun kind(i: Int): Int = if (!ski || s[i].down) 0 else if (s[i].up) 1 else 2
    fun shown(i: Int): Boolean = !ski || when (mode) {
        SkiMap.ALL -> true
        SkiMap.DOWN -> s[i].down
        SkiMap.BOTH -> s[i].down || s[i].up
    }
    val out = ArrayList<MapLine>()
    var i = 0
    while (i < s.size - 1) {
        if (!shown(i)) { i++; continue }
        val from = i
        val k = kind(i)
        var j = i
        var speedSum = 0.0
        while (j + 1 < s.size && j - from < 8 && s[j + 1].time - s[j].time <= maxGap && shown(j + 1) && kind(j + 1) == k) {
            j++
            speedSum += s[j].speed
        }
        if (j == from) { i++; continue }
        val color = when (k) {
            1 -> Climb
            2 -> Color(0xFF9AA0A6)
            else -> speedColor((speedSum / (j - from) / top).toFloat().coerceIn(0f, 1f))
        }
        out.add(MapLine(DoubleArray(j - from + 1) { s[from + it].lat }, DoubleArray(j - from + 1) { s[from + it].lon }, color))
        i = j
    }
    return out
}
