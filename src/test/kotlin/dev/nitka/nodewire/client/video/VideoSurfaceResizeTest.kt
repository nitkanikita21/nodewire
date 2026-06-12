package dev.nitka.nodewire.client.video

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Consumer-driven surface dims (multiblock panels): [VideoManager.requestSurfaceSize]
 * must resize a LIVE surface (destroy + realloc at the new dims) and steer the
 * lazy alloc of a not-yet-created one. [VideoManager.sizeForSpan] keeps the
 * aspect while capping the longest side.
 */
class VideoSurfaceResizeTest {

    private val factory = FakeSurfaceFactory()

    @AfterEach
    fun tearDown() = VideoManager.resetForTest()

    private fun install() {
        VideoManager.resetForTest()
        VideoManager.factory = factory
    }

    @Test
    fun `request before first alloc steers creation dims`() {
        install()
        val h = UUID.randomUUID()
        VideoManager.requestSurfaceSize(h, 768, 512)
        val s = VideoManager.getOrCreate(h)
        assertEquals(768, s.width)
        assertEquals(512, s.height)
        assertEquals(1, factory.createCount.get())
    }

    @Test
    fun `request on a live surface destroys and reallocates`() {
        install()
        val h = UUID.randomUUID()
        val s1 = VideoManager.getOrCreate(h) as FakeVideoSurface
        assertEquals(VideoManager.STANDARD_SIZE, s1.width)

        VideoManager.requestSurfaceSize(h, 512, 256)
        assertEquals(1, s1.destroyCount.get(), "old surface must be freed")
        val s2 = VideoManager.getOrCreate(h)
        assertEquals(512, s2.width)
        assertEquals(256, s2.height)
    }

    @Test
    fun `same dims request is a no-op`() {
        install()
        val h = UUID.randomUUID()
        VideoManager.requestSurfaceSize(h, 256, 256)
        val s1 = VideoManager.getOrCreate(h) as FakeVideoSurface
        VideoManager.requestSurfaceSize(h, 256, 256)
        assertEquals(0, s1.destroyCount.get())
        assertEquals(1, factory.createCount.get())
    }

    @Test
    fun `sizeForSpan keeps aspect and caps the longest side`() {
        assertArrayEquals(intArrayOf(256, 256), VideoManager.sizeForSpan(1, 1))
        assertArrayEquals(intArrayOf(768, 512), VideoManager.sizeForSpan(3, 2))
        // 8×8 → longest side 2048 → scaled to the 1024 cap, both axes together.
        assertArrayEquals(intArrayOf(1024, 1024), VideoManager.sizeForSpan(8, 8))
        // 8×1 → 2048×256 → scale 0.5 → 1024×128.
        assertArrayEquals(intArrayOf(1024, 128), VideoManager.sizeForSpan(8, 1))
    }
}
