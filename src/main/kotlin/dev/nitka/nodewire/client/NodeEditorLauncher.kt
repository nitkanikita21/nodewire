package dev.nitka.nodewire.client

import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.ui.dev.DemoScreen
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

/**
 * Client-only entry point invoked from [LogicBlock.use] via DistExecutor.
 * Opens the node editor screen for the BE at [pos].
 *
 * For Phase 3 the screen is a placeholder — the demo screen with a chat
 * confirmation. Phase 9 swaps in the real `NodeEditorScreen` that reads
 * the synced BE and renders the graph.
 */
object NodeEditorLauncher {
    fun open(pos: BlockPos) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val be = level.getBlockEntity(pos) as? LogicBlockEntity
        if (be == null) {
            mc.player?.displayClientMessage(
                Component.literal("§cNo logic-block at $pos"), true
            )
            return
        }
        mc.player?.displayClientMessage(
            Component.literal("§7Editor for ${pos.toShortString()} — graph has ${be.graph.nodes.size} nodes (placeholder)"),
            true,
        )
        // Placeholder — swap for NodeEditorScreen in Phase 9.
        mc.setScreen(DemoScreen())
    }
}
