package dev.nitka.nodewire.item

import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.client.screen.ChannelPickerScreen
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.net.BindChannelPacket
import dev.nitka.nodewire.net.BindSideChannelPacket
import dev.nitka.nodewire.net.NodewireNetwork
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.nbt.NbtUtils
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext

/**
 * Channel Link Tool — explicit channel-by-channel wiring.
 *
 * Sneak + right-click a logic block → opens [ChannelPickerScreen] with
 * every named `channel_output` on that block. Pick one ⇒ the source pos
 * and selected channel name are saved into the stack's NBT.
 *
 * Right-click any logic block while a source is set → opens the picker
 * again with the target's `channel_input` nodes filtered to those whose
 * [PinType] matches the source's. Pick one ⇒ [BindChannelPacket] flies to
 * the server, which calls [LogicBlockEntity.addBinding] and pushes the
 * BE update so other clients see the new wire.
 *
 * All UX runs on the client; the server is only contacted once at bind
 * commit. Stack NBT lives on the client copy of the stack — it's just
 * memory between the two clicks, never round-trips.
 */
class ChannelLinkToolItem(props: Properties) : Item(props) {

    /**
     * Runs *before* [LogicBlock.use] in the 1.20.1 interaction order, so
     * the editor screen never opens when this tool is held. Returns
     * SUCCESS to stop both Block.use and Item.useOn from firing.
     */
    override fun onItemUseFirst(stack: ItemStack, ctx: UseOnContext): InteractionResult =
        handle(stack, ctx)

    override fun useOn(ctx: UseOnContext): InteractionResult = handle(ctx.itemInHand, ctx)

