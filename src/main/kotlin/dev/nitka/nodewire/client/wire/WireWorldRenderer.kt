package dev.nitka.nodewire.client.wire

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.graph.PinType
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RenderLevelStageEvent
import kotlin.math.abs

/**
 * Draws Scrap-Mechanic-style cables between bound logic blocks. Wires
 * follow a parabolic catenary sag in world Y so they read as physical
 * rope rather than a flat segment; thickness is faked by drawing a few
 * parallel offset lines (GPUs ignore line-width > 1 in core profile).
 *
 * Channel type drives the colour — the same hues the editor uses for pin
 * handles, so a wire and the pins it connects share a visual language.
 *
 * Hook: [RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS]. Late
 * enough that the wires read as overlays but still depth-tested against
 * world geometry; a wire passing behind a wall hides correctly.
 */
object WireWorldRenderer {

    fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val tracked = ClientLogicBlockTracker.all()
        if (tracked.isEmpty()) return

        val cameraPos = event.camera.position
        val pose = event.poseStack
        val bufferSource = mc.renderBuffers().bufferSource()
        val builder = bufferSource.getBuffer(RenderType.lines())

        pose.pushPose()
        // Move world origin to the camera so absolute world positions
        // composed below render correctly.
        pose.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)

        for (source in tracked) {
            if (source.bindingsSnapshot().isEmpty()) continue
            val srcPos = source.blockPos.center
            for (binding in source.bindingsSnapshot()) {
                val dstPos = binding.targetPos.center
                val color = colorForBinding(source, binding.sourceChannelName)
                drawCatenary(pose, builder, srcPos, dstPos, color)
            }
        }

        pose.popPose()
        // Flush this frame's wire vertices.
        bufferSource.endBatch(RenderType.lines())
    }

    /**
     * Sample the catenary in [SEGMENTS] segments and emit them as line
     * pairs. The sag is parabolic (zero at endpoints, max at midpoint) and
     * scales with the chord length so short wires stay tight while long
     * ones droop visibly. Y-only sag — feels right for gravity-bound rope.
     */
    private fun drawCatenary(
        pose: PoseStack,
        builder: VertexConsumer,
        src: Vec3,
        dst: Vec3,
        color: Int,
    ) {
        val dx = dst.x - src.x
        val dy = dst.y - src.y
        val dz = dst.z - src.z
        val chord = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).toFloat()
        val sag = (chord * SAG_FACTOR).coerceAtLeast(MIN_SAG)

        // Pre-sample once; outline / core passes share the same points.
        val points = Array(SEGMENTS + 1) { i ->
            val t = i.toFloat() / SEGMENTS
            val x = src.x + dx * t
            val y = src.y + dy * t - sag * 4f * t * (1f - t)
            val z = src.z + dz * t
            Vec3(x, y, z)
        }

        // Two parallel passes offset slightly in Y for a thicker "rope"
        // silhouette without depending on RenderSystem.lineWidth (which
        // is unreliable on modern GL).
        val a = (color ushr 24) and 0xFF
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        val matrix = pose.last().pose()
        for (offset in OFFSETS) {
            for (i in 0 until SEGMENTS) {
                val p1 = points[i]
                val p2 = points[i + 1]
                builder.vertex(matrix, p1.x.toFloat(), (p1.y + offset).toFloat(), p1.z.toFloat())
                    .color(r, g, b, a)
                    .normal(0f, 1f, 0f)
                    .endVertex()
                builder.vertex(matrix, p2.x.toFloat(), (p2.y + offset).toFloat(), p2.z.toFloat())
                    .color(r, g, b, a)
                    .normal(0f, 1f, 0f)
                    .endVertex()
            }
        }
        RenderSystem.lineWidth(LINE_WIDTH)
    }

    /**
     * Look up the source's [channel_output] node by name, take its
     * configured [PinType], map to a colour. Falls back to white if the
     * channel can't be found (binding survives a node delete; we still
     * want to draw the wire so the user can see the dangling link).
     */
    private fun colorForBinding(source: LogicBlockEntity, channelName: String): Int {
        val node = source.graph.nodes.values.firstOrNull { node ->
            node.typeKey.path == "channel_output"
                && node.config.getString("name") == channelName
        }
        val type = node?.config?.getString("type")
            ?.let { PinType.fromName(it) }
            ?: return 0xFFFFFFFF.toInt()
        return colorForType(type)
    }

    private fun colorForType(type: PinType): Int = when (type) {
        PinType.BOOL -> 0xFF_E8_5C_5C.toInt()
        PinType.INT -> 0xFF_5C_C8_E8.toInt()
        PinType.FLOAT -> 0xFF_E8_C8_5C.toInt()
        PinType.REDSTONE -> 0xFF_B8_30_30.toInt()
        PinType.STRING -> 0xFF_F0_8A_4A.toInt()
        PinType.VEC2 -> 0xFF_7C_E8_5C.toInt()
        PinType.VEC3 -> 0xFF_AC_E8_5C.toInt()
        PinType.QUAT -> 0xFF_C8_7C_E8.toInt()
    }

    private const val SEGMENTS = 24
    private const val SAG_FACTOR = 0.08f
    private const val MIN_SAG = 0.3f
    private const val LINE_WIDTH = 2.5f
    /** Three near-parallel passes fake a rope thickness despite GL_LINES' 1px reality. */
    private val OFFSETS = floatArrayOf(-0.02f, 0f, 0.02f)
}
