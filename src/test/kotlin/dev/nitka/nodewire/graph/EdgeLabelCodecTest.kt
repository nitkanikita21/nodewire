package dev.nitka.nodewire.graph

import net.minecraft.nbt.NbtOps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class EdgeLabelCodecTest {
    private fun <T> roundTrip(codec: com.mojang.serialization.Codec<T>, v: T): T {
        val tag = codec.encodeStart(NbtOps.INSTANCE, v).result().orElseThrow()
        return codec.parse(NbtOps.INSTANCE, tag).result().orElseThrow()
    }

    @Test fun edgeWithLabelRoundTrip() {
        val e = Edge(PinRef(UUID.randomUUID(), "a"), PinRef(UUID.randomUUID(), "b"), label = "clock")
        assertEquals(e, roundTrip(Edge.CODEC, e))
    }

    @Test fun edgeWithoutLabelRoundTrip() {
        val e = Edge(PinRef(UUID.randomUUID(), "a"), PinRef(UUID.randomUUID(), "b"))
        val decoded = roundTrip(Edge.CODEC, e)
        assertEquals(e, decoded)
        assertNull(decoded.label)
    }
}
