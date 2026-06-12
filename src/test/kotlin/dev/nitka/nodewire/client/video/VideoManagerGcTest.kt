package dev.nitka.nodewire.client.video

import dev.nitka.nodewire.client.video.VideoManager.AGE_OUT_TICKS
import dev.nitka.nodewire.client.video.VideoManager.GRACE_TICKS
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class VideoManagerGcTest {
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

    private fun sweep(n: Int) = repeat(n) { VideoManager.onClientTick() }

    @Test
    fun graceWindowDefersFreeUntilExpiry() {
        val h = UUID.randomUUID()
        VideoManager.acquire(h)
        VideoManager.getOrCreate(h)
        VideoManager.release(h) // refs -> 0, grace starts at current tick

        sweep(GRACE_TICKS - 1)
        assertEquals(0, fake.surfaces.single().destroyCount.get(), "not freed before grace expires")
        assertTrue(VideoManager.isTracked(h))

        sweep(1) // grace window reaches GRACE_TICKS
        assertEquals(1, fake.surfaces.single().destroyCount.get(), "freed once grace expires")
        assertFalse(VideoManager.isTracked(h), "map entry dropped on free")
    }

    @Test
    fun reAcquireWithinGraceSurvives() {
        val h = UUID.randomUUID()
        VideoManager.acquire(h)
        VideoManager.getOrCreate(h)
        VideoManager.release(h)

        sweep(GRACE_TICKS - 5)
        VideoManager.acquire(h) // node remove+re-add within grace

        sweep(GRACE_TICKS + 10)
        assertEquals(0, fake.surfaces.single().destroyCount.get(), "survives remove+re-add")
        assertTrue(VideoManager.isAllocated(h))
        assertEquals(1, VideoManager.refCount(h))
    }

    @Test
    fun ageOutFreesAndReAllocates() {
        val h = UUID.randomUUID()
        VideoManager.acquire(h) // refs stays 1
        VideoManager.getOrCreate(h)

        sweep(AGE_OUT_TICKS + 1) // never touched -> aged out
        assertEquals(1, fake.surfaces[0].destroyCount.get())
        assertFalse(VideoManager.isAllocated(h))
        assertFalse(VideoManager.isTracked(h))

        // A later getOrCreate re-allocates cleanly.
        val s2 = VideoManager.getOrCreate(h)
        assertEquals(2, fake.createCount.get())
        assertEquals(0, (s2 as FakeVideoSurface).destroyCount.get())
    }

    @Test
    fun doubleFreeGuardDestroysExactlyOnce() {
        val h = UUID.randomUUID()
        VideoManager.acquire(h)
        VideoManager.getOrCreate(h)
        VideoManager.release(h)

        sweep(GRACE_TICKS) // frees
        sweep(GRACE_TICKS) // extra sweeps must not re-destroy
        assertEquals(1, fake.surfaces.single().destroyCount.get())
    }

    @Test
    fun `idle orphan frees after grace, not on the next sweep`() {
        val h = UUID.randomUUID()
        // No acquire — pure orphan handle, touched exactly once.
        VideoManager.getOrCreate(h)
        assertTrue(VideoManager.isAllocated(h))

        // Within the grace window the surface must SURVIVE (it may be a chain
        // intermediate between two script frames).
        sweep(GRACE_TICKS - 1)
        assertTrue(VideoManager.isAllocated(h))

        // Past the grace window without a touch — freed.
        sweep(1)
        assertEquals(1, fake.surfaces.single().destroyCount.get())
        assertFalse(VideoManager.isTracked(h))
    }

    @Test
    fun `actively-blitted orphan survives indefinitely (script chain intermediate)`() {
        val h = UUID.randomUUID()
        VideoManager.getOrCreate(h)
        // Script A draws into it / script B blits from it every frame: each
        // frame touches via getOrCreate. Run far past every GC horizon.
        repeat(AGE_OUT_TICKS + 100) {
            VideoManager.getOrCreate(h)
            VideoManager.onClientTick()
        }
        assertTrue(VideoManager.isAllocated(h))
        assertEquals(0, fake.surfaces.single().destroyCount.get())
        assertEquals(1, fake.createCount.get(), "surface must never be destroyed+recreated mid-chain")
    }
}
