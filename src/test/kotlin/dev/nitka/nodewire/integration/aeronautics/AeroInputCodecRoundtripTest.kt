package dev.nitka.nodewire.integration.aeronautics

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Node
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AeroInputCodecRoundtripTest {

    @Test
    fun `aeronautics_input config roundtrips through Node CODEC`() {
        // Build a config with all three keys set. We use a dummy endpoint
        // tag (shape-only) — the EndpointRef codec is exercised by its
        // own tests; here we only verify our config keys survive.
        val endpointTag = CompoundTag().apply {
            putString("backend", "nodewire:world")
            put("payload", CompoundTag().apply {
                putIntArray("v", intArrayOf(1, 64, -3))
            })
        }
        val config = CompoundTag().apply {
            putString("blockKind", AeroBlockKind.SMART_PROPELLER.name)
            putString("channel", AeroChannel.SMART_PROP_THRUST_DIR.name)
            put("endpoint", endpointTag)
        }
        val node = Node(
            id = Node.newId(),
            typeKey = AeroInputNode.AERONAUTICS_INPUT.id,
            pos = CanvasPos(0f, 0f),
            inputs = emptyList(),
            outputs = AeroInputNode.pinsFor(AeroChannel.SMART_PROP_THRUST_DIR).second,
            config = config,
        )

        val encoded = Node.CODEC
            .encodeStart(NbtOps.INSTANCE, node)
            .result().orElseThrow()
        val decoded = Node.CODEC
            .parse(NbtOps.INSTANCE, encoded)
            .result().orElseThrow()

        assertEquals(node.id, decoded.id)
        assertEquals(node.typeKey, decoded.typeKey)
        assertEquals(
            node.config.getString("blockKind"),
            decoded.config.getString("blockKind"),
        )
        assertEquals(
            node.config.getString("channel"),
            decoded.config.getString("channel"),
        )
        assertTrue(decoded.config.contains("endpoint"), "endpoint tag dropped during roundtrip")
    }
}
