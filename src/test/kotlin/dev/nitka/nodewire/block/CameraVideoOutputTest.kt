package dev.nitka.nodewire.block

import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Headless coverage of the Camera producer → Screen consumer wiring at the
 * value level (no BlockEntity, no Level, no GL). Proves the net invariant the
 * v1 channel-pipeline output relies on: the Camera's emitting value is a bare
 * `PinValue.Video(handle)` carrying only the UUID, and the Screen decodes that
 * exact handle back out — so a handle minted by a Camera and routed over the
 * existing cross-block path resolves to the same surface on the consumer.
 *
 * `CameraBlockEntity.videoValue()` returns `PinValue.Video(videoHandle())`;
 * this exercises the equivalent value (the BE itself needs a registered BE type
 * + Level, which only `runClient`/integration provides).
 */
class CameraVideoOutputTest {

    private val handle = UUID(0x1234_5678L, 0x9abc_def0L)

    @Test fun `camera emitting value carries exactly its handle and decodes back`() {
        // What CameraBlockEntity.videoValue() produces.
        val emitted: PinValue.Video = PinValue.Video(handle)
        assertEquals(handle, emitted.handle)
        // What ScreenBlockEntity.videoHandle() extracts on the consumer end.
        assertEquals(handle, ScreenBlockEntity.decodeHandle(emitted))
    }

    @Test fun `emitting value is the routable VIDEO type and round-trips its codec`() {
        // The Screen's `screen` input pin is PinType.VIDEO (see
        // ScreenBlockEntity.pinInputs); the producer's value must match.
        val emitted = PinValue.Video(handle)
        assertSame(PinType.VIDEO, emitted.type)
        val tag = PinValue.CODEC
            .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, emitted)
            .result().orElseThrow()
        val back = PinValue.CODEC
            .parse(net.minecraft.nbt.NbtOps.INSTANCE, tag)
            .result().orElseThrow()
        assertEquals(emitted, back)
    }

    @Test fun `nil camera handle decodes to null on the consumer`() {
        // Before a Camera mints its handle it is UUID(0,0); the Screen treats
        // that as "no feed" rather than acquiring a dead surface.
        assertNull(ScreenBlockEntity.decodeHandle(PinValue.Video(ScreenBlockEntity.NIL_HANDLE)))
    }
}
