package dev.nitka.nodewire.integration.sable

import com.mojang.serialization.JsonOps
import dev.nitka.nodewire.endpoint.EndpointBackends
import dev.nitka.nodewire.endpoint.EndpointRef
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class SableSubLevelBackendCodecTest {

    @BeforeEach fun reset() {
        EndpointBackends.clearForTests()
        EndpointBackends.register(SableSubLevelBackend)
    }

    @Test fun `round-trip preserves backend id, sub-level uuid, and block pos`() {
        val uuid = UUID.fromString("a1b2c3d4-e5f6-7890-1234-567890abcdef")
        val ref = EndpointRef(
            SableSubLevelBackend.id,
            SableSubLevelPayload(uuid, BlockPos(-12345, 64, 98765)),
        )

        val json = EndpointRef.CODEC.encodeStart(JsonOps.INSTANCE, ref).result().orElseThrow()
        val decoded = EndpointRef.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow()

        assertEquals(ref, decoded)
    }
}
