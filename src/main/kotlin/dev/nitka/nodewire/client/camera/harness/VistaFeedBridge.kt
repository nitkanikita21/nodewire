package dev.nitka.nodewire.client.camera.harness

import com.mojang.logging.LogUtils
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

/**
 * Renders our camera feeds through **Vista's** level renderer instead of our
 * own.
 *
 * Vista solved the same problem for its own cameras, and in this modpack its
 * feeds are demonstrably clean — its render envelope knows how to coexist
 * with Sodium, Iris, Veil, Flywheel and DH. Rather than keep re-deriving that
 * envelope, we ask Vista to draw the world from our camera's pose into one of
 * its feed textures and then copy that texture into our own video surface, so
 * everything downstream (Screen blocks, AR HUD, script `image()`, the CRT
 * pass) keeps working untouched.
 *
 * This is pure runtime interop: we call public methods on classes Vista ships
 * — no Vista code is copied, adapted or redistributed, and Vista is never
 * bundled. When Vista is absent (or its internals move) we fail open and our
 * own capture path takes over.
 *
 * The pieces used, all public:
 *  * `LiveFeedTexture(ResourceLocation, int, int, UUID)` — a feed-sized
 *    render texture. Safe to own: its self-refresh looks up a Vista
 *    ViewFinder by UUID, finds none for ours, and returns.
 *  * `VistaLevelRenderer.render(texture, token, cameraSetup, fov,
 *    applyPostChain, customProjection, bfsStart, renderDistance)`.
 *  * `SceneCameraSetup` — a single-method interface, supplied as a dynamic
 *    proxy that poses Vista's dummy camera at our camera block.
 *  * `RenderableDynamicTexture.getId()` (Moonlight) — the GL texture to copy.
 */
object VistaFeedBridge {

    private val LOG = LogUtils.getLogger()

    private var resolved = false
    private var ok = false

    private var textureCtor: Constructor<*>? = null
    private var renderMethod: Method? = null
    private var setupInterface: Class<*>? = null
    private var getIdMethod: Method? = null
    private var getRenderTargetMethod: Method? = null
    private var closeMethod: Method? = null

    private class Entry(val texture: Any, val width: Int, val height: Int)

    private val textures = HashMap<UUID, Entry>()
    private var loggedEngaged = false

    @Synchronized
    private fun resolveOnce() {
        if (resolved) return
        resolved = true
        try {
            if (!net.neoforged.fml.ModList.get().isLoaded("vista")) return
            val texCls = Class.forName("net.mehvahdjukaar.vista.client.textures.LiveFeedTexture")
            textureCtor = texCls.getConstructor(
                ResourceLocation::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                UUID::class.java,
            )
            getIdMethod = texCls.methods.firstOrNull { it.name == "getId" && it.parameterCount == 0 }
            getRenderTargetMethod = texCls.methods
                .firstOrNull { it.name == "getRenderTarget" && it.parameterCount == 0 }
            closeMethod = texCls.methods.firstOrNull { it.name == "close" && it.parameterCount == 0 }
            setupInterface = Class.forName("net.mehvahdjukaar.vista.client.renderer.SceneCameraSetup")
            val rendererCls = Class.forName("net.mehvahdjukaar.vista.client.renderer.VistaLevelRenderer")
            renderMethod = rendererCls.methods.firstOrNull { it.name == "render" && it.parameterCount == 8 }
            ok = textureCtor != null && getIdMethod != null && setupInterface != null && renderMethod != null
            if (ok) {
                LOG.info("[NW-CAMERA] Vista feed bridge resolved — feeds will render through Vista")
            } else {
                LOG.warn("[NW-CAMERA] Vista present but its feed API did not resolve; using our own capture path")
            }
        } catch (t: Throwable) {
            ok = false
            LOG.warn("[NW-CAMERA] Vista feed bridge unavailable: {}", t.toString())
        }
    }

    fun available(): Boolean {
        resolveOnce()
        return ok
    }

