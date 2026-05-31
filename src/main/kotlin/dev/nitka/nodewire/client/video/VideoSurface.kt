package dev.nitka.nodewire.client.video

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import net.minecraft.client.Minecraft

/**
 * Abstraction the [VideoManager] tracks for each Video handle. Deliberately
 * exposes **no GL types** so the manager's refcount/GC bookkeeping is
 * unit-testable headless (a fake surface stands in for the real FBO).
 *
 * The production implementation is [GlVideoSurface]; tests substitute a fake.
 */
interface VideoSurface {
    val width: Int
    val height: Int

    /** Free the underlying resources. Must be idempotent-safe at the manager level (the manager guarantees a single call). */
    fun destroy()
}

/**
 * Injectable seam that allocates a [VideoSurface]. The production factory is
 * [GlSurfaceFactory]; tests swap in a fake via [VideoManager.factory] so no GL
 * context is required.
 */
interface SurfaceFactory {
    fun create(width: Int, height: Int): VideoSurface
}

/**
 * GL-backed surface: a [TextureTarget] owning a colour+depth FBO. The
 * [TextureTarget] is constructed **lazily on first [target] access from the
 * render thread** — GL objects are created on the thread that first binds them,
 * and both the draw API and the BER run on the render thread, so first-touch
 * alloc there is render-thread-safe.
 *
 * **Never instantiated in tests** (it would touch GL).
 */
class GlVideoSurface(
    override val width: Int,
    override val height: Int,
) : VideoSurface {
    private var renderTarget: TextureTarget? = null

    /** Lazily allocate the FBO on the calling (render) thread. */
    fun target(): RenderTarget {
        var t = renderTarget
        if (t == null) {
            t = TextureTarget(width, height, /* useDepth = */ true, /* onMac = */ false)
            renderTarget = t
        }
        return t
    }

    /** Raw GL colour-texture id for blitting onto a face. Forces alloc if not yet bound. */
    fun colorTextureId(): Int = target().colorTextureId

    override fun destroy() {
        val t = renderTarget ?: return
        renderTarget = null
        // FBO teardown must run on the render thread.
        Minecraft.getInstance().execute { t.destroyBuffers() }
    }
}

/** Production factory — allocates a [GlVideoSurface] (FBO alloc deferred to first bind). */
object GlSurfaceFactory : SurfaceFactory {
    override fun create(width: Int, height: Int): VideoSurface = GlVideoSurface(width, height)
}
