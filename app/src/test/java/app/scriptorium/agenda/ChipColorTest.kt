package app.scriptorium.agenda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChipColorTest {
    private fun hex(s: String) = (0xFF000000.toInt()) or s.removePrefix("#").toInt(16)

    @Test
    fun everyPaletteColourAndAnyImportedOneCarriesWhiteText() {
        val colours = CalendarSettings.PALETTE.map { it.first } + listOf("#FFFFFF", "#FFF475", "#CCFF90", "#000000")
        for (c in colours) {
            val chip = ChipColor.behindWhiteText(hex(c))
            assertTrue("$c -> ${Integer.toHexString(chip)}", ChipColor.contrastWithWhite(chip) >= ChipColor.MIN_CONTRAST)
        }
    }

    @Test
    fun aDarkEnoughColourIsKept() {
        assertEquals(hex("#000000"), ChipColor.behindWhiteText(hex("#000000")))
        assertEquals(hex("#3A2A70"), ChipColor.behindWhiteText(hex("#3A2A70")))
    }
}
