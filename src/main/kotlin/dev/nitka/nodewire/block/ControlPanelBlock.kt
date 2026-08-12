package dev.nitka.nodewire.block

import dev.nitka.nodewire.block.panel.PanelGrid
import dev.nitka.nodewire.item.ChannelLinkToolItem
import dev.nitka.nodewire.item.PanelElementItem
import dev.nitka.nodewire.item.PanelKeyItem
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
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
    ): VoxelShape =
        // Plate + raised element boxes (BE-cached): the raycast targets
        // individual elements from any angle, and the element under the cursor
        // gets its own selection outline (PanelHighlightRenderer).
        (level.getBlockEntity(pos) as? ControlPanelBlockEntity)?.blockShape()
            ?: SHAPES[state.getValue(FACE)] ?: Shapes.block()

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape = Shapes.empty()

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        ControlPanelBlockEntity(pos, state)

    /**
     * Empty-hand RMB → operate the element under the cursor. The hit resolves
     * through the raised element boxes in [getShape], so aiming at an element's
     * side (any approach angle) works too. Sneak is forwarded (a selector steps
     * backwards); other interactive elements ignore it.
     */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val be = level.getBlockEntity(pos) as? ControlPanelBlockEntity ?: return InteractionResult.PASS
        return operate(level, be, state, pos, player, hit)
    }

    /**
     * Item-in-hand RMB. Element items, the Panel Key and the Link Tool own their
     * own face interactions (place / remove / configure / bind) — pass through to
     * their `useOn`. A plain non-sneak click with any other item operates the
     * element (so an operator can tap a button with a controller in hand).
     */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): ItemInteractionResult {
        val item = stack.item
        if (item is PanelElementItem || item is PanelKeyItem || item is ChannelLinkToolItem) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        }
        if (player.isShiftKeyDown) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        }
        if (level.isClientSide) return ItemInteractionResult.SUCCESS
        val be = level.getBlockEntity(pos) as? ControlPanelBlockEntity
            ?: return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        return if (operate(level, be, state, pos, player, hit) == InteractionResult.CONSUME) {
            ItemInteractionResult.CONSUME
        } else {
            ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        }
    }

    private fun operate(
        level: Level,
        be: ControlPanelBlockEntity,
        state: BlockState,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        val gh = gridHit(state, pos, hit.location) ?: return InteractionResult.PASS
        val handled = be.handleOperate(gh.cell, gh.uFrac, gh.vFrac, player.isShiftKeyDown, level.gameTime)
        return if (handled) InteractionResult.CONSUME else InteractionResult.PASS
    }

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
            be.serverTick(lvl.gameTime)
        }
        return ticker as BlockEntityTicker<T>
    }

    companion object {
        /** Outward normal of the panel surface (any of the 6 faces). */
        val FACE: DirectionProperty = BlockStateProperties.FACING

        /** In-plane rotation of the 16×16 grid, in 90° steps (0..3). */
        val SPIN: IntegerProperty = IntegerProperty.create("spin", 0, 3)

        /** World hit [loc] on a panel at [pos] → its grid cell + sub-cell fraction. */
        fun gridHit(state: BlockState, pos: BlockPos, loc: Vec3): PanelGrid.Hit? =
            PanelGrid.hitToGrid(
                state.getValue(FACE),
                state.getValue(SPIN),
                loc.x - pos.x,
                loc.y - pos.y,
                loc.z - pos.z,
            )

        /** Snap a footprint anchor so a `cols×rows` element stays on the grid. */
        fun clampAnchor(cell: PanelGrid.Cell, cols: Int, rows: Int): PanelGrid.Cell =
            PanelGrid.Cell(
                cell.x.coerceIn(0, PanelGrid.SIZE - cols),
                cell.y.coerceIn(0, PanelGrid.SIZE - rows),
            )

        /** The bare 2px plate slab for [face] (the empty-panel raycast shape and
         *  the hovered-outline fallback when no element is under the cursor). */
        fun plateShape(face: Direction): VoxelShape = SHAPES[face] ?: Shapes.block()

        /**
         * Plate SHAPE thickness. Must hug the RENDERED plate front
         * (`PanelSpace.FACE_GAP` + the plate outset ≈ 0.025): a thicker slab
         * floats an invisible pick-plane in front of the drawn surface (parallax
         * mis-picks at an angle) and swallows the raised element boxes (≤0.08),
         * so the raycast could never target an element individually.
         */
        private const val T = 0.03

        /**
         * Per-face thin slab on the **mounting** (−FACE) side of the cell, so the
         * plate sits against the block it's attached to and the display faces
         * outward along [FACE]. Matches the renderer's display plane.
         */
        private val SHAPES: Map<Direction, VoxelShape> = mapOf(
            Direction.UP to Shapes.box(0.0, 0.0, 0.0, 1.0, T, 1.0),
            Direction.DOWN to Shapes.box(0.0, 1.0 - T, 0.0, 1.0, 1.0, 1.0),
            Direction.SOUTH to Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, T),
            Direction.NORTH to Shapes.box(0.0, 0.0, 1.0 - T, 1.0, 1.0, 1.0),
            Direction.EAST to Shapes.box(0.0, 0.0, 0.0, T, 1.0, 1.0),
            Direction.WEST to Shapes.box(1.0 - T, 0.0, 0.0, 1.0, 1.0, 1.0),
        )
    }
}
