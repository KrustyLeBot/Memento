package app.memento.agenda

import kotlin.math.pow

/**
 * The agenda draws white text on the calendar's colour. The palette sits around 3.5:1 against white (fine for a
 * swatch, short of the 4.5:1 small text needs) and an imported calendar can be any colour, so the block behind
 * the text is darkened just enough. Pure (ARGB ints) for the JVM tests.
 */
object ChipColor {
    /** 4.5:1 for small text, with a margin for the 85 % white of the second line (time, place). */
    const val MIN_CONTRAST = 5.2

    private fun channel(c: Int): Double {
        val x = c / 255.0
        return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(argb: Int): Double =
        0.2126 * channel(argb shr 16 and 0xFF) + 0.7152 * channel(argb shr 8 and 0xFF) + 0.0722 * channel(argb and 0xFF)

    fun contrastWithWhite(argb: Int): Double = 1.05 / (luminance(argb) + 0.05)

    /** [argb] itself when white text already reads on it, else the same hue darkened step by step until it does. */
    fun behindWhiteText(argb: Int): Int {
        var r = argb shr 16 and 0xFF
        var g = argb shr 8 and 0xFF
        var b = argb and 0xFF
        var c = argb
        while (contrastWithWhite(c) < MIN_CONTRAST) {
            r = r * 92 / 100; g = g * 92 / 100; b = b * 92 / 100
            c = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return c
    }
}
