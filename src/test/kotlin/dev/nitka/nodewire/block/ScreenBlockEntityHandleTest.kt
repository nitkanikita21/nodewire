package dev.nitka.nodewire.block

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Headless coverage of the Screen's endpoint-only refcount discipline via the
 * pure [ScreenHandleTracker] (no BlockEntity, no Level, no GL). Verifies the
 * `release(old); acquire(new)` transitions the Screen BE drives on the client
 * when the delivered VIDEO handle changes, including nil↔handle (the nil handle
 * is normalised to `null` by `ScreenBlockEntity.decodeHandle` before reaching
 * the tracker).
 */
class ScreenBlockEntityHandleTest {

    private class RecordingRefcounter : ScreenHandleTracker.Refcounter {
        val acquired = mutableListOf<UUID>()
        val released = mutableListOf<UUID>()
        override fun acquire(handle: UUID) { acquired += handle }
        override fun release(handle: UUID) { released += handle }
    }

    private val h1 = UUID(1L, 1L)
    private val h2 = UUID(2L, 2L)

    @Test fun `nil to handle acquires only`() {
        val rc = RecordingRefcounter()
        val t = ScreenHandleTracker(rc)
        t.onHandle(h1)
        assertEquals(listOf(h1), rc.acquired)
        assertEquals(emptyList<UUID>(), rc.released)
        assertEquals(h1, t.held())
    }

    @Test fun `handle to nil releases only`() {
        val rc = RecordingRefcounter()
        val t = ScreenHandleTracker(rc)
        t.onHandle(h1)
        t.onHandle(null)
        assertEquals(listOf(h1), rc.acquired)
        assertEquals(listOf(h1), rc.released)
        assertEquals(null, t.held())
    }

    @Test fun `handle to different handle releases old then acquires new`() {
        val rc = RecordingRefcounter()
        val t = ScreenHandleTracker(rc)
        t.onHandle(h1)
        t.onHandle(h2)
        assertEquals(listOf(h1, h2), rc.acquired)
        assertEquals(listOf(h1), rc.released)
        assertEquals(h2, t.held())
    }

    @Test fun `same handle does not churn the refcount`() {
        val rc = RecordingRefcounter()
        val t = ScreenHandleTracker(rc)
        t.onHandle(h1)
        t.onHandle(h1)
        t.onHandle(h1)
        assertEquals(listOf(h1), rc.acquired)
        assertEquals(emptyList<UUID>(), rc.released)
    }

    @Test fun `unload releases the held handle`() {
        val rc = RecordingRefcounter()
        val t = ScreenHandleTracker(rc)
        t.onHandle(h1)
        t.onUnload()
        assertEquals(listOf(h1), rc.released)
        assertEquals(null, t.held())
    }

    @Test fun `unload with no handle is a no-op`() {
        val rc = RecordingRefcounter()
        val t = ScreenHandleTracker(rc)
        t.onUnload()
        assertEquals(emptyList<UUID>(), rc.acquired)
        assertEquals(emptyList<UUID>(), rc.released)
    }

    @Test fun `nil handle value decodes to null so tracker never acquires it`() {
        // PinValue.default(VIDEO) is UUID(0,0); decodeHandle maps it to null,
        // so a Screen that only ever sees the nil handle never acquires.
        assertEquals(null, ScreenBlockEntity.decodeHandle(
            dev.nitka.nodewire.graph.PinValue.Video(ScreenBlockEntity.NIL_HANDLE),
        ))
    }
}
