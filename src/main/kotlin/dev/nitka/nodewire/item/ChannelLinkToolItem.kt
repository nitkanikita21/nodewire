package dev.nitka.nodewire.item

import dev.nitka.nodewire.block.CameraBlockEntity
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.client.screen.AeroChannelPickerScreen
import dev.nitka.nodewire.client.screen.AeroTargetPickerScreen
import dev.nitka.nodewire.client.screen.ChannelPickerScreen
import dev.nitka.nodewire.client.screen.SensorReadingPickerScreen
import dev.nitka.nodewire.client.screen.SensorTargetPickerScreen
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.integration.aeronautics.AeroBlockKind
import dev.nitka.nodewire.integration.aeronautics.AeroChannel
import dev.nitka.nodewire.integration.sensor.SensorReading
import dev.nitka.nodewire.net.BindAeroSourcePacket
import dev.nitka.nodewire.net.BindCameraSourcePacket
import dev.nitka.nodewire.net.BindChannelPacket
import dev.nitka.nodewire.net.BindRemoteRedstonePacket
import dev.nitka.nodewire.net.BindSensorSourcePacket
import dev.nitka.nodewire.net.BindSideChannelPacket
import net.neoforged.fml.ModList
import net.neoforged.neoforge.capabilities.Capabilities
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
                    // Order: Aero → RemoteRedstone → classic ChannelOutput target.
                    val tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                    when {
                        tag.contains(NBT_SENSOR_SOURCE) ->
                            openSensorTargetPicker(stack, be, tag.getCompound(NBT_SENSOR_SOURCE))
                        tag.contains(NBT_AERO_SOURCE) ->
                            openAeroTargetPicker(stack, be, tag.getCompound(NBT_AERO_SOURCE))
                        tag.contains(NBT_CAMERA_SOURCE_POS) ->
                            openCameraLogicTargetPicker(stack, be, NbtUtils.readBlockPos(tag, NBT_CAMERA_SOURCE_POS).orElse(null))
                        tag.contains(NBT_REDSTONE_SOURCE_POS) ->
                            openRemoteRedstoneTargetPicker(stack, be, NbtUtils.readBlockPos(tag, NBT_REDSTONE_SOURCE_POS).orElse(null))
                        else -> openTargetPicker(stack, be)
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
                        // Mutual exclusion: clear the classic + camera source if set.
                        newTag.remove(NBT_CAMERA_SOURCE_POS)
                        newTag.remove(NBT_SOURCE_POS)
                        newTag.remove(NBT_SOURCE_NAME)
                        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(newTag))
                        actionBar("Aero source: ${k.displayName} / ${ch.displayName}", false)
                    },
                )
                return InteractionResult.SUCCESS
            }
        }

        // Branch 1a: Camera source-set — sneak + RMB on a Camera block makes it a
        // VIDEO source. Must run BEFORE the RemoteRedstone branch, which would
        // otherwise claim the camera as a generic redstone source.
        if (level.isClientSide && player.isShiftKeyDown &&
            level.getBlockEntity(pos) is CameraBlockEntity
        ) {
            val newTag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
            newTag.put(NBT_CAMERA_SOURCE_POS, NbtUtils.writeBlockPos(pos))
            // Mutual exclusion with the other source modes.
            newTag.remove(NBT_AERO_SOURCE)
            newTag.remove(NBT_REDSTONE_SOURCE_POS)
            newTag.remove(NBT_SOURCE_POS)
            newTag.remove(NBT_SOURCE_NAME)
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(newTag))
            actionBar("Camera source: (${pos.x},${pos.y},${pos.z}) — right-click a Screen to bind", false)
            return InteractionResult.SUCCESS
        }

        // Branch 1a': Block Sensor source-set — sneak + RMB on any world block
        // that exposes an item/fluid cap or a comparator analog signal. Runs
        // BEFORE RemoteRedstone (which also claims a generic world block),
        // mirroring the Camera-branch precedence.
        if (level.isClientSide && player.isShiftKeyDown) {
            val side = ctx.clickedFace
            val hasItems = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side) != null ||
                level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null) != null
            val hasFluids = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side) != null ||
                level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null) != null
            val hasComparator = level.getBlockState(pos).hasAnalogOutputSignal()
            if (hasItems || hasFluids || hasComparator) {
                val be2 = level.getBlockEntity(pos)
                val supported = SensorReading.supportedBy(be2).ifEmpty {
                    // Comparator-only blocks (no BE caps) still offer COMPARATOR.
                    if (hasComparator) listOf(SensorReading.COMPARATOR) else emptyList()
                }
                if (supported.isEmpty()) {
                    actionBar("Nothing readable here", true)
                    return InteractionResult.SUCCESS
                }
                val endpoint = EndpointRef.from(level, pos)
                Minecraft.getInstance().setScreen(
                    SensorReadingPickerScreen(supported, endpoint) { ep, reading ->
                        val newTag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                        val sensorTag = CompoundTag()
                        val endpointNbt = EndpointRef.CODEC.encodeStart(NbtOps.INSTANCE, ep)
                            .result().orElse(null) as? CompoundTag
                        if (endpointNbt != null) sensorTag.put("endpoint", endpointNbt)
                        sensorTag.putString("reading", reading.name)
                        sensorTag.putString("side", side.name)
                        newTag.put(NBT_SENSOR_SOURCE, sensorTag)
                        // Mutual exclusion with the other source modes.
                        newTag.remove(NBT_AERO_SOURCE)
                        newTag.remove(NBT_CAMERA_SOURCE_POS)
                        newTag.remove(NBT_REDSTONE_SOURCE_POS)
                        newTag.remove(NBT_SOURCE_POS)
                        newTag.remove(NBT_SOURCE_NAME)
                        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(newTag))
                        actionBar("Sensor source: ${reading.displayName}", false)
                    },
                )
                return InteractionResult.SUCCESS
            }
        }

        // Branch 1b: RemoteRedstone source-set — sneak + RMB on any non-Aero,
        // non-LogicBlock world block becomes a redstone source. We poll its
        // best-neighbour signal each server tick and feed it into a
        // channel_input on the target LogicBlock.
        if (level.isClientSide && player.isShiftKeyDown) {
            val newTag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
            newTag.put(NBT_REDSTONE_SOURCE_POS, NbtUtils.writeBlockPos(pos))
            // Mutual exclusion with the other source modes.
            newTag.remove(NBT_AERO_SOURCE)
            newTag.remove(NBT_CAMERA_SOURCE_POS)
            newTag.remove(NBT_SOURCE_POS)
            newTag.remove(NBT_SOURCE_NAME)
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(newTag))
            actionBar("Redstone source: (${pos.x},${pos.y},${pos.z})", false)
            return InteractionResult.SUCCESS
        }

        // Non-logic target — only valid as the "target" of an already-set
        // source. Shift+RMB here does nothing (no source to set on this
        // kind of block).
        if (level.isClientSide && !player.isShiftKeyDown) {
            val tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
            if (tag.contains(NBT_CAMERA_SOURCE_POS)) {
                handleCameraTarget(stack, ctx)
            } else {
                handleNonLogicTarget(stack, ctx)
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    private fun openRemoteRedstoneTargetPicker(stack: ItemStack, be: LogicBlockEntity, sourcePos: BlockPos?) {
        if (sourcePos == null) {
            actionBar("Invalid redstone source stored", true)
            return
        }
        val options = be.graph.nodes.values
            .filter { it.typeKey.path == "channel_input" }
            .mapNotNull { node ->
                val name = node.config.getString("name")
                if (name.isEmpty()) return@mapNotNull null
                val type = PinType.fromName(node.config.getString("type"))
                if (!dev.nitka.nodewire.graph.PinValueConversion
                        .canConvert(PinType.REDSTONE, type)) return@mapNotNull null
                ChannelPickerScreen.Option(name, type)
            }
        if (options.isEmpty()) {
            actionBar("No redstone-coercible channel inputs on this block.", true)
            return
        }
        val targetPos = be.blockPos
        Minecraft.getInstance().setScreen(
            ChannelPickerScreen("Target channel (redstone)", options) { picked ->
                PacketDistributor.sendToServer(
                    BindRemoteRedstonePacket(targetPos, picked, sourcePos),
                )
                val newTag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                newTag.remove(NBT_REDSTONE_SOURCE_POS)
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(newTag))
                actionBar(
                    "Bound (${sourcePos.x},${sourcePos.y},${sourcePos.z}) → (${targetPos.x},${targetPos.y},${targetPos.z})/$picked",
                    false,
                )
            },
        )
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

    private fun openSensorTargetPicker(stack: ItemStack, be: LogicBlockEntity, sensorTag: CompoundTag) {
        val readingName = sensorTag.getString("reading")
        val reading = SensorReading.fromName(readingName)
        if (reading == null) {
            actionBar("Unknown reading '$readingName' in stack", true)
            return
        }
        Minecraft.getInstance().setScreen(
            SensorTargetPickerScreen(be, reading.pinType) { nodeId ->
                val endpoint = EndpointRef.CODEC
                    .parse(NbtOps.INSTANCE, sensorTag.getCompound("endpoint"))
                    .result().orElse(null)
                if (endpoint == null) {
                    actionBar("Failed to parse sensor source endpoint", true)
                    return@SensorTargetPickerScreen
                }
                PacketDistributor.sendToServer(
                    BindSensorSourcePacket(
                        targetPos = be.blockPos,
                        nodeId = nodeId,
                        endpoint = endpoint,
                        reading = readingName,
                        side = sensorTag.getString("side"),
                        filter = ItemStack.EMPTY,
                    ),
                )
                val newTag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                newTag.remove(NBT_SENSOR_SOURCE)
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(newTag))
                actionBar("Sensor bound → ${be.blockPos}", false)
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
        val targetPos = ctx.clickedPos
        val state = ctx.level.getBlockState(targetPos)
        // Ask the target block what it accepts. A ChannelTargetProvider (e.g. the
        // video Screen) offers named channel slots; any other block falls back to
        // a redstone side. Keep only slots the source type can drive.
        val targetRef = dev.nitka.nodewire.endpoint.EndpointRef.from(mc.level!!, targetPos)
        val slots = dev.nitka.nodewire.block.ChannelTargetRegistry.lookup(state)
            .slotsFor(ctx.level, targetPos, state, ctx.clickedFace)
            .filter { dev.nitka.nodewire.graph.PinValueConversion.canConvert(sourceType, it.type) }
        if (slots.isEmpty()) {
            actionBar("Nothing here accepts a ${sourceType.name.lowercase()} channel", true)
            return
        }
        fun bind(slot: dev.nitka.nodewire.block.TargetSlot) {
            when (slot) {
                is dev.nitka.nodewire.block.TargetSlot.Channel ->
                    PacketDistributor.sendToServer(
                        dev.nitka.nodewire.net.BindChannelPacket(sourcePos, sourceName, targetRef, slot.name),
                    )
                is dev.nitka.nodewire.block.TargetSlot.Side ->
                    PacketDistributor.sendToServer(BindSideChannelPacket(sourcePos, sourceName, targetRef, slot.face))
            }
            actionBar("Bound $sourceName → (${targetPos.x},${targetPos.y},${targetPos.z})/${slot.name}", false)
        }
        if (slots.size == 1) {
            bind(slots[0])
        } else {
            mc.setScreen(
                ChannelPickerScreen("Target slot", slots.map { ChannelPickerScreen.Option(it.name, it.type) }) { picked ->
                    slots.firstOrNull { it.name == picked }?.let(::bind)
                },
            )
        }
    }

    /**
     * Right-click a block while a Camera source is set in the stack. If the
     * target exposes a VIDEO channel slot (e.g. a Screen), fire a
     * [BindCameraSourcePacket] one-shot binding `camera.handle → target slot`
     * and clear the stored source.
     */
    private fun handleCameraTarget(stack: ItemStack, ctx: UseOnContext) {
        val tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
        val cameraPos = NbtUtils.readBlockPos(tag, NBT_CAMERA_SOURCE_POS).orElse(null) ?: run {
            actionBar("Invalid camera source stored", true)
            return
        }
        val targetPos = ctx.clickedPos
        if (targetPos == cameraPos) {
            actionBar("Right-click a Screen, not the camera itself", true)
            return
        }
        val state = ctx.level.getBlockState(targetPos)
        val acceptsVideo = dev.nitka.nodewire.block.ChannelTargetRegistry.lookup(state)
            .slotsFor(ctx.level, targetPos, state, ctx.clickedFace)
            .filterIsInstance<dev.nitka.nodewire.block.TargetSlot.Channel>()
            .any { it.type == PinType.VIDEO }
        if (!acceptsVideo) {
            actionBar("Nothing here accepts a video channel", true)
            return
        }
        val targetRef = dev.nitka.nodewire.endpoint.EndpointRef.from(ctx.level, targetPos)
        // Empty channel name → server auto-picks the sink's VIDEO slot.
        PacketDistributor.sendToServer(BindCameraSourcePacket(cameraPos, targetRef, ""))
        val newTag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
        newTag.remove(NBT_CAMERA_SOURCE_POS)
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(newTag))
        actionBar(
            "Bound camera (${cameraPos.x},${cameraPos.y},${cameraPos.z}) → " +
                "(${targetPos.x},${targetPos.y},${targetPos.z})",
            false,
        )
    }

    /**
     * Camera source + right-click a LogicBlock: pick one of its VIDEO
     * `channel_input` nodes and fire a persistent [BindCameraSourcePacket]
     * binding `camera.handle → channel_input`, re-delivered each server tick.
     */
    private fun openCameraLogicTargetPicker(stack: ItemStack, be: LogicBlockEntity, cameraPos: BlockPos?) {
        if (cameraPos == null) {
            actionBar("Invalid camera source stored", true)
            return
        }
        if (cameraPos == be.blockPos) {
            actionBar("Source and target must differ", true)
            return
        }
        val options = be.graph.nodes.values
            .filter { it.typeKey.path == "channel_input" }
            .mapNotNull { node ->
                val name = node.config.getString("name")
                if (name.isEmpty()) return@mapNotNull null
                val type = PinType.fromName(node.config.getString("type"))
                if (!dev.nitka.nodewire.graph.PinValueConversion
                        .canConvert(PinType.VIDEO, type)) return@mapNotNull null
                ChannelPickerScreen.Option(name, type)
            }
        if (options.isEmpty()) {
            actionBar("No video channel inputs on this block.", true)
            return
        }
        val targetPos = be.blockPos
        Minecraft.getInstance().setScreen(
            ChannelPickerScreen("Target channel (video)", options) { picked ->
                val targetRef = dev.nitka.nodewire.endpoint.EndpointRef.from(be.level!!, targetPos)
                PacketDistributor.sendToServer(BindCameraSourcePacket(cameraPos, targetRef, picked))
                val newTag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                newTag.remove(NBT_CAMERA_SOURCE_POS)
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(newTag))
                actionBar(
                    "Bound camera (${cameraPos.x},${cameraPos.y},${cameraPos.z}) → " +
                        "(${targetPos.x},${targetPos.y},${targetPos.z})/$picked",
                    false,
                )
            },
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
        private const val NBT_REDSTONE_SOURCE_POS = "redstoneSourcePos"
        private const val NBT_CAMERA_SOURCE_POS = "cameraSourcePos"
        private const val NBT_SENSOR_SOURCE = "sensorSource"
    }
}
