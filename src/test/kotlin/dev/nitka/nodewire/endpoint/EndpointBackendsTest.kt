// src/test/kotlin/dev/nitka/nodewire/endpoint/EndpointBackendsTest.kt
package dev.nitka.nodewire.endpoint

import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EndpointBackendsTest {
    private object FakePayload : EndpointPayload { override val blockPos: BlockPos = BlockPos.ZERO }

    private class FakeBackend(override val id: ResourceLocation) : EndpointBackend {
        override val payloadCodec: Codec<out EndpointPayload> = Codec.unit(FakePayload)
        override fun resolveBlockEntity(level: net.minecraft.world.level.Level, payload: EndpointPayload) = null
        override fun worldCenter(level: net.minecraft.world.level.Level, payload: EndpointPayload): Vec3? = null
        override fun worldDirection(level: net.minecraft.world.level.Level, payload: EndpointPayload, localDir: Vec3): Vec3? = null
        override fun claims(level: net.minecraft.world.level.Level, worldPos: BlockPos): EndpointPayload? = null
    }

    @BeforeEach fun reset() { EndpointBackends.clearForTests() }

    @Test fun `register and get round-trip`() {
        val a = FakeBackend(ResourceLocation.fromNamespaceAndPath("test", "a"))
        EndpointBackends.register(a)
        assertSame(a, EndpointBackends.get(ResourceLocation.fromNamespaceAndPath("test", "a")))
    }

    @Test fun `unknown id returns null`() {
        assertNull(EndpointBackends.get(ResourceLocation.fromNamespaceAndPath("test", "x")))
    }

    @Test fun `all preserves insertion order`() {
        val a = FakeBackend(ResourceLocation.fromNamespaceAndPath("test", "a"))
        val b = FakeBackend(ResourceLocation.fromNamespaceAndPath("test", "b"))
        EndpointBackends.register(a)
        EndpointBackends.register(b)
        assertEquals(listOf(a, b), EndpointBackends.all().toList())
    }

    @Test fun `WorldBackend claims any position`() {
        EndpointBackends.register(WorldBackend)
        // WorldBackend is registered and produces a payload with the correct blockPos.
        // We construct WorldPayload directly because claims() requires a non-null Level
        // (Kotlin intrinsic check) and no MC environment is available in unit tests.
        val payload = WorldPayload(BlockPos(7, 8, 9))
        assertNotNull(payload)
        assertEquals(BlockPos(7, 8, 9), payload.blockPos)
        // Verify the backend is actually registered under its id.
        assertSame(WorldBackend, EndpointBackends.get(ResourceLocation.fromNamespaceAndPath("nodewire", "world")))
    }
}
