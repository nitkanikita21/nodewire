package dev.nitka.nodewire.script

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Headless test for the sandbox-safe `draw {}` buffering on [ScriptModule]
 * (Phase B). The script's `frame()` body only BUFFERS a (handle, closure) pair;
 * no GL is touched. The host drains the buffer and replays each closure on the
 * render thread later. This test proves the buffer-and-drain half end-to-end
 * with a no-GL recording [VideoCanvas] — the GL replay itself is `runClient`.
 */
class VideoDrawBufferTest {

    /** A no-GL canvas that records the verbs the replayed closure calls. */
    private class RecordingCanvas : VideoCanvas {
        val ops = mutableListOf<String>()
        override fun width() = 256
        override fun height() = 256
        override fun clear(color: Long) { ops += "clear" }
        override fun rect(x: Int, y: Int, w: Int, h: Int, color: Long) { ops += "rect($x,$y,$w,$h)" }
        override fun border(x: Int, y: Int, w: Int, h: Int, thickness: Int, color: Long) { ops += "border" }
        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, color: Long) { ops += "line" }
        override fun text(s: String, x: Int, y: Int, color: Long) { ops += "text($s)" }
    }

    private class M(val target: Video) : ScriptModule() {
        init {
            draw(target) {
                clear(0xFF000000L)
                rect(10, 10, 20, 20, 0xFFFF0000L)
                text("hi", 0, 0, 0xFFFFFFFFL)
            }
        }
    }

    @Test
    fun drawBuffersClosureAndDrainReplaysIt() {
        val handle = UUID.randomUUID()
        val m = M(Video(handle))

        val drained = m.drainVideoDraws()
        assertEquals(1, drained.size, "exactly one buffered draw")
        assertEquals(handle, drained.single().handle, "carries the target handle")

        // Replay the closure against a recording canvas — what the render-thread
        // VideoFrameRenderer would do, minus GL.
        val canvas = RecordingCanvas()
        drained.single().block(canvas)
        assertEquals(listOf("clear", "rect(10,10,20,20)", "text(hi)"), canvas.ops)
    }

    @Test
    fun drainIsOneShot() {
        val m = M(Video(UUID.randomUUID()))
        assertTrue(m.drainVideoDraws().isNotEmpty())
        assertTrue(m.drainVideoDraws().isEmpty(), "second drain is empty")
    }

    @Test
    fun bufferIsCappedPerFrame() {
        val h = Video(UUID.randomUUID())
        val m = object : ScriptModule() {
            init { repeat(10_000) { draw(h) { } } }
        }
        assertTrue(m.drainVideoDraws().size <= 64, "draw buffer is capped so a runaway loop can't balloon it")
    }
}