    private fun handle(stack: ItemStack, ctx: UseOnContext): InteractionResult {
        val level = ctx.level
        val player = ctx.player ?: return InteractionResult.PASS
        val pos = ctx.clickedPos
        val be = level.getBlockEntity(pos) as? LogicBlockEntity

        if (be != null) {
            // Logic block: source-set / target-pick UX as before.
            if (level.isClientSide) {
                if (player.isShiftKeyDown) openSourcePicker(stack, be)
                else openTargetPicker(stack, be)
            }
            return InteractionResult.sidedSuccess(level.isClientSide)
        }

        // Non-logic target — only valid as the "target" of an already-set
        // source. Shift+RMB here does nothing (no source to set on this
        // kind of block).
        if (level.isClientSide && !player.isShiftKeyDown) {
            handleNonLogicTarget(stack, ctx)
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    /**
     * Right-click on a non-logic block face. If a source channel is set in
     * the stack and is redstone-coercible (BOOL / INT / REDSTONE), send a
     * BindSideChannelPacket binding `source.channel → target face`. The
     * target face is `ctx.clickedFace` — the face the player aimed at.
     *
     * Adjacency: the target must sit directly adjacent to the source on
     * the opposite of [clickedFace]. We validate client-side for fast user
     * feedback; the server re-validates.
     */
    private fun handleNonLogicTarget(stack: ItemStack, ctx: UseOnContext) {
        val tag = stack.tag
        if (tag == null || !tag.contains(NBT_SOURCE_POS) || !tag.contains(NBT_SOURCE_NAME)) {
            actionBar("Pick a source first: Shift + right-click a logic block", true)
            return
        }
        val sourcePos = NbtUtils.readBlockPos(tag.getCompound(NBT_SOURCE_POS))
        val sourceName = tag.getString(NBT_SOURCE_NAME)
        val mc = Minecraft.getInstance()
        val sourceBe = mc.level?.getBlockEntity(sourcePos) as? LogicBlockEntity
        if (sourceBe == null) {
            actionBar("Source block no longer exists", true)
            return
        }
        val sourceNode = sourceBe.graph.nodes.values.firstOrNull {
            it.typeKey.path == "channel_output" && it.config.getString("name") == sourceName
        }
        if (sourceNode == null) {
            actionBar("Source channel '$sourceName' no longer exists", true)
            return
        }
        val sourceType = PinType.fromName(sourceNode.config.getString("type"))
        if (sourceType != PinType.BOOL && sourceType != PinType.INT && sourceType != PinType.REDSTONE) {
            actionBar("Channel type ${sourceType.name.lowercase()} can't drive a redstone side", true)
            return
        }
        val targetPos = ctx.clickedPos
        val targetSide = ctx.clickedFace
        // No adjacency requirement — virtual signals travel via VirtualSignalMap
        // surfaced through a Level mixin, so the source can be anywhere.
        NodewireNetwork.CHANNEL.sendToServer(
            BindSideChannelPacket(sourcePos, sourceName, targetPos, targetSide),
        )
        actionBar(
            "Bound ${sourcePos.toShortString()}/$sourceName → ${targetPos.toShortString()} ${targetSide.name.lowercase()}",
            false,
        )
    }

    private fun openSourcePicker(stack: ItemStack, be: LogicBlockEntity) {
        val mc = Minecraft.getInstance()
        val srcPos = be.blockPos
        mc.setScreen(
            dev.nitka.nodewire.client.screen.BindingsManagerScreen(be) { picked ->
                stack.orCreateTag.put(NBT_SOURCE_POS, NbtUtils.writeBlockPos(srcPos))
                stack.orCreateTag.putString(NBT_SOURCE_NAME, picked)
                actionBar("Source: ${srcPos.toShortString()} / $picked", false)
            },
        )
    }

    private fun openTargetPicker(stack: ItemStack, be: LogicBlockEntity) {
        val tag = stack.tag
        if (tag == null || !tag.contains(NBT_SOURCE_POS) || !tag.contains(NBT_SOURCE_NAME)) {
            actionBar("Pick a source first: Shift + right-click a logic block", true)
            return
        }
        val sourcePos = NbtUtils.readBlockPos(tag.getCompound(NBT_SOURCE_POS))
        val sourceName = tag.getString(NBT_SOURCE_NAME)
        if (sourcePos == be.blockPos) {
            actionBar("Source and target must differ", true)
            return
        }

        val mc = Minecraft.getInstance()
        val sourceBe = mc.level?.getBlockEntity(sourcePos) as? LogicBlockEntity
        if (sourceBe == null) {
            actionBar("Source block no longer exists", true)
            return
        }
        val sourceNode = sourceBe.graph.nodes.values.firstOrNull {
            it.typeKey.path == "channel_output" && it.config.getString("name") == sourceName
        }
        if (sourceNode == null) {
            actionBar("Source channel '$sourceName' no longer exists", true)
            return
        }
        val sourceType = PinType.fromName(sourceNode.config.getString("type"))

        // Only show target inputs that match the source's type.
        val options = be.graph.nodes.values
            .filter { it.typeKey.path == "channel_input" }
            .mapNotNull { node ->
                val name = node.config.getString("name")
                if (name.isEmpty()) return@mapNotNull null
                val type = PinType.fromName(node.config.getString("type"))
                if (type != sourceType) return@mapNotNull null
                ChannelPickerScreen.Option(name, type)
            }
        if (options.isEmpty()) {
            actionBar("No ${sourceType.name.lowercase()} channel inputs on this block.", true)
            return
        }
        val targetPos = be.blockPos
        mc.setScreen(ChannelPickerScreen("Target channel (${sourceType.name.lowercase()})", options) { picked ->
            val targetRef = dev.nitka.nodewire.endpoint.EndpointRef.from(mc.level!!, targetPos)
            NodewireNetwork.CHANNEL.sendToServer(
                BindChannelPacket(sourcePos, sourceName, targetRef, picked),
            )
            actionBar(
                "Bound ${sourcePos.toShortString()}/$sourceName → ${targetPos.toShortString()}/$picked",
                false,
            )
        })
    }

    private fun actionBar(message: String, warn: Boolean) {
        val component = if (warn) {
            Component.literal(message).withStyle(ChatFormatting.YELLOW)
        } else {
            Component.literal(message)
        }
        Minecraft.getInstance().player?.displayClientMessage(component, true)
    }

    companion object {
        private const val NBT_SOURCE_POS = "source_pos"
        private const val NBT_SOURCE_NAME = "source_name"
    }
}
