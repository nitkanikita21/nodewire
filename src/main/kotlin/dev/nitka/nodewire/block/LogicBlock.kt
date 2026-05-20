package dev.nitka.nodewire.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.loading.FMLEnvironment

/**
 * Block placed in the world that carries a node graph in its
 * [LogicBlockEntity]. Right-click opens the editor on the client; the
 * BE ticks on the server, reading neighbour redstone for `side_input`
 * nodes and emitting on `side_output` faces via [getSignal].
 */
class LogicBlock(props: BlockBehaviour.Properties) : Block(props), EntityBlock {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        LogicBlockEntity(pos, state)

    /**
     * Server-only ticker that defers to [LogicBlockEntity.serverTick].
     * Client side returns null — the editor preview runs its own ticking
     * StatefulGraphEvaluator while the GUI is open.
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != dev.nitka.nodewire.Registry.LOGIC_BLOCK_BE.get()) return null
        val ticker = BlockEntityTicker<LogicBlockEntity> { lvl, p, s, be ->
            be.serverTick(lvl, p, s)
        }
        return ticker as BlockEntityTicker<T>
    }

    /** We can emit redstone on a face. Returning true makes vanilla call [getSignal]. */
    override fun isSignalSource(state: BlockState): Boolean = true

    /**
     * Power on the face that vanilla is querying. `direction` is the
     * direction **from the querying neighbour towards us** (see
     * [SignalGetter.getBestNeighborSignal]: `getSignal(pos.relative(d), d)`),
     * so the face of ours that actually emits toward that neighbour is
     * `direction.opposite`. Reads the BE's cached per-face output map
     * populated by the last tick — keyed by the user-facing emitting face.
     */
    override fun getSignal(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        direction: Direction,
    ): Int {
        val be = level.getBlockEntity(pos) as? LogicBlockEntity ?: return 0
        return be.faceOutputs[direction.opposite] ?: 0
    }

    /** Same value for direct (= strong) signal — we don't distinguish for now. */
    override fun getDirectSignal(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        direction: Direction,
    ): Int = getSignal(state, level, pos, direction)

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                dev.nitka.nodewire.client.NodeEditorLauncher.open(pos)
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }
}
