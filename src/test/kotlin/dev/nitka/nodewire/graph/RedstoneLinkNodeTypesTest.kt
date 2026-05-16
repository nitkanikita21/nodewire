package dev.nitka.nodewire.graph

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RedstoneLinkNodeTypesTest {
    @BeforeEach fun reset() {
        NodeTypeRegistry.clearForTests()
        StockNodeTypes.registerAll()
    }

    @Test fun `redstone_link_input is registered with REDSTONE output`() {
        val t = NodeTypeRegistry.get(net.minecraft.resources.ResourceLocation("nodewire", "redstone_link_input"))
        assertNotNull(t)
        assertEquals(0, t!!.inputs.size)
        assertEquals(1, t.outputs.size)
        assertEquals(PinType.REDSTONE, t.outputs[0].type)
    }

    @Test fun `redstone_link_output is registered with REDSTONE input`() {
        val t = NodeTypeRegistry.get(net.minecraft.resources.ResourceLocation("nodewire", "redstone_link_output"))
        assertNotNull(t)
        assertEquals(1, t!!.inputs.size)
        assertEquals(0, t.outputs.size)
        assertEquals(PinType.REDSTONE, t.inputs[0].type)
    }

    @Test fun `default config has empty freq1 and freq2 compounds`() {
        val t = NodeTypeRegistry.get(net.minecraft.resources.ResourceLocation("nodewire", "redstone_link_input"))!!
        val cfg = t.defaultConfig()
        assertTrue(cfg.contains("freq1"))
        assertTrue(cfg.contains("freq2"))
        // Empty compound → ItemStack.EMPTY when read back via ItemStack.of(tag).
        // We avoid calling ItemStack.of() here because it triggers MC bootstrap
        // which is not available in unit tests.
        assertTrue(cfg.getCompound("freq1").isEmpty)
        assertTrue(cfg.getCompound("freq2").isEmpty)
    }
}
