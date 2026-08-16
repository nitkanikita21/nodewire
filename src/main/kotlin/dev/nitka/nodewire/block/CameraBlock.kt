package dev.nitka.nodewire.block

import net.minecraft.core.BlockPos
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.DirectionProperty

/**
 * A block that *produces* a Video handle: it mints+`acquire()`s a stable
 * client-local handle and a client capture loop renders the world from this
 * block's POV into that handle's surface every capture frame. The handle is
 * published into a bound channel (via the existing handle->channel pipeline)
 * that a [ScreenBlock] on the other end blits.
 *
 * Mirror of [ScreenBlock] (the consumer end). Faces with [FACING]; the camera
 * looks out of that face. No server ticker and no BER — the producer owns no
 * graph and needs no custom face render.
 */
/**
 * [rotatable] = true → the gimbal Camera: exposes yaw/pitch/roll input pins and
 * its yoke/head are drawn by [dev.nitka.nodewire.client.camera.CameraBlockRenderer].
 * false → the Fixed Camera: aims along its facing only (no rotation pins, one
 * static model). Both share [CameraBlockEntity] and its BE type.
 */
class CameraBlock(props: Properties, val rotatable: Boolean = true) : Block(props), EntityBlock {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(FACING, net.minecraft.core.Direction.NORTH)
                .setValue(HIDDEN, false),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        // HIDDEN is registered on BOTH camera variants ([rotatable] isn't
        // assigned yet while the super constructor runs this), but only the
        // Fixed Camera's wrench toggle ever flips it — the gimbal's moving
        // parts are BER-drawn and couldn't hide via the blockstate anyway.
        builder.add(FACING, HIDDEN)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    /**
     * Fixed Camera only: a Create-style wrench (the `c:tools/wrench` tag or
     * Create's own item) toggles [HIDDEN] — the body model swaps to an empty
     * one, leaving an invisible (still selectable) surveillance camera.
     */
    override fun useItemOn(
        stack: net.minecraft.world.item.ItemStack,
        state: BlockState,
        level: net.minecraft.world.level.Level,
        pos: BlockPos,
        player: net.minecraft.world.entity.player.Player,
        hand: net.minecraft.world.InteractionHand,
        hit: net.minecraft.world.phys.BlockHitResult,
    ): net.minecraft.world.ItemInteractionResult {
        if (rotatable || !isWrench(stack)) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        }
        if (!level.isClientSide) {
            val hidden = !state.getValue(HIDDEN)
            level.setBlock(pos, state.setValue(HIDDEN, hidden), Block.UPDATE_ALL)
            level.playSound(
                null, pos,
                net.minecraft.sounds.SoundEvents.ITEM_FRAME_REMOVE_ITEM,
                net.minecraft.sounds.SoundSource.BLOCKS,
                0.6f, if (hidden) 1.3f else 0.9f,
            )
        }
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide)
    }

    /** Hidden Fixed Camera is fully ghost: entities pass through. The OUTLINE
     *  shape stays a full cube so the crosshair can still find it (wrench it
     *  back, aim the Link Tool). */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getCollisionShape(
        state: BlockState,
        level: net.minecraft.world.level.BlockGetter,
        pos: BlockPos,
        context: net.minecraft.world.phys.shapes.CollisionContext,
    ): net.minecraft.world.phys.shapes.VoxelShape =
        if (state.getValue(HIDDEN)) {
            net.minecraft.world.phys.shapes.Shapes.empty()
        } else {
            super.getCollisionShape(state, level, pos, context)
        }

    private fun isWrench(stack: net.minecraft.world.item.ItemStack): Boolean {
        if (stack.isEmpty) return false
        if (stack.`is`(WRENCH_TAG)) return true
        // Registry-id fallback — no Create classes touched, works without it.
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item).toString() == "create:wrench"
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        CameraBlockEntity(pos, state)

    // Capture is driven client-side off the render thread (see
    // VideoCameraCapture). The server ticker below exists ONLY for the
    // unified pin links driving camera params (fov/enable/yaw/pitch) —
    // a no-op while the camera has no incoming links.
    @Suppress("UNCHECKED_CAST")
    override fun <T : BlockEntity> getTicker(
        level: net.minecraft.world.level.Level,
        state: BlockState,
        type: net.minecraft.world.level.block.entity.BlockEntityType<T>,
    ): net.minecraft.world.level.block.entity.BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != dev.nitka.nodewire.Registry.CAMERA_BLOCK_BE.get()) return null
        val ticker = net.minecraft.world.level.block.entity.BlockEntityTicker<CameraBlockEntity> { lvl, _, _, be ->
            dev.nitka.nodewire.link.PinLinkEngine.tick(lvl, be)
        }
        return ticker as net.minecraft.world.level.block.entity.BlockEntityTicker<T>
    }

    companion object {
        val FACING: DirectionProperty = HorizontalDirectionalBlock.FACING

        /** Fixed Camera: wrench-toggled invisibility (blockstate model swap). */
        val HIDDEN: net.minecraft.world.level.block.state.properties.BooleanProperty =
            net.minecraft.world.level.block.state.properties.BooleanProperty.create("hidden")

        private val WRENCH_TAG = net.minecraft.tags.TagKey.create(
            net.minecraft.core.registries.Registries.ITEM,
            net.minecraft.resources.ResourceLocation.parse("c:tools/wrench"),
        )
    }
}
