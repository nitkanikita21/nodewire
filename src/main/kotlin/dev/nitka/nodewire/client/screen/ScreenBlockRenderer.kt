package dev.nitka.nodewire.client.screen

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import dev.nitka.nodewire.block.ScreenBlock
import dev.nitka.nodewire.block.ScreenBlockEntity
import dev.nitka.nodewire.client.video.GlVideoSurface
import dev.nitka.nodewire.client.video.VideoManager
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import org.joml.Matrix4f

/**
 * The repo's **first** [BlockEntityRenderer]. Blits a Video handle's client-
 * local FBO colour texture onto the [ScreenBlock.FACING] face of a Screen block.
 *
 * Reuses the [RenderType.create] + [RenderStateShard] recipe from
 * `WireWorldRenderer`, but textures the quad from a **raw GL texture id**
 * (the FBO colour attachment) rather than a `ResourceLocation` — bound via
 * `RenderSystem.setShaderTexture(0, id)` in the type's setup shard. The id is
 * read fresh each frame so a re-allocated surface is picked up automatically.
 *
 * Honors [VideoManager.isCapturing] (inert `false` until a Camera exists) so the
 * Screen refuses to draw while a Camera capture is in progress — correct the day
 * the Camera lands, with zero Camera code here.
 */
class ScreenBlockRenderer(
    @Suppress("UNUSED_PARAMETER") ctx: BlockEntityRendererProvider.Context,
) : BlockEntityRenderer<ScreenBlockEntity> {

    override fun render(
        be: ScreenBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        light: Int,
        overlay: Int,
    ) {
        // Recursion guard (Camera-readiness): never draw a video face while a
        // capture pass is in flight. Inert until Component 4 exists.
        if (VideoManager.isCapturing()) return

        val handle = be.videoHandle() ?: return
        val surface = VideoManager.getOrCreate(handle) as? GlVideoSurface ?: return
        val texId = surface.colorTextureId()

        val facing = be.blockState.getValue(ScreenBlock.FACING)
        val type = renderTypeFor(texId)
        val consumer = buffers.getBuffer(type)

        poseStack.pushPose()
        val matrix = poseStack.last().pose()
        // Lit texture WITH a self-emission floor: a monitor glows, so clamp the
        // block-light component up to MIN_BLOCK_LIGHT before blitting. The screen
        // still brightens with environment light (sky component kept) but never
        // dims below readable in shadow — full lightmap modulation made the whole
        // content (camera feed included) almost invisible in dim areas.
        val lit = LightTexture.pack(
            maxOf(LightTexture.block(light), MIN_BLOCK_LIGHT),
            LightTexture.sky(light),
        )
        emitFace(consumer, matrix, facing, lit)
        poseStack.popPose()
    }

    /**
     * Emits a single unit quad on [facing], inset slightly off the block face so
     * it does not z-fight with the block texture. UVs map the full FBO (0..1);
     * V is flipped because FBO colour attachments are bottom-up.
     */
    private fun emitFace(
        consumer: VertexConsumer,
        matrix: Matrix4f,
        facing: Direction,
        light: Int,
    ) {
        val o = 0.001f // outset off the face plane
        // Corners of the face in block-local [0,1]^3 space. Order CCW so the
        // textured front faces outward along the facing normal.
        val (a, b, c, d) = faceCorners(facing, o)
        // (u,v) per corner: bottom-left, bottom-right, top-right, top-left.
        // The GuiGraphics ortho (top-left origin, y-down) draws an UPRIGHT image
        // into the FBO; sampled with v=0 at the face bottom it shows upright, so
        // the face's bottom edge takes v=0 and the top edge v=1.
        vertex(consumer, matrix, a, 0f, 0f, facing, light)
        vertex(consumer, matrix, b, 1f, 0f, facing, light)
        vertex(consumer, matrix, c, 1f, 1f, facing, light)
        vertex(consumer, matrix, d, 0f, 1f, facing, light)
    }

    private fun vertex(
        consumer: VertexConsumer,
        matrix: Matrix4f,
        p: FloatArray,
        u: Float,
        v: Float,
        facing: Direction,
        light: Int,
    ) {
        val n = facing.normal
        consumer.addVertex(matrix, p[0], p[1], p[2])
            .setColor(255, 255, 255, 255)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(n.x.toFloat(), n.y.toFloat(), n.z.toFloat())
    }

    /**
     * Four corners (each `[x,y,z]`) of the quad on [facing], outset by [o] along
     * the facing normal, returned bottom-left, bottom-right, top-right, top-left
     * as seen looking at the face from outside.
     */
    private fun faceCorners(facing: Direction, o: Float): List<FloatArray> = when (facing) {
        Direction.NORTH -> listOf(
            floatArrayOf(1f, 0f, -o), floatArrayOf(0f, 0f, -o),
            floatArrayOf(0f, 1f, -o), floatArrayOf(1f, 1f, -o),
        )
        Direction.SOUTH -> listOf(
            floatArrayOf(0f, 0f, 1f + o), floatArrayOf(1f, 0f, 1f + o),
            floatArrayOf(1f, 1f, 1f + o), floatArrayOf(0f, 1f, 1f + o),
        )
        Direction.WEST -> listOf(
            floatArrayOf(-o, 0f, 0f), floatArrayOf(-o, 0f, 1f),
            floatArrayOf(-o, 1f, 1f), floatArrayOf(-o, 1f, 0f),
        )
        Direction.EAST -> listOf(
            floatArrayOf(1f + o, 0f, 1f), floatArrayOf(1f + o, 0f, 0f),
            floatArrayOf(1f + o, 1f, 0f), floatArrayOf(1f + o, 1f, 1f),
        )
        // FACING is horizontal-only on ScreenBlock; UP/DOWN never occur but
        // keep the when exhaustive.
        Direction.UP -> listOf(
            floatArrayOf(0f, 1f + o, 1f), floatArrayOf(1f, 1f + o, 1f),
            floatArrayOf(1f, 1f + o, 0f), floatArrayOf(0f, 1f + o, 0f),
        )
        Direction.DOWN -> listOf(
            floatArrayOf(0f, -o, 0f), floatArrayOf(1f, -o, 0f),
            floatArrayOf(1f, -o, 1f), floatArrayOf(0f, -o, 1f),
        )
    }

    companion object {
        /** Self-emission floor (block-light units, 0..15) so a screen stays
         *  readable in the dark while still brightening with environment light. */
        private const val MIN_BLOCK_LIGHT = 14
        /**
         * A textured, lit render type bound to a **raw GL texture id**. The setup
         * shard binds the FBO colour attachment to sampler 0; the clear shard is a
         * no-op (the texture stays bound until the next type swaps it).
         */
        private fun renderTypeFor(texId: Int): RenderType = RenderType.create(
            "nodewire_screen",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            256,
            false,
            false,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_SOLID_SHADER)
                .setTextureState(object : RenderStateShard.EmptyTextureStateShard(
                    Runnable { RenderSystem.setShaderTexture(0, texId) },
                    Runnable {},
                ) {})
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                .createCompositeState(true),
        )
    }
}
