package dev.nitka.nodewire.item

import dev.nitka.nodewire.block.LogicBlockEntity
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.nbt.NbtUtils
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.Block

/**
 * In-world tool to bind named channels between two logic blocks.
 *
 *   * Shift + right-click a logic block ⇒ remembers it as the source.
 *   * Right-click another logic block ⇒ binds every name-matching, type-
 *     matching channel between source [channel_output] nodes and target
 *     [channel_input] nodes. The source BE records the bindings;
 *     [LogicBlockEntity.serverTick] on the source then pushes the values
 *     each tick into the target's external-channel-inputs map.
 *
 * The selected source is stored on the [ItemStack] itself (NBT
 * `source_pos`), so several players carrying their own tools don't share
 * state. Status messages go through the action bar.
 *
 * Mismatched names or types simply aren't bound — silent. Re-binding the
 * same target replaces prior bindings to that BE so the user never ends
 * up with stale duplicates after a name/type rewire.
 */
class ChannelLinkToolItem(props: Properties) : Item(props) {

    /**
     * Intercepts the right-click BEFORE [LogicBlock.use] gets a chance to
     * open the editor screen. Standard interaction order in 1.20.1 is
     * onItemUseFirst → Block.use → Item.useOn, so we own the first slot
     * and return SUCCESS to short-circuit the rest.
     */
    override fun onItemUseFirst(stack: net.minecraft.world.item.ItemStack, ctx: UseOnContext): InteractionResult =
        handle(ctx)

    override fun useOn(ctx: UseOnContext): InteractionResult = handle(ctx)

    private fun handle(ctx: UseOnContext): InteractionResult {
        val level = ctx.level
        val pos = ctx.clickedPos
        val player = ctx.player ?: return InteractionResult.PASS
        val stack = ctx.itemInHand
        val be = level.getBlockEntity(pos) as? LogicBlockEntity
            ?: return InteractionResult.PASS

        val shift = player.isShiftKeyDown
        if (shift) {
            stack.orCreateTag.put(NBT_SOURCE_POS, NbtUtils.writeBlockPos(pos))
            if (!level.isClientSide) {
                val outs = be.graph.nodes.values.count { it.typeKey.path == "channel_output" }
                player.displayClientMessage(
                    Component.literal("Source set at ${pos.toShortString()} (")
                        .append(Component.literal("$outs out").withStyle(ChatFormatting.AQUA))
                        .append(")"),
                    true,
                )
            }
            return InteractionResult.sidedSuccess(level.isClientSide)
        }

        val tag = stack.tag ?: run {
            if (!level.isClientSide) player.displayClientMessage(NO_SOURCE_MSG, true)
            return InteractionResult.sidedSuccess(level.isClientSide)
        }
        if (!tag.contains(NBT_SOURCE_POS)) {
            if (!level.isClientSide) player.displayClientMessage(NO_SOURCE_MSG, true)
            return InteractionResult.sidedSuccess(level.isClientSide)
        }
        val sourcePos: BlockPos = NbtUtils.readBlockPos(tag.getCompound(NBT_SOURCE_POS))
        if (sourcePos == pos) {
            if (!level.isClientSide) {
                player.displayClientMessage(
                    Component.literal("Source and target must differ").withStyle(ChatFormatting.RED),
                    true,
                )
            }
            return InteractionResult.sidedSuccess(level.isClientSide)
        }
        if (!level.isClientSide) {
            val sourceBe = level.getBlockEntity(sourcePos) as? LogicBlockEntity
            if (sourceBe == null) {
                player.displayClientMessage(
                    Component.literal("Source block no longer exists").withStyle(ChatFormatting.RED),
                    true,
                )
                stack.orCreateTag.remove(NBT_SOURCE_POS)
                return InteractionResult.SUCCESS
            }
            val bound = sourceBe.bindChannelsTo(be)
            if (bound > 0) {
                // Push the new bindings to clients so WireWorldRenderer
                // picks them up without needing a chunk-reload.
                level.sendBlockUpdated(
                    sourcePos,
                    sourceBe.blockState,
                    sourceBe.blockState,
                    Block.UPDATE_CLIENTS,
                )
            }
            val msg = if (bound > 0) {
                Component.literal("Bound $bound channel(s) ")
                    .append(Component.literal(sourcePos.toShortString()).withStyle(ChatFormatting.AQUA))
                    .append(" → ")
                    .append(Component.literal(pos.toShortString()).withStyle(ChatFormatting.AQUA))
            } else {
                Component.literal("No matching channels (same name + type)")
                    .withStyle(ChatFormatting.YELLOW)
            }
            player.displayClientMessage(msg, true)
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    companion object {
        private const val NBT_SOURCE_POS = "source_pos"
        private val NO_SOURCE_MSG: Component =
            Component.literal("Pick a source first: Shift + right-click a logic block")
                .withStyle(ChatFormatting.YELLOW)
    }
}
