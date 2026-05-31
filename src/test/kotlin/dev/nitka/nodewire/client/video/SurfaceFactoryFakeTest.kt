package dev.nitka.nodewire.client.video

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Sanity-checks the headless fake reused across the VideoManager suite. */
class SurfaceFactoryFakeTest {
    @Test
    fun factoryHonorsSizeAndTracksAllocations() {
        val factory = FakeSurfaceFactory()
        val s = factory.create(256, 256)
        assertEquals(256, s.width)
        assertEquals(256, s.height)
        assertEquals(1, factory.createCount.get())
        assertEquals(1, factory.surfaces.size)
    }

    @Test
    fun destroyCounterWorks() {
        val s = FakeVideoSurface(256, 256)
        assertEquals(0, s.destroyCount.get())
        s.destroy()
        s.destroy()
        assertEquals(2, s.destroyCount.get())
    }
}
