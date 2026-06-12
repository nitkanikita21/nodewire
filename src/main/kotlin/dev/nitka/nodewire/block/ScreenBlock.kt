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

    /**
     * Touch-screen: RMB on the panel face queues a tap (in panel-surface px)
     * on the LogicBlock that feeds this panel's video — scripts read it via
     * `channel_input` nodes named `touch` (VEC2) / `touch_down` (BOOL).
     * Sneak bypasses (vanilla convention: lets you place blocks against it).
     *
     * Taps must ALSO work with an item in hand (an operator holds a controller)
     * — vanilla only calls [useWithoutItem] for an EMPTY hand
     * (`TRY_WITH_EMPTY_HAND`), so [useItemOn] routes to the same logic for any
     * item except the Channel Link Tool (which owns its own screen flows).
     */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun useWithoutItem(
        state: BlockState,
        level: net.minecraft.world.level.Level,
        pos: BlockPos,
        player: net.minecraft.world.entity.player.Player,
        hit: net.minecraft.world.phys.BlockHitResult,
    ): net.minecraft.world.InteractionResult {
        if (!isTouch(state, player, hit)) return net.minecraft.world.InteractionResult.PASS
        if (level.isClientSide) return net.minecraft.world.InteractionResult.SUCCESS
        return if (tryTouch(level, pos, player, hit)) net.minecraft.world.InteractionResult.CONSUME
        else net.minecraft.world.InteractionResult.PASS
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun useItemOn(
        stack: net.minecraft.world.item.ItemStack,
        state: BlockState,
        level: net.minecraft.world.level.Level,
        pos: BlockPos,
        player: net.minecraft.world.entity.player.Player,
        hand: net.minecraft.world.InteractionHand,
        hit: net.minecraft.world.phys.BlockHitResult,
    ): net.minecraft.world.ItemInteractionResult {
        // The Link Tool owns its own screen interactions (bind / panel resize).
        if (stack.item is dev.nitka.nodewire.item.ChannelLinkToolItem ||
            !isTouch(state, player, hit)
        ) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        }
        if (level.isClientSide) return net.minecraft.world.ItemInteractionResult.SUCCESS
        tryTouch(level, pos, player, hit)
        // Consume regardless so the held block/item is never placed/used by a tap.
        return net.minecraft.world.ItemInteractionResult.CONSUME
    }

    /** A non-sneak click on the DISPLAY face. */
    private fun isTouch(
        state: BlockState,
        player: net.minecraft.world.entity.player.Player,
        hit: net.minecraft.world.phys.BlockHitResult,
    ): Boolean = !player.isShiftKeyDown && hit.direction == state.getValue(FACING)

    /** Server side of a tap; action-bar feedback on every path (diagnosable). */
    private fun tryTouch(
        level: net.minecraft.world.level.Level,
        pos: BlockPos,
        player: net.minecraft.world.entity.player.Player,
        hit: net.minecraft.world.phys.BlockHitResult,
    ): Boolean {
        fun bar(s: String, warn: Boolean) = player.displayClientMessage(
            net.minecraft.network.chat.Component.literal(s)
                .withStyle(if (warn) net.minecraft.ChatFormatting.YELLOW else net.minecraft.ChatFormatting.AQUA),
            true,
        )
        val be = level.getBlockEntity(pos) as? ScreenBlockEntity
        if (be == null) {
            LOG.info("[NW-TOUCH] no ScreenBlockEntity at {}", pos)
            return false
        }
        val px = be.handleTouchDiag(pos, hit.location.x, hit.location.y, hit.location.z) { reason ->
            LOG.info("[NW-TOUCH] tap at {} rejected: {}", pos, reason)
            bar("Tap ignored: $reason", true)
        }
        if (px != null) {
            LOG.info("[NW-TOUCH] tap at {} -> surface px ({}, {})", pos, px[0], px[1])
            bar("Tap (${px[0]}, ${px[1]})", false)
        }
        return px != null
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onRemove(
        state: BlockState,
        level: net.minecraft.world.level.Level,
        pos: BlockPos,
        newState: BlockState,
        isMoving: Boolean,
    ) {
        // Panel teardown — must run BEFORE super drops the BE.
        //  * removed ANCHOR  → free its covered blocks (each resumes 1×1).
        //  * removed COVERED cell → dissolve the WHOLE panel: the anchor kept
        //    its span and rendered the full quad floating over the hole
        //    (user report 2026-06-11). Survivors resume 1×1; re-drag the
        //    corners to rebuild a smaller panel.
        if (!state.`is`(newState.block) && !level.isClientSide) {
            val be = level.getBlockEntity(pos) as? ScreenBlockEntity
            if (be != null) {
                val anchorPos = be.coveredBy()
                if (anchorPos == null) {
                    be.releaseSpan(level)
                } else {
                    val anchor = level.getBlockEntity(anchorPos) as? ScreenBlockEntity
                    if (anchor != null && anchor.coveredBy() == null) {
                        anchor.releaseSpan(level)
                        anchor.setSpan(1, 1)
                    }
                }
            }
        }
        super.onRemove(state, level, pos, newState, isMoving)
    }

    /**
     * Server ticker = the unified pin-link pull: prune stale links, sample
     * each bound source pin (camera handle, logic channel…) into this
     * screen's `screen` input. No-ops instantly while the link list is empty.
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T : net.minecraft.world.level.block.entity.BlockEntity> getTicker(
        level: net.minecraft.world.level.Level,
        state: BlockState,
        type: net.minecraft.world.level.block.entity.BlockEntityType<T>,
    ): net.minecraft.world.level.block.entity.BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != dev.nitka.nodewire.Registry.SCREEN_BLOCK_BE.get()) return null
        val ticker = net.minecraft.world.level.block.entity.BlockEntityTicker<ScreenBlockEntity> { lvl, _, _, be ->
            dev.nitka.nodewire.link.PinLinkEngine.tick(lvl, be)
        }
        return ticker as net.minecraft.world.level.block.entity.BlockEntityTicker<T>
    }

    companion object {
        val FACING: DirectionProperty = HorizontalDirectionalBlock.FACING
        private val LOG = com.mojang.logging.LogUtils.getLogger()
    }
}