    /**
     * Draw the world from [pos]/[yawDeg]/[pitchDeg] into Vista's texture for
     * [handle] and return that texture's GL id, or -1 on any failure.
     */
    fun render(
        handle: UUID,
        width: Int,
        height: Int,
        marker: Entity,
        pos: Vec3,
        yawDeg: Float,
        pitchDeg: Float,
        fovDeg: Float,
    ): Int {
        resolveOnce()
        if (!ok) return -1
        val ctor = textureCtor ?: return -1
        val render = renderMethod ?: return -1
        val setupIface = setupInterface ?: return -1
        val getId = getIdMethod ?: return -1
        val level = Minecraft.getInstance().level ?: return -1

        val getRenderTarget = getRenderTargetMethod ?: return -1

        return runCatching {
            var entry = textures[handle]
            if (entry == null || entry.width != width || entry.height != height) {
                entry?.let { old -> runCatching { closeMethod?.invoke(old.texture) } }
                val id = ResourceLocation.fromNamespaceAndPath(
                    "nodewire",
                    "camera_feed/" + handle.toString().replace('-', '_'),
                )
                entry = Entry(ctor.newInstance(id, width, height, handle), width, height)
                textures[handle] = entry
            }

            val setup = Proxy.newProxyInstance(
                setupIface.classLoader,
                arrayOf(setupIface),
            ) { _, method, args ->
                if (method.name == "setup" && args != null && args.size == 2) {
                    val camera = args[0]
                    val partialTicks = (args[1] as? Float) ?: 1f
                    // Vista hands us its own dummy camera; pose it exactly the
                    // way its own view finders do — entity for the level
                    // renderer to key off, then explicit position + rotation.
                    runCatching {
                        camera.javaClass.methods
                            .firstOrNull { it.name == "setup" && it.parameterCount == 5 }
                            ?.invoke(camera, level, marker, false, false, partialTicks)
                    }
                    runCatching {
                        camera.javaClass.methods
                            .firstOrNull { m -> m.name == "setPosition" && m.parameterCount == 1 && m.parameterTypes[0] == Vec3::class.java }
                            ?.invoke(camera, pos)
                    }
                    runCatching {
                        camera.javaClass.methods
                            .firstOrNull { m -> m.name == "setRotation" && m.parameterCount == 2 }
                            ?.invoke(camera, yawDeg, pitchDeg)
                    }
                    null
                } else {
                    when (method.name) {
                        "toString" -> "NodewireSceneCameraSetup"
                        "hashCode" -> System.identityHashCode(this)
                        "equals" -> false
                        else -> null
                    }
                }
            }

            // (texture, token, cameraSetup, fov, applyPostChain, customProjection,
            //  bfsStartOverride, renderDistanceOverride)
            // getId() also lazily allocates both buffers; getRenderTarget()
            // does not, so ask for it first or Vista draws into a null target.
            getId.invoke(entry.texture)

            render.invoke(null, entry.texture, handle, setup, fovDeg, false, null, null, null)

            if (!loggedEngaged) {
                loggedEngaged = true
                LOG.info("[NW-CAMERA] Vista feed bridge engaged")
            }
            val target = getRenderTarget.invoke(entry.texture) as? com.mojang.blaze3d.pipeline.RenderTarget
            if (target != null && CaptureDebug.isArmed()) {
                // Saved BEFORE our copy: separates "Vista drew nothing" from
                // "we failed to bring its picture across".
                CaptureDebug.dumpTarget("vista-source", target)
            }
            target?.colorTextureId ?: -1
        }.getOrElse {
            LOG.warn("[NW-CAMERA] Vista feed render failed, falling back: {}", it.toString())
            ok = false
            -1
        }
    }

    /** Free textures for feeds that no longer exist. */
    fun prune(live: Set<UUID>) {
        if (textures.isEmpty()) return
        val it = textures.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in live) {
                runCatching { closeMethod?.invoke(e.value.texture) }
                it.remove()
            }
        }
    }
}
