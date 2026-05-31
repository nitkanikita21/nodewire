package dev.nitka.nodewire.client.video

import java.util.concurrent.atomic.AtomicInteger

/**
 * Headless stand-in for the GL surface — records size and counts [destroy]
 * calls. Reused by every VideoManager test so the refcount/GC state machine can
 * be exercised without a GL context.
 */
class FakeVideoSurface(
    override val width: Int,
    override val height: Int,
) : VideoSurface {
    val destroyCount = AtomicInteger(0)

    override fun destroy() {
        destroyCount.incrementAndGet()
    }
}

/** Counts allocations and hands out [FakeVideoSurface]s. */
class FakeSurfaceFactory : SurfaceFactory {
    val createCount = AtomicInteger(0)
    val surfaces = mutableListOf<FakeVideoSurface>()

    override fun create(width: Int, height: Int): VideoSurface {
        createCount.incrementAndGet()
        val s = FakeVideoSurface(width, height)
        surfaces.add(s)
        return s
    }
}
