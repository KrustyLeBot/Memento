package app.scriptorium.draw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawingTest {
    private fun stroke(rgb: Int, w: Float, vararg p: Float): Stroke {
        val n = p.size / 2
        return Stroke(rgb, w, FloatArray(n) { p[it * 2] }, FloatArray(n) { p[it * 2 + 1] })
    }

    @Test
    fun svgRoundTripKeepsStrokes() {
        val d = Drawing(listOf(stroke(0x1a1a1a, 3f, 10f, 20f, 30.5f, 40f, -5f, 7.5f), stroke(0xe53935, 6f, 100f, 100f)))
        val svg = d.toSvg()
        assertTrue(Drawing.looksLikeDrawing(svg))
        assertTrue(svg.contains("viewBox="))
        val back = Drawing.parse(svg)
        assertEquals(2, back.strokes.size)
        assertEquals(0x1a1a1a, back.strokes[0].rgb)
        assertEquals(3f, back.strokes[0].width, 0.01f)
        assertEquals(3, back.strokes[0].size)
        assertEquals(30.5f, back.strokes[0].xs[1], 0.05f)
        assertEquals(-5f, back.strokes[0].xs[2], 0.05f)
        assertEquals(7.5f, back.strokes[0].ys[2], 0.05f)
        assertEquals(0xe53935, back.strokes[1].rgb)
        assertEquals(1, back.strokes[1].size) // a dot stays a dot
        assertEquals(100f, back.strokes[1].xs[0], 0.05f)
    }

    @Test
    fun emptyDrawingIsAValidSvg() {
        val svg = Drawing(emptyList()).toSvg()
        assertTrue(svg.startsWith("<svg"))
        assertTrue(Drawing.parse(svg).isEmpty)
        assertEquals(null, Drawing(emptyList()).bounds())
    }

    @Test
    fun boundsIncludeTheStrokeWidth() {
        val b = Drawing(listOf(stroke(0, 10f, 0f, 0f, 100f, 50f))).bounds()!!
        assertEquals(-5f, b[0], 0.01f)
        assertEquals(105f, b[2], 0.01f)
        assertEquals(55f, b[3], 0.01f)
    }

    @Test
    fun eraserHitsTheTopmostStrokeNearThePoint() {
        val d = Drawing(listOf(stroke(1, 4f, 0f, 0f, 100f, 0f), stroke(2, 4f, 0f, 3f, 100f, 3f), stroke(3, 4f, 0f, 200f, 100f, 200f)))
        assertEquals(1, d.strokeAt(50f, 2f, 3f)) // both lines are near: the later one is on top
        assertEquals(2, d.strokeAt(50f, 198f, 3f))
        assertEquals(-1, d.strokeAt(50f, 100f, 3f))
        assertEquals(0, d.strokeAt(-2f, -3f, 3f))
    }

    @Test
    fun otherSvgPathsAreIgnored() {
        val svg = "<svg><path d=\"M0 0 L5 5\" fill=\"red\"/><path d=\"M1 1 L2 2\" stroke=\"#112233\" stroke-width=\"2\"/></svg>"
        val d = Drawing.parse(svg)
        assertEquals(1, d.strokes.size)
        assertEquals(0x112233, d.strokes[0].rgb)
    }
}
