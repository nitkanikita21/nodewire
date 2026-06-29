package dev.nitka.nodewire.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A thin, no-collision "Control Panel" plate carrying a 16×16 grid of placed
 * controls and indicators. Mounts on any face ([FACE] = the outward normal) and
 * spins in 90° steps ([SPIN]) so the grid can be oriented freely.
 *
 * The block itself renders nothing ([RenderShape.INVISIBLE]); the plate body and
 * every placed element are drawn by the BER (a later slice). Until then the only
 * visible cue is the per-face selection [getShape] outline — a 2px slab flush to
 * the mounting face. Entities pass through ([getCollisionShape] is empty).
 *
 * The server ticker is the unified pin-link pull ([dev.nitka.nodewire.link.PinLinkEngine.tick]);
 * a no-op while the panel has no incoming links.
 */
class ControlPanelBlock(props: Properties) : Block(props), EntityBlock {

    init {
        registerDefaultState(
            stateDefinition.any().setValue(FACE, Direction.UP).setValue(SPIN, 0),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACE, SPIN)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
        val face = context.clickedFace
        // A sane default spin for flat (up/down) panels follows the player's
        // facing; wall panels keep spin 0. The Panel Key re-rotates later.
        val spin = if (face.axis.isVertical) context.horizontalDirection.get2DDataValue() and 3 else 0
        return defaultBlockState().setValue(FACE, face).setValue(SPIN, spin)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.INVISIBLE

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape = SHAPES[state.getValue(FACE)] ?: Shapes.block()

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape = Shapes.empty()

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        ControlPanelBlockEntity(pos, state)

    @Suppress("UNCHECKED_CAST")
    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != dev.nitka.nodewire.Registry.CONTROL_PANEL_BE.get()) return null
        val ticker = BlockEntityTicker<ControlPanelBlockEntity> { lvl, _, _, be ->
            dev.nitka.nodewire.link.PinLinkEngine.tick(lvl, be)
        }
        return ticker as BlockEntityTicker<T>
    }

    companion object {
        /** Outward normal of the panel surface (any of the 6 faces). */
        val FACE: DirectionProperty = BlockStateProperties.FACING

        /** In-plane rotation of the 16×16 grid, in 90° steps (0..3). */
        val SPIN: IntegerProperty = IntegerProperty.create("spin", 0, 3)

        private const val T = 2.0 / 16.0 // 2px plate thickness

        /** Per-face 2px slab flush to the mounting face (outward face = [FACE]). */
        private val SHAPES: Map<Direction, VoxelShape> = mapOf(
            Direction.UP to Shapes.box(0.0, 0.0, 0.0, 1.0, T, 1.0),
            Direction.DOWN to Shapes.box(0.0, 1.0 - T, 0.0, 1.0, 1.0, 1.0),
            Direction.NORTH to Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, T),
            Direction.SOUTH to Shapes.box(0.0, 0.0, 1.0 - T, 1.0, 1.0, 1.0),
            Direction.WEST to Shapes.box(0.0, 0.0, 0.0, T, 1.0, 1.0),
            Direction.EAST to Shapes.box(1.0 - T, 0.0, 0.0, 1.0, 1.0, 1.0),
        )
    }
}
