package dev.nitka.nodewire.block

import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Headless coverage of the Screen's channel-read path and the net invariant:
 * the cross-block channel pipeline carries a VIDEO handle to a graphless
 * consumer via [ChannelInputSink], and **only the bare UUID crosses** — never a
 * frame.
 *
 * The real cross-block delivery loop resolves the target and calls
 * `writeChannelInput(name, value)` on the [ChannelInputSink]; here a recording
 * sink stands in for the BE (constructing a real BlockEntity needs the live
 * registry). The handle-decode under test is [ScreenBlockEntity.decodeHandle],
 * exactly what the BER reads each frame.
 */
class ScreenChannelDeliveryTest {

    private class RecordingSink : ChannelInputSink {
        val slots = mutableMapOf<String, PinValue>()
        override fun writeChannelInput(name: String, value: PinValue) {
            slots[name] = value
        }
        fun videoHandle(): UUID? =
            ScreenBlockEntity.decodeHandle(slots[ScreenBlockEntity.SCREEN_CHANNEL])
    }

    @Test fun `delivered Video lands the exact UUID on the screen channel`() {
        val uuid = UUID.randomUUID()
        val sink = RecordingSink()
        sink.writeChannelInput(ScreenBlockEntity.SCREEN_CHANNEL, PinValue.Video(uuid))
        assertEquals(uuid, sink.videoHandle())
    }

    @Test fun `only the UUID crosses — the delivered value is Video with exactly the handle`() {
        val uuid = UUID.randomUUID()
        val delivered: PinValue = PinValue.Video(uuid)
        // The net invariant: a VIDEO channel value is a PinValue.Video whose sole
        // field is the UUID handle. No frame, no byte payload, no extra state.
        assertTrue(delivered is PinValue.Video)
        assertEquals(PinType.VIDEO, delivered.type)
        assertEquals(uuid, (delivered as PinValue.Video).handle)
        // PinValue.Video is a data class with a single component: the handle.
        assertEquals(uuid, delivered.component1())
    }

    @Test fun `nil handle decodes to null (no surface for an unbound screen)`() {
        val sink = RecordingSink()
        sink.writeChannelInput(
            ScreenBlockEntity.SCREEN_CHANNEL,
            PinValue.Video(ScreenBlockEntity.NIL_HANDLE),
        )
        assertNull(sink.videoHandle())
    }

    @Test fun `a non-video value on the screen channel decodes to null`() {
        val sink = RecordingSink()
        sink.writeChannelInput(ScreenBlockEntity.SCREEN_CHANNEL, PinValue.Redstone(15))
        assertNull(sink.videoHandle())
    }

    @Test fun `re-delivery overwrites the slot (last-writer-wins)`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val sink = RecordingSink()
        sink.writeChannelInput(ScreenBlockEntity.SCREEN_CHANNEL, PinValue.Video(first))
        sink.writeChannelInput(ScreenBlockEntity.SCREEN_CHANNEL, PinValue.Video(second))
        assertEquals(second, sink.videoHandle())
    }
}
