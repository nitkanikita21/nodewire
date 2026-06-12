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
class CameraBlock(props: Properties) : Block(props), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

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
    }
}
