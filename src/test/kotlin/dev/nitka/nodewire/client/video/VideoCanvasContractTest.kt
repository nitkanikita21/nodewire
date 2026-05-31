package dev.nitka.nodewire.client.video

import dev.nitka.nodewire.script.VideoCanvas
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Headless contract test for the [VideoCanvas] DoS clamps (Finding F5). Drives
 * the SAME pure [VideoDrawClamps] the GL-backed impl uses, plus a no-GL
 * [RecordingVideoCanvas] that applies those clamps and records the resulting
 * verbs — so a 2-billion-px rect / over-long text are provably normalised
 * before they could reach a GL primitive.
 */
class VideoCanvasContractTest {

    private val size = VideoManager.STANDARD_SIZE

    /** Records the post-clamp verbs (no GL). Shares [VideoDrawClamps] with the real impl. */
    private class RecordingVideoCanvas(val size: Int) : VideoCanvas {
        data class RectCall(val x: Int, val y: Int, val w: Int, val h: Int)
        val rects = mutableListOf<RectCall>()
        val texts = mutableListOf<String>()
        var borderThickness: Int? = null

        override fun width() = size
        override fun height() = size
        override fun clear(color: Long) { rects += RectCall(0, 0, size, size) }
        override fun rect(x: Int, y: Int, w: Int, h: Int, color: Long) {
            val r = VideoDrawClamps.rect(x, y, w, h, size)
            rects += RectCall(r.x, r.y, r.w, r.h)
        }
        override fun border(x: Int, y: Int, w: Int, h: Int, thickness: Int, color: Long) {
            val r = VideoDrawClamps.rect(x, y, w, h, size)
            borderThickness = VideoDrawClamps.thickness(thickness, size)
            rects += RectCall(r.x, r.y, r.w, r.h)
        }
        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, color: Long) {}
        override fun text(s: String, x: Int, y: Int, color: Long) { texts += VideoDrawClamps.text(s) }
    }

    @Test
    fun hugeRectIsClampedToSurface() {
        val c = RecordingVideoCanvas(size)
        c.rect(-1000, -1000, Int.MAX_VALUE, Int.MAX_VALUE, 0xFFFFFFFFL)
        val r = c.rects.single()
        assertEquals(0, r.x)
        assertEquals(0, r.y)
        assertEquals(size, r.w, "width clamped to surface")
        assertEquals(size, r.h, "height clamped to surface")
    }

    @Test
    fun rectOriginPlusSpanDoesNotOverflow() {
        // x near MAX_VALUE + a large w must not wrap negative.
        val r = VideoDrawClamps.rect(Int.MAX_VALUE - 10, 0, 1000, 10, size)
        assertTrue(r.x in 0..size)
        assertTrue(r.w in 0..size)
        assertEquals(size, r.x, "origin past the surface clamps to the far edge")
        assertEquals(0, r.w)
    }

    @Test
    fun overLongTextIsTruncated() {
        val c = RecordingVideoCanvas(size)
        c.text("a".repeat(10_000), 0, 0, 0xFFFFFFFFL)
        assertEquals(VideoDrawClamps.MAX_TEXT_LEN, c.texts.single().length)
    }

    @Test
    fun borderThicknessClampedToAtLeastOneAndAtMostSize() {
        val c = RecordingVideoCanvas(size)
        c.border(0, 0, 10, 10, -5, 0xFFFFFFFFL)
        assertEquals(1, c.borderThickness)
        c.border(0, 0, 10, 10, Int.MAX_VALUE, 0xFFFFFFFFL)
        assertEquals(size, c.borderThickness)
    }

    @Test
    fun inBoundsRectPassesThrough() {
        val r = VideoDrawClamps.rect(10, 20, 30, 40, size)
        assertEquals(VideoDrawClamps.Rect(10, 20, 30, 40), r)
    }

    @Test
    fun coordClampedToSurfaceRange() {
        assertEquals(0, VideoDrawClamps.coord(-99, size))
        assertEquals(size, VideoDrawClamps.coord(Int.MAX_VALUE, size))
        assertEquals(7, VideoDrawClamps.coord(7, size))
    }
}
