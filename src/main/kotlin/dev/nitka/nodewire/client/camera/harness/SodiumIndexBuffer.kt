package dev.nitka.nodewire.client.camera.harness

import com.mojang.logging.LogUtils

/**
 * Pre-grows Sodium's shared quad index buffer once, so a capture pass can
 * never trigger a reallocation.
 *
 * The opaque terrain path calls
 * `SharedQuadIndexBuffer.ensureCapacity(commandList, batch.getIndexBufferSize())`
 * before every region draw, and growth reallocates the buffer's storage and
 * rewrites it through an UNSYNCHRONIZED mapping — while the GPU may still be
 * a frame or two behind, executing draws that read it. A feed camera sitting
 * right on top of geometry produces much larger merged draw ranges than the
 * player's view, so feeds are exactly what pushes the buffer over its high
 * water mark mid-frame.
 *
 * Growing it once, up front, to more than any single draw can need removes
 * that entirely. Reflection throughout (Sodium ships inside a jarJar) and
 * fail-open: on any error we simply leave Sodium to manage it.
 */
object SodiumIndexBuffer {

    private val LOG = LogUtils.getLogger()

    /** Elements for one fully-merged section draw, with generous headroom. */
    private const val ELEMENTS = 6 * 1024 * 1024

    @Volatile
    private var done = false

    fun pregrow() {
        if (done) return
        done = true
        runCatching {
            val swrCls = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer")
            val swr = swrCls.getMethod("instanceNullable").invoke(null) ?: run { done = false; return }
            val rsm = swrCls.getDeclaredField("renderSectionManager").also { it.isAccessible = true }.get(swr)
                ?: run { done = false; return }
            val renderer = rsm.javaClass.getDeclaredField("chunkRenderer").also { it.isAccessible = true }.get(rsm)
                ?: return
            val bufField = renderer.javaClass.getDeclaredField("sharedIndexBuffer").also { it.isAccessible = true }
            val buffer = bufField.get(renderer) ?: return

            val deviceCls = Class.forName("net.caffeinemc.mods.sodium.client.gl.device.RenderDevice")
            val device = deviceCls.getField("INSTANCE").get(null)
            val commandList = deviceCls.getMethod("createCommandList").invoke(device)

            val ensure = buffer.javaClass.methods.firstOrNull {
                it.name == "ensureCapacity" && it.parameterCount == 2
            } ?: return
            ensure.invoke(buffer, commandList, ELEMENTS)
            runCatching { commandList.javaClass.getMethod("flush").invoke(commandList) }
            LOG.info("[NW-CAMERA] Sodium shared index buffer pre-grown to {} elements", ELEMENTS)
        }.onFailure {
            LOG.warn("[NW-CAMERA] shared index buffer pre-grow failed: {}", it.toString())
        }
    }
}
