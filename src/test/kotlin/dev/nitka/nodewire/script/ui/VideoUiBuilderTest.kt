package dev.nitka.nodewire.script.ui

import dev.nitka.nodewire.script.Video
import dev.nitka.nodewire.script.VideoCanvas
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `ui {}` DSL builder is pure data: it must produce the declared [UiSpec]
 * tree and hand it to [VideoCanvas.renderUi] exactly once. The flexbox engine
 * itself is client-side (Yoga) and exercised in-game; here we pin the
 * script-facing contract.
 */
class VideoUiBuilderTest {

    private class CapturingCanvas : VideoCanvas {
        var captured: UiSpec? = null
        var renderCalls = 0
        override fun width(): Int = 100
        override fun height(): Int = 100
        override fun clear(color: Long) {}
        override fun rect(x: Int, y: Int, w: Int, h: Int, color: Long) {}
        override fun border(x: Int, y: Int, w: Int, h: Int, thickness: Int, color: Long) {}
        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, color: Long) {}
        override fun text(s: String, x: Int, y: Int, color: Long) {}
        override fun image(video: Video, x: Int, y: Int, w: Int, h: Int) {}
        override fun renderUi(root: UiSpec) {
            captured = root
            renderCalls++
        }
    }

    @Test
    fun `builder produces the declared spec tree and renders once`() {
        val canvas = CapturingCanvas()
        val feed = Video(java.util.UUID(1L, 2L))
        canvas.ui(pad = 2, gap = 3, justify = Justify.Center) {
            row(justify = Justify.SpaceBetween, bg = 0xFF000000L) {
                text("RWS-01", 0xFF8BE08BL)
                text("ZOOM")
            }
            spacer()
            image(feed, height = 20)
            rect(4, 4, 0xFFFF3B3BL)
        }

        assertEquals(1, canvas.renderCalls)
        val root = assertNotNull(canvas.captured)
        assertEquals(UiKind.CONTAINER, root.kind)
        assertTrue(root.vertical)
        assertEquals(2, root.pad)
        assertEquals(3, root.gap)
        assertEquals(Justify.Center, root.justify)
        assertEquals(4, root.children.size)

        val header = root.children[0]
        assertEquals(UiKind.CONTAINER, header.kind)
        assertTrue(!header.vertical)
        assertEquals(Justify.SpaceBetween, header.justify)
        assertEquals(0xFF000000L, header.bg)
        assertEquals(listOf("RWS-01", "ZOOM"), header.children.map { it.text })
        assertEquals(0xFF8BE08BL, header.children[0].textColor)

        val spacer = root.children[1]
        assertEquals(UiKind.CONTAINER, spacer.kind)
        assertEquals(1f, spacer.grow)

        val image = root.children[2]
        assertEquals(UiKind.IMAGE, image.kind)
        assertEquals(feed, image.video)
        assertEquals(20, image.height)
        assertEquals(1f, image.grow)

        val dot = root.children[3]
        assertEquals(UiKind.CONTAINER, dot.kind)
        assertEquals(4, dot.width)
        assertEquals(0xFFFF3B3BL, dot.bg)
    }

    private fun assertNotNull(value: UiSpec?): UiSpec {
        org.junit.jupiter.api.Assertions.assertNotNull(value)
        return value!!
    }
}
