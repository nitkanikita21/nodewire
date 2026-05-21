package dev.nitka.nodewire.graph

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WireCompatibilityTest {

    @Test fun `every scalar pair allowed`() {
        val scalars = listOf(PinType.BOOL, PinType.INT, PinType.FLOAT, PinType.REDSTONE, PinType.STRING)
        for (a in scalars) for (b in scalars) {
            assertTrue(PinValueConversion.canConvert(a, b), "$a → $b should be allowed")
        }
    }

    @Test fun `any to and from everything`() {
        for (t in PinType.entries) {
            assertTrue(PinValueConversion.canConvert(PinType.ANY, t))
            assertTrue(PinValueConversion.canConvert(t, PinType.ANY))
        }
    }

    @Test fun `vec3 to bool rejected`() {
        assertFalse(PinValueConversion.canConvert(PinType.VEC3, PinType.BOOL))
    }

    @Test fun `quat to float rejected`() {
        assertFalse(PinValueConversion.canConvert(PinType.QUAT, PinType.FLOAT))
    }

    @Test fun `vec2 to vec3 rejected`() {
        assertFalse(PinValueConversion.canConvert(PinType.VEC2, PinType.VEC3))
    }
}
