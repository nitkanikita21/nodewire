package dev.nitka.nodewire.ui.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ColorTest {
    @Test fun argbComponents() {
        val c = Color(0x80_AA_BB_CC.toInt())
        assertEquals(0x80, c.a)
        assertEquals(0xAA, c.r)
        assertEquals(0xBB, c.g)
        assertEquals(0xCC, c.b)
    }

    @Test fun argbFactory() {
        val c = Color.argb(0x12, 0x34, 0x56, 0x78)
        assertEquals(0x12_34_56_78, c.argb)
    }

    @Test fun rgbDefaultsAlphaFF() {
        assertEquals(0xFF_11_22_33.toInt(), Color.rgb(0x11, 0x22, 0x33).argb)
    }

    @Test fun copyAlphaFloat() {
        val c = Color.rgb(255, 0, 0).copy(alpha = 0.5f)
        // 0.5 * 255 = 127 (truncated)
        assertEquals(127, c.a)
        assertEquals(255, c.r)
    }

    @Test fun blendInterpolates() {
        val red = Color.rgb(255, 0, 0)
        val blue = Color.rgb(0, 0, 255)
        val mid = red.blend(blue, 0.5f)
        assertEquals(127, mid.r)
        assertEquals(127, mid.b)
        assertEquals(255, mid.a)
    }

    @Test fun shiftLightnessChangesValue() {
        val gray = Color.rgb(128, 128, 128)
        val lighter = gray.shiftLightness(+0.2f)
        val darker = gray.shiftLightness(-0.2f)
        assertNotEquals(gray, lighter)
        assertNotEquals(gray, darker)
        // Lighter should have a higher mean component
        val mean = { c: Color -> (c.r + c.g + c.b) / 3 }
        assert(mean(lighter) > mean(gray))
        assert(mean(darker) < mean(gray))
    }

    @Test fun shiftLightnessZeroIsNoop() {
        val c = Color.rgb(123, 45, 67)
        assertEquals(c, c.shiftLightness(0f))
    }

    @Test fun constants() {
        assertEquals(0, Color.Transparent.a)
        assertEquals(255, Color.Black.a); assertEquals(0, Color.Black.r)
        assertEquals(255, Color.White.r); assertEquals(255, Color.White.b)
    }
}
