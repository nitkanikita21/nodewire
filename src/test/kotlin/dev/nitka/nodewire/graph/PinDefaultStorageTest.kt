package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PinDefaultStorageTest {

    private fun bareNode(): Node = Node(
        id = Node.newId(),
        typeKey = ResourceLocation.fromNamespaceAndPath("nodewire", "test"),
        pos = CanvasPos(0f, 0f),
        inputs = emptyList(),
        outputs = emptyList(),
        config = CompoundTag(),
    )

    @Test fun `unset pin returns null`() {
        assertNull(bareNode().getPinDefault("anything"))
    }

    @Test fun `set then get returns the same value`() {
        val n = bareNode().withPinDefault("x", PinValue.Float(1.5f))
        assertEquals(PinValue.Float(1.5f), n.getPinDefault("x"))
    }

    @Test fun `set null removes`() {
        var n = bareNode().withPinDefault("x", PinValue.Float(1.5f))
        n = n.withPinDefault("x", null)
        assertNull(n.getPinDefault("x"))
    }

    @Test fun `multiple pins coexist`() {
        val n = bareNode()
            .withPinDefault("a", PinValue.Int(7))
            .withPinDefault("b", PinValue.Bool(true))
            .withPinDefault("c", PinValue.Vec3(1f, 2f, 3f))
        assertEquals(PinValue.Int(7), n.getPinDefault("a"))
        assertEquals(PinValue.Bool(true), n.getPinDefault("b"))
        assertEquals(PinValue.Vec3(1f, 2f, 3f), n.getPinDefault("c"))
    }

    @Test fun `preserves unrelated config keys`() {
        val n0 = bareNode().copy(config = CompoundTag().apply { putString("op", "ADD") })
        val n1 = n0.withPinDefault("x", PinValue.Float(1f))
        assertEquals("ADD", n1.config.getString("op"))
        assertEquals(PinValue.Float(1f), n1.getPinDefault("x"))
    }

    @Test fun `round trip via PinValue CODEC every type`() {
        val cases = listOf(
            PinValue.Bool(true),
            PinValue.Int(-3),
            PinValue.Float(2.5f),
            PinValue.Redstone(11),
            PinValue.Str("hello"),
            PinValue.Vec2(1f, 2f),
            PinValue.Vec3(1f, 2f, 3f),
            PinValue.Quat(0.1f, 0.2f, 0.3f, 0.9f),
        )
        for (v in cases) {
            val n = bareNode().withPinDefault("v", v)
            assertEquals(v, n.getPinDefault("v"), "round-trip failed for $v")
        }
    }
}
