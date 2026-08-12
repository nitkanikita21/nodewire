package dev.nitka.nodewire.block.panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PanelElementConfigTest {
    @Test fun `knob fields round-trip through build and read`() {
        val cfg = PanelElementConfig.build("knob", mapOf("min" to "-5", "max" to "10"))
        assertEquals(-5.0, cfg.getDouble("min"), 1e-9)
        assertEquals(10.0, cfg.getDouble("max"), 1e-9)
        val maxF = PanelElementConfig.fields("knob").first { it.key == "max" }
        assertEquals("10", PanelElementConfig.read(cfg, maxF))
    }

    @Test fun `colour parses hex into argb int and reads back as hex`() {
        val cfg = PanelElementConfig.build("bulb", mapOf("on_color" to "FF00FF00"))
        assertEquals(0xFF00FF00.toInt(), cfg.getInt("on_color"))
        val f = PanelElementConfig.fields("bulb").first { it.key == "on_color" }
        assertEquals("FF00FF00", PanelElementConfig.read(cfg, f))
    }

    @Test fun `unparseable value falls back to the field default`() {
        val cfg = PanelElementConfig.build("push_button", mapOf("buttons" to "abc"))
        assertEquals(1, cfg.getInt("buttons"))
    }

    @Test fun `switch and momentary have no options`() {
        assertTrue(PanelElementConfig.fields("switch").isEmpty())
        assertTrue(PanelElementConfig.fields("momentary").isEmpty())
    }
}
