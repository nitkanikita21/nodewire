package dev.nitka.nodewire.client.video

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class VideoManagerRefcountTest {
    private lateinit var fake: FakeSurfaceFactory

    @BeforeEach
    fun setUp() {
        fake = FakeSurfaceFactory()
        VideoManager.factory = fake
    }

    @AfterEach
    fun tearDown() {
        VideoManager.resetForTest()
    }

    @Test
    fun acquireTwiceReleaseOnceKeepsRefAndSurface() {
        val h = UUID.randomUUID()
        VideoManager.acquire(h)
        VideoManager.acquire(h)
        VideoManager.getOrCreate(h)
        VideoManager.release(h)

        assertEquals(1, VideoManager.refCount(h))
        // Refs > 0 -> not freed even after a sweep.
        VideoManager.onClientTick()
        assertEquals(0, fake.surfaces.single().destroyCount.get())
    }

    @Test
    fun acquireThenReleaseDropsToZero() {
        val h = UUID.randomUUID()
        VideoManager.acquire(h)
        VideoManager.release(h)
        assertEquals(0, VideoManager.refCount(h))
    }

    @Test
    fun getOrCreateAllocatesExactlyOnce() {
        val h = UUID.randomUUID()
        VideoManager.acquire(h)
        val a = VideoManager.getOrCreate(h)
        val b = VideoManager.getOrCreate(h)
        assertSame(a, b)
        assertEquals(1, fake.createCount.get())
    }

    @Test
    fun releaseUnknownHandleIsNoOp() {
        val h = UUID.randomUUID()
        VideoManager.release(h) // must not throw
        assertEquals(0, VideoManager.refCount(h))
        assertFalse(VideoManager.isTracked(h))
    }

    @Test
    fun releaseBelowZeroNeverGoesNegative() {
        val h = UUID.randomUUID()
        VideoManager.acquire(h)
        VideoManager.release(h)
        VideoManager.release(h)
        assertEquals(0, VideoManager.refCount(h))
    }
}
