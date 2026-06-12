package dev.nitka.nodewire.script

import dev.nitka.nodewire.graph.PinValue
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `output<Video>` handles are SERVER-MINTED per node (random, carried by the
 * hidden `__vout.` replicated cell). The old name-derived scheme
 * (`nameUUIDFromBytes("nodewire-video:out")`) collided across nodes: every
 * script in the world with a pin named "out" drew into the SAME surface, so a
 * screen showed whichever node drew last ("стара картинка" after re-wiring).
 */
class VideoOutputHandleTest {

    private class M : ScriptModule()

    private val nil = UUID(0L, 0L)

    @Test
    fun `two nodes with the same pin name get distinct handles`() {
        val m1 = M().apply { output<Video>("out") }
        val m2 = M().apply { output<Video>("out") }
        m1.pushInputs(emptyMap()) // server tick hook → seeds
        m2.pushInputs(emptyMap())

        val h1 = (m1.outputs["out"] as Video).handle
        val h2 = (m2.outputs["out"] as Video).handle
        assertNotEquals(nil, h1)
        assertNotEquals(nil, h2)
        assertNotEquals(h1, h2, "name-derived handles collide across nodes")
    }

    @Test
    fun `handle persists through state save-load (screens keep their binding)`() {
        val m1 = M().apply { output<Video>("out") }
        m1.pushInputs(emptyMap())
        val h1 = (m1.outputs["out"] as Video).handle

        val tag = CompoundTag()
        m1.saveState(tag)

        // Fresh module (recompile / world reload) over the same node state.
        val m2 = M().apply { output<Video>("out") }
        m2.loadState(tag)
        m2.pushInputs(emptyMap())
        assertEquals(h1, (m2.outputs["out"] as Video).handle)
    }

    @Test
    fun `pin emits the minted handle`() {
        val m = M().apply { output<Video>("out") }
        m.pushInputs(emptyMap())
        val pin = m.pullOutputs()["out"]
        assertEquals((m.outputs["out"] as Video).handle, (pin as PinValue.Video).handle)
    }

    @Test
    fun `disconnected video input resets to nil instead of keeping the old feed`() {
        val m = M()
        m.input<Video>("video")
        val live = UUID.randomUUID()
        m.pushInputs(mapOf("video" to PinValue.Video(live)))
        assertEquals(live, (m.inputs["video"] as Video).handle)

        // Edge removed → no incoming value → must fall back to nil, not stick.
        m.pushInputs(emptyMap())
        assertEquals(nil, (m.inputs["video"] as Video).handle)
        // And the hidden replicated mirror follows (client stops drawing it).
        val mirror = m.stateCells.first { it.key == ScriptModule.videoMirrorKey("video") }
        assertEquals(nil, (mirror.value as Video).handle)
    }
}
