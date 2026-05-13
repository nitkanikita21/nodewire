package dev.nitka.nodewire.block

import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.DistExecutor

/**
 * Block placed in the world that carries a node graph in its
 * [LogicBlockEntity]. Right-click → opens the in-world editor (handled on
 * the client; the server just returns `SUCCESS` to acknowledge).
 *
 * No fancy directional state in MVP — it's a plain isotropic cube. Face-
 * specific I/O is modeled by the `block_input` / `block_output` node
 * types, not by block rotation.
 */
class LogicBlock(props: BlockBehaviour.Properties) : Block(props), EntityBlock {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        LogicBlockEntity(pos, state)

    override fun use(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): InteractionResult {
        // The editor is a pure client-side Screen for MVP — no Menu/Container
        // because there's no slotted inventory to sync. The server just OKs
        // the interaction; the client opens the editor and reads its graph
        // directly from the synced BE.
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                Runnable { dev.nitka.nodewire.client.NodeEditorLauncher.open(pos) }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }
}
