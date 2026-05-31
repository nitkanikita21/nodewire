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
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
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
        // Flat emissive blit: the render type uses the position_tex_color shader,
        // which applies NEITHER diffuse normal lighting NOR a lightmap, so the FBO
        // content shows EXACTLY as drawn at full brightness — like a real monitor.
        // (The old entity-solid shader multiplied by face-diffuse, dimming the whole
        // screen to a muddy tint regardless of the FULL_BRIGHT lightmap.)
        emitFace(consumer, matrix, facing)
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
    ) {
        val o = 0.001f // outset off the face plane
        // Corners of the face in block-local [0,1]^3 space. Order CCW so the
        // textured front faces outward along the facing normal.
        val (a, b, c, d) = faceCorners(facing, o)
        // (u,v) per corner: bottom-left, bottom-right, top-right, top-left.
        // The GuiGraphics ortho (top-left origin, y-down) draws an UPRIGHT image
        // into the FBO; sampled with v=0 at the face bottom it shows upright, so
        // the face's bottom edge takes v=0 and the top edge v=1.
        vertex(consumer, matrix, a, 0f, 0f)
        vertex(consumer, matrix, b, 1f, 0f)
        vertex(consumer, matrix, c, 1f, 1f)
        vertex(consumer, matrix, d, 0f, 1f)
    }

    /** POSITION_TEX vertex: position + UV only (the position_tex shader samples the
     *  texture × the white shader-colour the setup shard forces — no lighting). */
    private fun vertex(
        consumer: VertexConsumer,
        matrix: Matrix4f,
        p: FloatArray,
        u: Float,
        v: Float,
    ) {
        consumer.addVertex(matrix, p[0], p[1], p[2]).setUv(u, v)
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
        /**
         * A **flat, UNLIT** textured render type bound to a raw GL texture id. Uses
         * the `position_tex_color` shader — sample(tex) × vertexColor — which applies
         * neither diffuse normal lighting nor a lightmap, so the screen shows the FBO
         * content exactly as drawn (a self-illuminated monitor). The setup shard binds
         * the FBO colour attachment to sampler 0; the id is read fresh each frame.
         */
        private fun renderTypeFor(texId: Int): RenderType = RenderType.create(
            "nodewire_screen",
            DefaultVertexFormat.POSITION_TEX,
            VertexFormat.Mode.QUADS,
            256,
            false,
            false,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.POSITION_TEX_SHADER)
                .setTextureState(object : RenderStateShard.EmptyTextureStateShard(
                    Runnable {
                        RenderSystem.setShaderTexture(0, texId)
                        // position_tex multiplies by the shader colour-modulator —
                        // force it white so the blit shows the FBO content verbatim.
                        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
                    },
                    Runnable {},
                ) {})
                .createCompositeState(false),
        )
    }
}
