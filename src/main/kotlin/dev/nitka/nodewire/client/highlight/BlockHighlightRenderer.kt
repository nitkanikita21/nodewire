package dev.nitka.nodewire.client.highlight

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
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

    private val active = ConcurrentHashMap<BlockPos, Long>()

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

    fun highlight(pos: BlockPos, durationMs: Long = DEFAULT_DURATION_MS) {
        active[pos] = System.currentTimeMillis() + durationMs
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

        for (pos in active.keys) {
            drawCubeSolid(builder, matrix, pos, r, g, b, a)
        }

        pose.popPose()
        bufferSource.endBatch(HIGHLIGHT_TYPE)
    }

    /**
     * Draws the six faces of a cube around [pos] as translucent quads, very
     * slightly outset to avoid z-fight with the block's own surfaces. NO_CULL
     * means faces render from both sides, so we don't worry about winding.
     */
    private fun drawCubeSolid(
        builder: VertexConsumer,
        matrix: Matrix4f,
        pos: BlockPos,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        val lo = -OUTSET
        val hi = 1.0 + OUTSET
        val x = pos.x.toDouble(); val y = pos.y.toDouble(); val z = pos.z.toDouble()

        // -Y (bottom)
        emit(builder, matrix, x + lo, y + lo, z + lo, r, g, b, a)
        emit(builder, matrix, x + lo, y + lo, z + hi, r, g, b, a)
        emit(builder, matrix, x + hi, y + lo, z + hi, r, g, b, a)
        emit(builder, matrix, x + hi, y + lo, z + lo, r, g, b, a)
        // +Y (top)
        emit(builder, matrix, x + lo, y + hi, z + lo, r, g, b, a)
        emit(builder, matrix, x + hi, y + hi, z + lo, r, g, b, a)
        emit(builder, matrix, x + hi, y + hi, z + hi, r, g, b, a)
        emit(builder, matrix, x + lo, y + hi, z + hi, r, g, b, a)
        // -Z (north)
        emit(builder, matrix, x + lo, y + lo, z + lo, r, g, b, a)
        emit(builder, matrix, x + hi, y + lo, z + lo, r, g, b, a)
        emit(builder, matrix, x + hi, y + hi, z + lo, r, g, b, a)
        emit(builder, matrix, x + lo, y + hi, z + lo, r, g, b, a)
        // +Z (south)
        emit(builder, matrix, x + lo, y + lo, z + hi, r, g, b, a)
        emit(builder, matrix, x + lo, y + hi, z + hi, r, g, b, a)
        emit(builder, matrix, x + hi, y + hi, z + hi, r, g, b, a)
        emit(builder, matrix, x + hi, y + lo, z + hi, r, g, b, a)
        // -X (west)
        emit(builder, matrix, x + lo, y + lo, z + lo, r, g, b, a)
        emit(builder, matrix, x + lo, y + hi, z + lo, r, g, b, a)
        emit(builder, matrix, x + lo, y + hi, z + hi, r, g, b, a)
        emit(builder, matrix, x + lo, y + lo, z + hi, r, g, b, a)
        // +X (east)
        emit(builder, matrix, x + hi, y + lo, z + lo, r, g, b, a)
        emit(builder, matrix, x + hi, y + lo, z + hi, r, g, b, a)
        emit(builder, matrix, x + hi, y + hi, z + hi, r, g, b, a)
        emit(builder, matrix, x + hi, y + hi, z + lo, r, g, b, a)
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
