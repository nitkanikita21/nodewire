package dev.nitka.nodewire.client.highlight

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import dev.nitka.nodewire.endpoint.EndpointRef
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import org.joml.Matrix4f
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.sin

object BlockHighlightRenderer {

    private val active = ConcurrentHashMap<EndpointRef, Long>()

    private object Shards : RenderStateShard("", Runnable {}, Runnable {}) {
        val POSITION_COLOR = POSITION_COLOR_SHADER
        val NO_LIGHTMAP_S = NO_LIGHTMAP
        val TRANSLUCENT = TRANSLUCENT_TRANSPARENCY
        val NO_CULL_S = NO_CULL
        val NO_DEPTH = NO_DEPTH_TEST
        val COLOR_DEPTH = COLOR_DEPTH_WRITE
    }

    private val HIGHLIGHT_TYPE: RenderType = RenderType.create(
        "nodewire_highlight",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        256,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(Shards.POSITION_COLOR)
            .setLightmapState(Shards.NO_LIGHTMAP_S)
            .setTransparencyState(Shards.TRANSLUCENT)
            .setCullState(Shards.NO_CULL_S)
            .setDepthTestState(Shards.NO_DEPTH)
            .setWriteMaskState(Shards.COLOR_DEPTH)
            .createCompositeState(false),
    )

    fun highlight(endpoint: EndpointRef, durationMs: Long = DEFAULT_DURATION_MS) {
        active[endpoint] = System.currentTimeMillis() + durationMs
    }

    /** Backward-compat overload for callers that only have a [BlockPos]. */
    fun highlight(pos: BlockPos, durationMs: Long = DEFAULT_DURATION_MS) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        highlight(EndpointRef.from(level, pos), durationMs)
    }

    fun onRender(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
        val now = System.currentTimeMillis()
        val iter = active.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.value <= now) iter.remove()
        }
        if (active.isEmpty()) return

        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val cameraPos = event.camera.position
        val pose = event.poseStack
        val bufferSource = mc.renderBuffers().bufferSource()
        val builder = bufferSource.getBuffer(HIGHLIGHT_TYPE)

        // Pulse alpha for the outline. It's thin edge beams now (not a fill),
        // so the base alpha is high — a thin line needs the opacity to read
        // through walls — with a gentle pulse on top.
        val pulse = (0.5 + 0.5 * sin(now * (2 * PI / PULSE_PERIOD_MS))).toFloat()
        val a = (BASE_ALPHA + (PEAK_ALPHA - BASE_ALPHA) * pulse).toInt().coerceIn(0, 255)
        val r = 0xFF
        val g = 0xF0
        val b = 0x80

        pose.pushPose()
        pose.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
        val matrix = pose.last().pose()

        for (endpoint in active.keys) {
            val center = endpoint.worldCenter(level) ?: continue
            drawBoxOutline(builder, matrix, center.x, center.y, center.z, r, g, b, a)
        }

        pose.popPose()
        bufferSource.endBatch(HIGHLIGHT_TYPE)
    }

    /**
     * Draws the 12 edges of a cube centred on (cx, cy, cz) as thin axis-aligned
     * beams — a Create-style selection outline (rather than a solid fill) that,
     * thanks to the type's NO_DEPTH_TEST, shows straight through walls so you
     * can find the linked block from anywhere. The cube is slightly outset to
     * sit just outside the block's own surfaces.
     *
     * For world endpoints [EndpointRef.worldCenter] returns
     * `Vec3.atCenterOf(pos)` = `(pos.x+0.5, pos.y+0.5, pos.z+0.5)`; for ship
     * endpoints the centre is the rotated/translated world position, so the
     * highlight follows the ship.
     */
    private fun drawBoxOutline(
        builder: VertexConsumer,
        matrix: Matrix4f,
        cx: Double, cy: Double, cz: Double,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        val h = 0.5 + OUTSET
        val t = EDGE
        val s = doubleArrayOf(-h, h) // the two corner offsets per axis
        // 4 edges along X (vary y,z corners) …
        for (yy in s) for (zz in s)
            solidBox(builder, matrix, cx - h, cy + yy - t, cz + zz - t, cx + h, cy + yy + t, cz + zz + t, r, g, b, a)
        // … 4 along Y (vary x,z) …
        for (xx in s) for (zz in s)
            solidBox(builder, matrix, cx + xx - t, cy - h, cz + zz - t, cx + xx + t, cy + h, cz + zz + t, r, g, b, a)
        // … 4 along Z (vary x,y).
        for (xx in s) for (yy in s)
            solidBox(builder, matrix, cx + xx - t, cy + yy - t, cz - h, cx + xx + t, cy + yy + t, cz + h, r, g, b, a)
    }

    /** Six QUAD faces of an axis-aligned box [x0,x1]×[y0,y1]×[z0,z1]. NO_CULL
     *  means winding doesn't matter. */
    private fun solidBox(
        builder: VertexConsumer,
        matrix: Matrix4f,
        x0: Double, y0: Double, z0: Double,
        x1: Double, y1: Double, z1: Double,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        // -Y / +Y
        emit(builder, matrix, x0, y0, z0, r, g, b, a); emit(builder, matrix, x0, y0, z1, r, g, b, a)
        emit(builder, matrix, x1, y0, z1, r, g, b, a); emit(builder, matrix, x1, y0, z0, r, g, b, a)
        emit(builder, matrix, x0, y1, z0, r, g, b, a); emit(builder, matrix, x1, y1, z0, r, g, b, a)
        emit(builder, matrix, x1, y1, z1, r, g, b, a); emit(builder, matrix, x0, y1, z1, r, g, b, a)
        // -Z / +Z
        emit(builder, matrix, x0, y0, z0, r, g, b, a); emit(builder, matrix, x1, y0, z0, r, g, b, a)
        emit(builder, matrix, x1, y1, z0, r, g, b, a); emit(builder, matrix, x0, y1, z0, r, g, b, a)
        emit(builder, matrix, x0, y0, z1, r, g, b, a); emit(builder, matrix, x0, y1, z1, r, g, b, a)
        emit(builder, matrix, x1, y1, z1, r, g, b, a); emit(builder, matrix, x1, y0, z1, r, g, b, a)
        // -X / +X
        emit(builder, matrix, x0, y0, z0, r, g, b, a); emit(builder, matrix, x0, y1, z0, r, g, b, a)
        emit(builder, matrix, x0, y1, z1, r, g, b, a); emit(builder, matrix, x0, y0, z1, r, g, b, a)
        emit(builder, matrix, x1, y0, z0, r, g, b, a); emit(builder, matrix, x1, y0, z1, r, g, b, a)
        emit(builder, matrix, x1, y1, z1, r, g, b, a); emit(builder, matrix, x1, y1, z0, r, g, b, a)
    }

    private fun emit(
        builder: VertexConsumer,
        matrix: Matrix4f,
        x: Double, y: Double, z: Double,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        builder.addVertex(matrix, x.toFloat(), y.toFloat(), z.toFloat())
            .setColor(r, g, b, a)
    }

    private const val DEFAULT_DURATION_MS = 3000L
    private const val OUTSET = 0.01
    private const val EDGE = 0.03         // half-thickness of an outline beam (~0.06 full ≈ Create line)
    private const val PULSE_PERIOD_MS = 700.0
    private const val BASE_ALPHA = 170  // thin lines need opacity to read through walls
    private const val PEAK_ALPHA = 240
}
