package dev.nitka.nodewire.block.panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PanelElementConfigTest {
    @Test fun `slider fields round-trip through build and read`() {
        val cfg = PanelElementConfig.build("slider", mapOf("min" to "-5", "max" to "10", "step" to "0.5"))
        assertEquals(-5.0, cfg.getDouble("min"), 1e-9)
        assertEquals(10.0, cfg.getDouble("max"), 1e-9)
        assertEquals(0.5, cfg.getDouble("step"), 1e-9)
        val maxF = PanelElementConfig.fields("slider").first { it.key == "max" }
        assertEquals("10", PanelElementConfig.read(cfg, maxF))
    }

    @Test fun `colour parses hex into argb int and reads back as hex`() {
        val cfg = PanelElementConfig.build("lamp", mapOf("on_color" to "FF00FF00"))
        assertEquals(0xFF00FF00.toInt(), cfg.getInt("on_color"))
        val f = PanelElementConfig.fields("lamp").first { it.key == "on_color" }
        assertEquals("FF00FF00", PanelElementConfig.read(cfg, f))
    }

    @Test fun `unparseable value falls back to the field default`() {
        val cfg = PanelElementConfig.build("selector", mapOf("positions" to "abc"))
        assertEquals(2, cfg.getInt("positions"))
    }

    @Test fun `toggle and momentary have no options`() {
        assertTrue(PanelElementConfig.fields("toggle").isEmpty())
        assertTrue(PanelElementConfig.fields("momentary").isEmpty())
    }
}
