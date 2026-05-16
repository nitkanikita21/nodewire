package dev.nitka.nodewire.client.highlight

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import dev.nitka.nodewire.endpoint.EndpointRef
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraftforge.client.event.RenderLevelStageEvent
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

        // Pulse alpha for the translucent face fill. The faces are solid
        // quads, so we want a low base alpha (so the block + surroundings
        // remain readable through the highlight) with a gentle pulse.
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
            drawCubeSolid(builder, matrix, center.x, center.y, center.z, r, g, b, a)
        }

        pose.popPose()
        bufferSource.endBatch(HIGHLIGHT_TYPE)
    }

    /**
     * Draws the six faces of a cube centred on (cx, cy, cz) as translucent
     * quads, very slightly outset to avoid z-fight with the block's own
     * surfaces. NO_CULL means faces render from both sides, so we don't worry
     * about winding.
     *
     * For world endpoints [EndpointRef.worldCenter] returns
     * `Vec3.atCenterOf(pos)` = `(pos.x+0.5, pos.y+0.5, pos.z+0.5)`, so the
     * rendered cube is identical to the old `pos.x..pos.x+1` form.
     * For ship endpoints the centre is the rotated/translated world position,
     * so the highlight follows the ship.
     */
    private fun drawCubeSolid(
        builder: VertexConsumer,
        matrix: Matrix4f,
        cx: Double, cy: Double, cz: Double,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        val lo = -(0.5 + OUTSET)
        val hi = 0.5 + OUTSET

        // -Y (bottom)
        emit(builder, matrix, cx + lo, cy + lo, cz + lo, r, g, b, a)
        emit(builder, matrix, cx + lo, cy + lo, cz + hi, r, g, b, a)
        emit(builder, matrix, cx + hi, cy + lo, cz + hi, r, g, b, a)
        emit(builder, matrix, cx + hi, cy + lo, cz + lo, r, g, b, a)
        // +Y (top)
        emit(builder, matrix, cx + lo, cy + hi, cz + lo, r, g, b, a)
        emit(builder, matrix, cx + hi, cy + hi, cz + lo, r, g, b, a)
        emit(builder, matrix, cx + hi, cy + hi, cz + hi, r, g, b, a)
        emit(builder, matrix, cx + lo, cy + hi, cz + hi, r, g, b, a)
        // -Z (north)
        emit(builder, matrix, cx + lo, cy + lo, cz + lo, r, g, b, a)
        emit(builder, matrix, cx + hi, cy + lo, cz + lo, r, g, b, a)
        emit(builder, matrix, cx + hi, cy + hi, cz + lo, r, g, b, a)
        emit(builder, matrix, cx + lo, cy + hi, cz + lo, r, g, b, a)
        // +Z (south)
        emit(builder, matrix, cx + lo, cy + lo, cz + hi, r, g, b, a)
        emit(builder, matrix, cx + lo, cy + hi, cz + hi, r, g, b, a)
        emit(builder, matrix, cx + hi, cy + hi, cz + hi, r, g, b, a)
        emit(builder, matrix, cx + hi, cy + lo, cz + hi, r, g, b, a)
        // -X (west)
        emit(builder, matrix, cx + lo, cy + lo, cz + lo, r, g, b, a)
        emit(builder, matrix, cx + lo, cy + hi, cz + lo, r, g, b, a)
        emit(builder, matrix, cx + lo, cy + hi, cz + hi, r, g, b, a)
        emit(builder, matrix, cx + lo, cy + lo, cz + hi, r, g, b, a)
        // +X (east)
        emit(builder, matrix, cx + hi, cy + lo, cz + lo, r, g, b, a)
        emit(builder, matrix, cx + hi, cy + lo, cz + hi, r, g, b, a)
        emit(builder, matrix, cx + hi, cy + hi, cz + hi, r, g, b, a)
        emit(builder, matrix, cx + hi, cy + hi, cz + lo, r, g, b, a)
    }

    private fun emit(
        builder: VertexConsumer,
        matrix: Matrix4f,
        x: Double, y: Double, z: Double,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        builder.vertex(matrix, x.toFloat(), y.toFloat(), z.toFloat())
            .color(r, g, b, a)
            .endVertex()
    }

    private const val DEFAULT_DURATION_MS = 3000L
    private const val OUTSET = 0.005
    private const val PULSE_PERIOD_MS = 700.0
    private const val BASE_ALPHA = 60   // ~24% — block stays readable
    private const val PEAK_ALPHA = 140  // ~55% — clearly "lit"
}
