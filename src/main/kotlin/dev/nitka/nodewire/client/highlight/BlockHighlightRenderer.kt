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

        // Pulse alpha 0.2..1.0 with ~2 Hz.
        val pulse = (0.6 + 0.4 * sin(now * (2 * PI / PULSE_PERIOD_MS))).toFloat()
        val a = (255 * pulse).toInt().coerceIn(0, 255)
        val r = 0xFF
        val g = 0xE0
        val b = 0x66

        pose.pushPose()
        pose.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
        val matrix = pose.last().pose()

        for (pos in active.keys) {
            drawCubeFrame(builder, matrix, pos, r, g, b, a)
        }

        pose.popPose()
        bufferSource.endBatch(HIGHLIGHT_TYPE)
    }

    private fun drawCubeFrame(
        builder: VertexConsumer,
        matrix: Matrix4f,
        pos: BlockPos,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        val lo = -OUTSET
        val hi = 1.0 + OUTSET
        val x = pos.x.toDouble(); val y = pos.y.toDouble(); val z = pos.z.toDouble()
        // 12 edges of the cube. Each edge runs along one axis between two
        // corners. Encode as (from, to) pairs.
        val edges = arrayOf(
            // Bottom square (y = lo)
            Triple(doubleArrayOf(lo, lo, lo), doubleArrayOf(hi, lo, lo), 0),
            Triple(doubleArrayOf(hi, lo, lo), doubleArrayOf(hi, lo, hi), 2),
            Triple(doubleArrayOf(hi, lo, hi), doubleArrayOf(lo, lo, hi), 0),
            Triple(doubleArrayOf(lo, lo, hi), doubleArrayOf(lo, lo, lo), 2),
            // Top square (y = hi)
            Triple(doubleArrayOf(lo, hi, lo), doubleArrayOf(hi, hi, lo), 0),
            Triple(doubleArrayOf(hi, hi, lo), doubleArrayOf(hi, hi, hi), 2),
            Triple(doubleArrayOf(hi, hi, hi), doubleArrayOf(lo, hi, hi), 0),
            Triple(doubleArrayOf(lo, hi, hi), doubleArrayOf(lo, hi, lo), 2),
            // Vertical edges
            Triple(doubleArrayOf(lo, lo, lo), doubleArrayOf(lo, hi, lo), 1),
            Triple(doubleArrayOf(hi, lo, lo), doubleArrayOf(hi, hi, lo), 1),
            Triple(doubleArrayOf(hi, lo, hi), doubleArrayOf(hi, hi, hi), 1),
            Triple(doubleArrayOf(lo, lo, hi), doubleArrayOf(lo, hi, hi), 1),
        )
        for ((from, to, axis) in edges) {
            emitEdgeQuad(
                builder, matrix,
                x + from[0], y + from[1], z + from[2],
                x + to[0],   y + to[1],   z + to[2],
                axis, r, g, b, a,
            )
        }
    }

    /**
     * Draws a flat thin quad between two world-space points. `axis` indicates
     * which world axis the edge runs along (0=X, 1=Y, 2=Z) so we can pick a
     * sensible perpendicular for the quad's width.
     */
    private fun emitEdgeQuad(
        builder: VertexConsumer,
        matrix: Matrix4f,
        x1: Double, y1: Double, z1: Double,
        x2: Double, y2: Double, z2: Double,
        axis: Int,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        val w = EDGE_THICKNESS * 0.5
        // Perpendicular vector for the quad width.
        val (dx, dy, dz) = when (axis) {
            0 -> Triple(0.0, w, 0.0) // X edge → spread along Y
            1 -> Triple(w, 0.0, 0.0) // Y edge → spread along X
            else -> Triple(w, 0.0, 0.0) // Z edge → spread along X
        }
        emit(builder, matrix, x1 - dx, y1 - dy, z1 - dz, r, g, b, a)
        emit(builder, matrix, x2 - dx, y2 - dy, z2 - dz, r, g, b, a)
        emit(builder, matrix, x2 + dx, y2 + dy, z2 + dz, r, g, b, a)
        emit(builder, matrix, x1 + dx, y1 + dy, z1 + dz, r, g, b, a)
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
    private const val EDGE_THICKNESS = 0.04
    private const val OUTSET = 0.02
    private const val PULSE_PERIOD_MS = 500.0
}
