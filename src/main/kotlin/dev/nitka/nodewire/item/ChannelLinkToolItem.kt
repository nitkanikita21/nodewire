package dev.nitka.nodewire.item

import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.client.screen.AeroChannelPickerScreen
import dev.nitka.nodewire.client.screen.AeroTargetPickerScreen
import dev.nitka.nodewire.client.screen.ChannelPickerScreen
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.integration.aeronautics.AeroBlockKind
import dev.nitka.nodewire.integration.aeronautics.AeroChannel
import dev.nitka.nodewire.net.BindAeroSourcePacket
import dev.nitka.nodewire.net.BindChannelPacket
import dev.nitka.nodewire.net.BindSideChannelPacket
import net.neoforged.fml.ModList
import net.neoforged.neoforge.network.PacketDistributor
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtUtils
import net.minecraft.network.chat.Component
import net.minecraft.world.item.component.CustomData
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
                if (player.isShiftKeyDown) {
                    openSourcePicker(stack, be)
                } else {
                    // Branch 2: Aero target-bind — check BEFORE the classic source check.
                    val tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                    if (tag.contains(NBT_AERO_SOURCE)) {
                        openAeroTargetPicker(stack, be, tag.getCompound(NBT_AERO_SOURCE))
                    } else {
                        openTargetPicker(stack, be)
                    }
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide)
        }

        // Branch 1: Aero source-set — sneak + RMB on a supported Aero block.
        if (level.isClientSide && player.isShiftKeyDown &&
            ModList.get().isLoaded("aeronautics")
        ) {
            val anyBe = level.getBlockEntity(pos)
            val kind = if (anyBe != null) AeroBlockKind.fromBE(anyBe) else null
            if (kind != null) {
                val endpoint = EndpointRef.from(level, pos)
                Minecraft.getInstance().setScreen(
                    AeroChannelPickerScreen(kind, endpoint) { ep, k, ch ->
                        val newTag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                        val aeroTag = CompoundTag()
                        val endpointNbt = EndpointRef.CODEC
                            .encodeStart(NbtOps.INSTANCE, ep)
                            .result().orElse(null) as? CompoundTag
                        if (endpointNbt != null) aeroTag.put("endpoint", endpointNbt)
                        aeroTag.putString("blockKind", k.name)
                        aeroTag.putString("channel", ch.name)
                        newTag.put(NBT_AERO_SOURCE, aeroTag)
                        // Mutual exclusion: clear the classic source if set.
                        newTag.remove(NBT_SOURCE_POS)
                        newTag.remove(NBT_SOURCE_NAME)
                        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(newTag))
                        actionBar("Aero source: ${k.displayName} / ${ch.displayName}", false)
                    },
                )
                return InteractionResult.SUCCESS
            }
        }

        // Non-logic target — only valid as the "target" of an already-set
        // source. Shift+RMB here does nothing (no source to set on this
        // kind of block).
        if (level.isClientSide && !player.isShiftKeyDown) {
            handleNonLogicTarget(stack, ctx)
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    private fun openAeroTargetPicker(stack: ItemStack, be: LogicBlockEntity, aeroTag: CompoundTag) {
        val channelName = aeroTag.getString("channel")
        val channel = AeroChannel.fromName(channelName)
        if (channel == null) {
            actionBar("Unknown aero channel '$channelName' in stack", true)
            return
        }
        val mc = Minecraft.getInstance()
        mc.setScreen(
            AeroTargetPickerScreen(be, channel.pinType) { nodeId ->
                val endpointTag = aeroTag.getCompound("endpoint")
                val endpoint = EndpointRef.CODEC
                    .parse(NbtOps.INSTANCE, endpointTag)
                    .result().orElse(null)
                if (endpoint == null) {
                    actionBar("Failed to parse aero source endpoint", true)
                    return@AeroTargetPickerScreen
                }
                PacketDistributor.sendToServer(
                    BindAeroSourcePacket(
                        targetPos = be.blockPos,
                        nodeId = nodeId,
                        endpoint = endpoint,
                        blockKind = aeroTag.getString("blockKind"),
                        channel = channelName,
                    ),
                )
                // Clear aeroSource from stack.
                val newTag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                newTag.remove(NBT_AERO_SOURCE)
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(newTag))
                actionBar("Aero bound → ${be.blockPos}", false)
            },
        )
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
        val tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
        if (!tag.contains(NBT_SOURCE_POS) || !tag.contains(NBT_SOURCE_NAME)) {
            actionBar("Pick a source first: Shift + right-click a logic block", true)
            return
        }
        val sourcePos = NbtUtils.readBlockPos(tag, NBT_SOURCE_POS).orElse(null) ?: run {
            actionBar("Invalid source position stored", true)
            return
        }
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
        val targetRef = dev.nitka.nodewire.endpoint.EndpointRef.from(mc.level!!, targetPos)
        PacketDistributor.sendToServer(BindSideChannelPacket(sourcePos, sourceName, targetRef, targetSide))
        actionBar(
            "Bound (${sourcePos.x},${sourcePos.y},${sourcePos.z})/$sourceName → (${targetPos.x},${targetPos.y},${targetPos.z}) ${targetSide.name.lowercase()}",
            false,
        )
    }

    private fun openSourcePicker(stack: ItemStack, be: LogicBlockEntity) {
        val mc = Minecraft.getInstance()
        val srcPos = be.blockPos
        mc.setScreen(
            dev.nitka.nodewire.client.screen.BindingsManagerScreen(be) { picked ->
                val newTag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                newTag.put(NBT_SOURCE_POS, NbtUtils.writeBlockPos(srcPos))
                newTag.putString(NBT_SOURCE_NAME, picked)
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(newTag))
                actionBar("Source: (${srcPos.x},${srcPos.y},${srcPos.z}) / $picked", false)
            },
        )
    }

    private fun openTargetPicker(stack: ItemStack, be: LogicBlockEntity) {
        val tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
        if (!tag.contains(NBT_SOURCE_POS) || !tag.contains(NBT_SOURCE_NAME)) {
            actionBar("Pick a source first: Shift + right-click a logic block", true)
            return
        }
        val sourcePos = NbtUtils.readBlockPos(tag, NBT_SOURCE_POS).orElse(null) ?: run {
            actionBar("Invalid source position stored", true)
            return
        }
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
            PacketDistributor.sendToServer(BindChannelPacket(sourcePos, sourceName, targetRef, picked))
            actionBar(
                "Bound (${sourcePos.x},${sourcePos.y},${sourcePos.z})/$sourceName → (${targetPos.x},${targetPos.y},${targetPos.z})/$picked",
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
        private const val NBT_AERO_SOURCE = "aeroSource"
    }
}
