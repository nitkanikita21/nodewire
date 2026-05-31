package dev.nitka.nodewire.client.video

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Headless test for the runtime-owned redraw cadence gate (Finding F8). A
 * `frame()` that requests a redraw every client frame must be throttled so it
 * cannot force an unbounded number of full-surface GL passes.
 */
class VideoCadenceTest {

    @AfterEach
    fun tearDown() = VideoCadence.resetForTest()

    @Test
    fun firstDrawAllowed() {
        val h = UUID.randomUUID()
        assertTrue(VideoCadence.shouldDraw(h, nowTick = 0, intervalTicks = 1))
    }

    @Test
    fun backToBackSameTickThrottled() {
        val h = UUID.randomUUID()
        assertTrue(VideoCadence.shouldDraw(h, nowTick = 5, intervalTicks = 1))
        assertFalse(VideoCadence.shouldDraw(h, nowTick = 5, intervalTicks = 1), "same tick is throttled")
    }

    @Test
    fun advancingPastIntervalReEnables() {
        val h = UUID.randomUUID()
        assertTrue(VideoCadence.shouldDraw(h, nowTick = 0, intervalTicks = 3))
        assertFalse(VideoCadence.shouldDraw(h, nowTick = 1, intervalTicks = 3))
        assertFalse(VideoCadence.shouldDraw(h, nowTick = 2, intervalTicks = 3))
        assertTrue(VideoCadence.shouldDraw(h, nowTick = 3, intervalTicks = 3), "interval elapsed -> redraw allowed")
    }

    @Test
    fun perHandleIndependent() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        assertTrue(VideoCadence.shouldDraw(a, nowTick = 0, intervalTicks = 10))
        assertTrue(VideoCadence.shouldDraw(b, nowTick = 0, intervalTicks = 10), "a different handle isn't throttled by a")
    }

    @Test
    fun zeroOrNegativeIntervalTreatedAsOne() {
        val h = UUID.randomUUID()
        assertTrue(VideoCadence.shouldDraw(h, nowTick = 0, intervalTicks = 0))
        assertFalse(VideoCadence.shouldDraw(h, nowTick = 0, intervalTicks = 0))
        assertTrue(VideoCadence.shouldDraw(h, nowTick = 1, intervalTicks = 0))
    }

    @Test
    fun forgetResetsHandle() {
        val h = UUID.randomUUID()
        assertTrue(VideoCadence.shouldDraw(h, nowTick = 0, intervalTicks = 100))
        assertFalse(VideoCadence.shouldDraw(h, nowTick = 1, intervalTicks = 100))
        VideoCadence.forget(h)
        assertTrue(VideoCadence.shouldDraw(h, nowTick = 1, intervalTicks = 100), "forgotten handle draws again")
    }
}
