package dev.nitka.nodewire.link

import dev.nitka.nodewire.endpoint.EndpointBackends
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.endpoint.WorldBackend
import dev.nitka.nodewire.endpoint.WorldPayload
import net.minecraft.core.BlockPos
import net.minecraft.nbt.NbtOps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The unified [PinLink] is THE persisted shape behind every Link-Tool wire
 * (camera→logic, touch→logic, redstone→logic, logic→logic, logic→screen…) —
 * its codec round-trip is the persistence contract for all of them at once.
 */
class PinLinkCodecTest {
    @BeforeEach fun reset() {
        EndpointBackends.clearForTests()
        EndpointBackends.register(WorldBackend)
    }

    @Test fun `round-trips through NBT`() {
        val link = PinLink(
            source = EndpointRef(WorldBackend.id, WorldPayload(BlockPos(7, 64, -3))),
            sourcePin = "touch_down",
            targetPin = "tap_down",
        )
        val tag = PinLink.CODEC.encodeStart(NbtOps.INSTANCE, link).result().orElseThrow()
        val decoded = PinLink.CODEC.parse(NbtOps.INSTANCE, tag).result().orElseThrow()
        assertEquals(link, decoded)
    }

    @Test fun `seenStamp is runtime-only — never round-trips`() {
        val link = PinLink(
            source = EndpointRef(WorldBackend.id, WorldPayload(BlockPos.ZERO)),
            sourcePin = "touch_down",
            targetPin = "tap_down",
        )
        link.seenStamp = 12345L
        val tag = PinLink.CODEC.encodeStart(NbtOps.INSTANCE, link).result().orElseThrow()
        val decoded = PinLink.CODEC.parse(NbtOps.INSTANCE, tag).result().orElseThrow()
        assertEquals(-1L, decoded.seenStamp)
        // ...and the latch doesn't affect identity either.
        assertEquals(link, decoded)
    }
}
