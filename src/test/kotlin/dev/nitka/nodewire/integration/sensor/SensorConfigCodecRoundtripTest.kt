package dev.nitka.nodewire.integration.sensor

import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.PinType
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SensorConfigCodecRoundtripTest {

    @Test
    fun `block_sensor config roundtrips through Node CODEC`() {
        // Dummy endpoint tag (shape-only) — EndpointRef's own codec is tested
        // elsewhere; here we only verify our config keys survive the roundtrip.
        val endpointTag = CompoundTag().apply {
            putString("backend", "nodewire:world")
            put("payload", CompoundTag().apply {
                putIntArray("v", intArrayOf(1, 64, -3))
            })
        }
        val filterTag = CompoundTag().apply {
            putString("id", "minecraft:stone")
            putByte("count", 1)
        }
        val config = CompoundTag().apply {
            putString("reading", SensorReading.COUNT_OF.name)
            putString("side", "NORTH")
            put("endpoint", endpointTag)
            put("filter", filterTag)
        }
        val node = Node(
            id = Node.newId(),
            typeKey = BlockSensorNode.BLOCK_SENSOR.id,
            pos = CanvasPos(0f, 0f),
            inputs = emptyList(),
            outputs = BlockSensorNode.pinsFor(SensorReading.COUNT_OF).second,
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
        assertEquals(SensorReading.COUNT_OF.name, decoded.config.getString("reading"))
        assertEquals("NORTH", decoded.config.getString("side"))
        assertTrue(decoded.config.contains("endpoint"), "endpoint tag dropped during roundtrip")
        assertTrue(decoded.config.contains("filter"), "filter tag dropped during roundtrip")
    }

    @Test
    fun `pinsFor maps fluid reading to FLOAT out pin`() {
        val out = BlockSensorNode.pinsFor(SensorReading.FLUID_FILL).second.single()
        assertEquals(PinType.FLOAT, out.type)
    }
}
