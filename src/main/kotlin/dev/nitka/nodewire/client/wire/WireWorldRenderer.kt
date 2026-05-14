package dev.nitka.nodewire.client.wire

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import dev.nitka.nodewire.Registry
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.graph.PinType
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RenderLevelStageEvent
import kotlin.math.sqrt

/**
 * Straight, camera-facing colored cables between bound logic blocks.
 *
 * Visibility: only renders while the player is holding the Channel Link
 * Tool in either hand — keeps the world clean during normal play and
 * draws the wires only when the user is actively wiring.
 *
 * Geometry: each binding is a single billboard quad spanning source-
 * center to target-center. Quad width is computed by cross-producting
 * the segment direction with the view direction so it always faces
 * the camera — gives a constant pixel thickness regardless of viewing
 * angle, unlike GL_LINES whose width is silently clamped to 1 in core
 * profile.
 */
object WireWorldRenderer {

    /**
     * Subclass purely to access [RenderStateShard]'s protected static
     * sub-shards (POSITION_COLOR_SHADER, NO_LIGHTMAP, …). They aren't
     * exposed publicly; the subclass-based access is the standard
     * trick Forge mods use without an Access Transformer.
     */
    private object Shards : RenderStateShard("", Runnable {}, Runnable {}) {
        val POSITION_COLOR = POSITION_COLOR_SHADER
        val NO_LIGHTMAP = RenderStateShard.NO_LIGHTMAP
        val TRANSLUCENT = TRANSLUCENT_TRANSPARENCY
        val NO_CULL_S = NO_CULL
        val LEQUAL_DEPTH = LEQUAL_DEPTH_TEST
        val COLOR_DEPTH = COLOR_DEPTH_WRITE
    }

    /** Custom render type: POSITION+COLOR quads, translucency, world depth-test. */
    private val WIRE_TYPE: RenderType = RenderType.create(
        "nodewire_wire",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        256,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(Shards.POSITION_COLOR)
            .setLightmapState(Shards.NO_LIGHTMAP)
            .setTransparencyState(Shards.TRANSLUCENT)
            .setCullState(Shards.NO_CULL_S)
            .setDepthTestState(Shards.LEQUAL_DEPTH)
            .setWriteMaskState(Shards.COLOR_DEPTH)
            .createCompositeState(false),
    )

    fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        // Only show wires when the link tool is actually in hand.
        val toolItem = Registry.CHANNEL_LINK_TOOL.get()
        val hasTool = player.mainHandItem.`is`(toolItem) || player.offhandItem.`is`(toolItem)
        if (!hasTool) return

        val level = mc.level ?: return
        val tracked = ClientLogicBlockTracker.all()
        if (tracked.isEmpty()) return

        val cameraPos = event.camera.position
        val pose = event.poseStack
        val bufferSource = mc.renderBuffers().bufferSource()
        val builder = bufferSource.getBuffer(WIRE_TYPE)

        pose.pushPose()
        pose.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
        val matrix = pose.last().pose()

        for (source in tracked) {
            val bindings = source.bindingsSnapshot()
            if (bindings.isEmpty()) continue
            val srcCenter = source.blockPos.center
            for (binding in bindings) {
                val color = colorForBinding(source, binding.sourceChannelName)
                drawStraightWire(builder, matrix, srcCenter, binding.targetPos.center, cameraPos, color)
            }
        }

        pose.popPose()
        bufferSource.endBatch(WIRE_TYPE)
    }

    /**
     * One billboard quad from [src] to [dst]. The quad's width-direction
     * is `segDir × cameraDir` normalized — keeps the quad facing the
     * camera so it stays thick at any viewing angle.
     */
    private fun drawStraightWire(
        builder: VertexConsumer,
        matrix: org.joml.Matrix4f,
        src: Vec3,
        dst: Vec3,
        cameraPos: Vec3,
        color: Int,
    ) {
        val dx = dst.x - src.x
        val dy = dst.y - src.y
        val dz = dst.z - src.z
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-4) return

        // Direction toward camera, taken from the segment midpoint so the
        // billboard normal isn't degenerate when the camera is right above
        // an endpoint.
        val midX = (src.x + dst.x) * 0.5
        val midY = (src.y + dst.y) * 0.5
        val midZ = (src.z + dst.z) * 0.5
        var vx = cameraPos.x - midX
        var vy = cameraPos.y - midY
        var vz = cameraPos.z - midZ
        val vlen = sqrt(vx * vx + vy * vy + vz * vz)
        if (vlen < 1e-4) return
        vx /= vlen; vy /= vlen; vz /= vlen

        // perp = (seg × view) — perpendicular to both, lies in the screen
        // plane along the screen-horizontal of the segment.
        val sdx = dx / len; val sdy = dy / len; val sdz = dz / len
        var px = sdy * vz - sdz * vy
        var py = sdz * vx - sdx * vz
        var pz = sdx * vy - sdy * vx
        val plen = sqrt(px * px + py * py + pz * pz)
        if (plen < 1e-4) return
        val half = WIRE_THICKNESS * 0.5
        px = px / plen * half; py = py / plen * half; pz = pz / plen * half

        val a = (color ushr 24) and 0xFF
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF

        // Quad order matters for back-face culling / shader winding.
        // CCW from the camera: src-, src+, dst+, dst- ⇒ NO_CULL keeps both
        // sides drawable so the order doesn't matter visually here.
        emit(builder, matrix, src.x - px, src.y - py, src.z - pz, r, g, b, a)
        emit(builder, matrix, src.x + px, src.y + py, src.z + pz, r, g, b, a)
        emit(builder, matrix, dst.x + px, dst.y + py, dst.z + pz, r, g, b, a)
        emit(builder, matrix, dst.x - px, dst.y - py, dst.z - pz, r, g, b, a)
    }

    private fun emit(
        builder: VertexConsumer,
        matrix: org.joml.Matrix4f,
        x: Double, y: Double, z: Double,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        builder.vertex(matrix, x.toFloat(), y.toFloat(), z.toFloat())
            .color(r, g, b, a)
            .endVertex()
    }

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

    /** Visible cable thickness in world units. Tweak for fatter / thinner ropes. */
    private const val WIRE_THICKNESS = 0.08
}
