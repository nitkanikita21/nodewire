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
 * A block that displays a Video handle's client-local texture on its front
 * face. The handle arrives through the existing channel pipeline (a
 * `channel_output(type=VIDEO)` on a LogicBlock bound to this block via the
 * Channel Link Tool); the BE acquires it from `VideoManager` and the
 * `ScreenBlockRenderer` (the repo's first BER) blits it on the [FACING] face.
 *
 * Single-block only (multiblock formation is a later slice). No server ticker —
 * the BE owns no graph and only routes the delivered handle to the client
 * renderer.
 */
class ScreenBlock(props: Properties) : Block(props), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        ScreenBlockEntity(pos, state)

    // No getTicker override: the Screen needs no server ticker. The VIDEO handle
    // is delivered into the BE via ChannelInputSink by the bound LogicBlock's
    // own serverTick; the client BER reads it directly.

    companion object {
        val FACING: DirectionProperty = HorizontalDirectionalBlock.FACING
    }
}
