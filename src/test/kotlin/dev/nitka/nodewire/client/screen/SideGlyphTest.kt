package dev.nitka.nodewire.client.screen

import net.minecraft.core.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SideGlyphTest {
    @Test fun upIsArrow()    = assertEquals("↑", sideGlyph(Direction.UP))
    @Test fun downIsArrow()  = assertEquals("↓", sideGlyph(Direction.DOWN))
    @Test fun northIsN()     = assertEquals("N", sideGlyph(Direction.NORTH))
    @Test fun southIsS()     = assertEquals("S", sideGlyph(Direction.SOUTH))
    @Test fun westIsW()      = assertEquals("W", sideGlyph(Direction.WEST))
    @Test fun eastIsE()      = assertEquals("E", sideGlyph(Direction.EAST))
}
