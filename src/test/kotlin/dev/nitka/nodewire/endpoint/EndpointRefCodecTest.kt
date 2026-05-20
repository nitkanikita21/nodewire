// src/test/kotlin/dev/nitka/nodewire/endpoint/EndpointRefCodecTest.kt
package dev.nitka.nodewire.endpoint

import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EndpointRefCodecTest {
    data class FakePayload(override val blockPos: BlockPos) : EndpointPayload

    object FakeBackend : EndpointBackend {
        override val id = ResourceLocation.fromNamespaceAndPath("test", "fake")
        override val payloadCodec: Codec<out EndpointPayload> =
            BlockPos.CODEC.xmap(::FakePayload) { it.blockPos }
        override fun resolveBlockEntity(level: Level, payload: EndpointPayload) = null
        override fun worldCenter(level: Level, payload: EndpointPayload): Vec3? = null
        override fun worldDirection(level: Level, payload: EndpointPayload, localDir: Vec3): Vec3? = null
        override fun claims(level: Level, worldPos: BlockPos): EndpointPayload? = FakePayload(worldPos)
    }

    @BeforeEach fun reset() {
        EndpointBackends.clearForTests()
        EndpointBackends.register(FakeBackend)
    }

    @Test fun `round-trip preserves backend id and payload`() {
        val ref = EndpointRef(FakeBackend.id, FakePayload(BlockPos(1, 2, 3)))
        val json = EndpointRef.CODEC.encodeStart(JsonOps.INSTANCE, ref).result().orElseThrow()
        val decoded = EndpointRef.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow()
        assertEquals(ref, decoded)
    }

    @Test fun `unknown backend id decodes to UnknownPayload`() {
        // payload must be a JSON object — CompoundTag.CODEC rejects arrays/primitives.
        val raw = """{"backend":"test:missing","payload":{"foo":"bar"}}"""
        val json = com.google.gson.JsonParser.parseString(raw)
        val ref = EndpointRef.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow()
        assertEquals(ResourceLocation.fromNamespaceAndPath("test", "missing"), ref.backendId)
        assertTrue(ref.payload is UnknownPayload)

        // Verify wrapper bytes survive a round-trip when backend isn't registered
        val reencoded = EndpointRef.CODEC.encodeStart(JsonOps.INSTANCE, ref).result().orElseThrow()
        val expected = com.google.gson.JsonParser.parseString(raw)
        assertEquals(expected, reencoded)
    }
}
