package dev.nitka.nodewire.item

import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.client.screen.ChannelPickerScreen
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValueConversion
import dev.nitka.nodewire.link.LinkContext
import dev.nitka.nodewire.link.LinkPin
import dev.nitka.nodewire.link.PinPorts
import dev.nitka.nodewire.net.BindPinPacket
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.context.UseOnContext
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Channel Link Tool — UNIFIED pin-to-pin wiring.
 *
 * Anything whose BlockEntity implements [dev.nitka.nodewire.link.PinPort]
 * (or has a [PinPorts] adapter — Aeronautics blocks, capability containers,
 * plain redstone blocks) is linkable, with ONE flow for every kind:
 *
 *  * **Sneak + RMB** a block → enumerate its OUTPUT pins. One pin arms
 *    instantly; several open a picker. The armed source (endpoint + pin +
 *    type) lives in the stack's client-side NBT between the two clicks.
 *  * **RMB** a block with a source armed → enumerate its INPUT pins,
 *    filtered to those the source type converts into. One pin commits
 *    instantly; several open a picker. Commit = a single [BindPinPacket];
 *    the server stores a [dev.nitka.nodewire.link.PinLink] on the target
 *    (or a SideBinding for the redstone-face fallback).
 *
 * A Logic Block's sneak+RMB opens the Link Manager instead of a bare picker
 * (same outputs, plus existing-binding management).
 *
 * **PANEL mode** (sneak+scroll to switch): the tool only does the Screen
 * two-corner panel resize; no linking.
 */
class ChannelLinkToolItem(props: Properties) : Item(props) {

    /**
     * Runs *before* Block.use in the interaction order, so the editor screen
     * never opens while this tool is held. Returns SUCCESS to stop both
     * Block.use and Item.useOn from firing.
     */
    override fun onItemUseFirst(stack: ItemStack, ctx: UseOnContext): InteractionResult =
        handle(stack, ctx)

    override fun useOn(ctx: UseOnContext): InteractionResult = handle(ctx.itemInHand, ctx)

    private fun handle(stack: ItemStack, ctx: UseOnContext): InteractionResult {
        val level = ctx.level
        val player = ctx.player ?: return InteractionResult.PASS
        val pos = ctx.clickedPos

        // ── PANEL mode: ONLY screen-panel corner picking ──────────────────
        if (readMode(stack) == Mode.PANEL) {
            if (level.getBlockEntity(pos) is dev.nitka.nodewire.block.ScreenBlockEntity) {
                if (!level.isClientSide) handleScreenResize(player, level, pos)
            } else if (!level.isClientSide) {
                player.displayClientMessage(
                    Component.literal("Panel mode: click two screen corners (sneak+scroll to switch mode)")
                        .withStyle(ChatFormatting.YELLOW),
                    true,
                )
            }
            return InteractionResult.sidedSuccess(level.isClientSide)
        }

        // ── LINK mode: all UX runs on the client; the server is contacted
        // once, at bind commit. Stack NBT is just memory between two clicks.
        if (level.isClientSide) {
            if (player.isShiftKeyDown) pickSource(stack, ctx) else pickTarget(stack, ctx)
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    // ── source side (sneak + RMB) ─────────────────────────────────────────

    private fun pickSource(stack: ItemStack, ctx: UseOnContext) {
        val level = ctx.level
        val pos = ctx.clickedPos

        // Logic block keeps its richer UI: the Link Manager lists this
        // block's channel outputs (the same pins pinOutputs reports) plus
        // existing bindings with remove buttons.
        val logicBe = level.getBlockEntity(pos) as? LogicBlockEntity
        if (logicBe != null) {
            Minecraft.getInstance().setScreen(
                dev.nitka.nodewire.client.screen.BindingsManagerScreen(logicBe) { picked ->
                    val type = logicBe.pinOutputs(LinkContext(level, pos, level.getBlockState(pos)))
                        .firstOrNull { it.id == picked }?.type ?: PinType.ANY
                    armSource(stack, level, pos, LinkPin(picked, type))
                },
            )
            return
        }

        val port = PinPorts.at(level, pos, ctx.clickedFace)
        val outs = port?.pinOutputs(LinkContext(level, pos, level.getBlockState(pos), ctx.clickedFace)).orEmpty()
        when {
            outs.isEmpty() -> actionBar("Nothing here exposes output pins", true)
            outs.size == 1 -> armSource(stack, level, pos, outs[0])
            else -> Minecraft.getInstance().setScreen(
                ChannelPickerScreen(
                    "Source pin",
                    outs.map { ChannelPickerScreen.Option(it.id, it.type, it.label) },
                ) { picked ->
                    outs.firstOrNull { it.id == picked }?.let { armSource(stack, level, pos, it) }
                },
            )
        }
    }

    private fun armSource(stack: ItemStack, level: net.minecraft.world.level.Level, pos: BlockPos, pin: LinkPin) {
        val endpointNbt = EndpointRef.CODEC
            .encodeStart(NbtOps.INSTANCE, EndpointRef.from(level, pos))
            .result().orElse(null) as? CompoundTag
        if (endpointNbt == null) {
            actionBar("Failed to encode source endpoint", true)
            return
        }
        val tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
        val src = CompoundTag()
        src.put(SRC_ENDPOINT, endpointNbt)
        src.putString(SRC_PIN, pin.id)
        src.putString(SRC_TYPE, pin.type.name)
        src.putString(SRC_LABEL, pin.label)
        tag.put(NBT_LINK_SOURCE, src)
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        actionBar(
            "Source: ${pin.label} [${pin.type.name.lowercase()}] @ (${pos.x},${pos.y},${pos.z}) — right-click a target",
            false,
        )
    }

    // ── target side (plain RMB) ───────────────────────────────────────────

    private fun pickTarget(stack: ItemStack, ctx: UseOnContext) {
        val level = ctx.level
        val pos = ctx.clickedPos
        val tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
        val src = tag.getCompound(NBT_LINK_SOURCE)
        if (!tag.contains(NBT_LINK_SOURCE) || src.isEmpty) {
            actionBar("Arm a source first: sneak + right-click a block with output pins", true)
            return
        }
        val source = EndpointRef.CODEC
            .parse(NbtOps.INSTANCE, src.getCompound(SRC_ENDPOINT))
            .result().orElse(null)
        if (source == null) {
            actionBar("Stored source is invalid — re-arm it", true)
            return
        }
        val sourcePin = src.getString(SRC_PIN)
        val srcType = PinType.fromName(src.getString(SRC_TYPE))
        val srcLabel = src.getString(SRC_LABEL).ifEmpty { sourcePin }
        if (source.payload.blockPos == pos) {
            actionBar("Source and target must differ", true)
            return
        }

        val port = PinPorts.at(level, pos, ctx.clickedFace)
        val ins = port?.pinInputs(LinkContext(level, pos, level.getBlockState(pos), ctx.clickedFace))
            .orEmpty()
            .filter { PinValueConversion.canConvert(srcType, it.type) }

        fun commit(pin: LinkPin) {
            PacketDistributor.sendToServer(
                BindPinPacket(source, sourcePin, EndpointRef.from(level, pos), pin.id),
            )
            val cleared = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
            cleared.remove(NBT_LINK_SOURCE)
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(cleared))
            actionBar("Linking $srcLabel → ${pin.label} @ (${pos.x},${pos.y},${pos.z})…", false)
        }

        when {
            ins.isEmpty() ->
                actionBar("Nothing here accepts a ${srcType.name.lowercase()} pin", true)
            ins.size == 1 -> commit(ins[0])
            else -> Minecraft.getInstance().setScreen(
                ChannelPickerScreen(
                    "Target pin (${srcType.name.lowercase()})",
                    ins.map { ChannelPickerScreen.Option(it.id, it.type, it.label) },
                ) { picked ->
                    ins.firstOrNull { it.id == picked }?.let(::commit)
                },
            )
        }
    }

    // ── PANEL mode: two-corner screen resize (unchanged flow) ─────────────

    /**
     * SERVER side of the Screen panel resize. Validates the rect (coplanar,
     * ≤ MAX×MAX, every cell a Screen with the same FACING), dissolves any
     * overlapping older panels, marks the non-anchor cells covered and sets
     * the anchor span. All feedback goes to the player's action bar.
     */
    private fun handleScreenResize(
        player: net.minecraft.world.entity.player.Player,
        level: net.minecraft.world.level.Level,
        pos: BlockPos,
    ) {
        fun msg(s: String, warn: Boolean = false) {
            val c = Component.literal(s).withStyle(if (warn) ChatFormatting.YELLOW else ChatFormatting.AQUA)
            player.displayClientMessage(c, true)
        }

        val now = level.gameTime
        val pending = pendingScreenCorner[player.uuid]
            ?.takeIf { now - it.second <= SCREEN_RESIZE_TIMEOUT_TICKS }
            ?.first
        if (pending == null) {
            pendingScreenCorner[player.uuid] = pos to now
            msg("Corner 1: (${pos.x},${pos.y},${pos.z}) — sneak+RMB the opposite corner (same block = reset to 1×1)")
            return
        }
        pendingScreenCorner.remove(player.uuid)

        val firstBe = level.getBlockEntity(pending) as? dev.nitka.nodewire.block.ScreenBlockEntity
        if (firstBe == null) {
            msg("First corner is gone — start over", true)
            return
        }
        val facing = level.getBlockState(pending).getValue(dev.nitka.nodewire.block.ScreenBlock.FACING)

        if (pos == pending) {
            firstBe.releaseSpan(level)
            firstBe.setSpan(1, 1)
            msg("Screen reset to 1×1")
            return
        }

        val rect = dev.nitka.nodewire.block.ScreenSpan.rect(facing, pending, pos)
        if (rect == null) {
            msg("Corners are not coplanar (or panel exceeds ${dev.nitka.nodewire.block.ScreenSpan.MAX}×${dev.nitka.nodewire.block.ScreenSpan.MAX})", true)
            return
        }
        val cells = dev.nitka.nodewire.block.ScreenSpan.cells(facing, rect)
        for (cell in cells) {
            val st = level.getBlockState(cell)
            if (st.block !is dev.nitka.nodewire.block.ScreenBlock ||
                st.getValue(dev.nitka.nodewire.block.ScreenBlock.FACING) != facing
            ) {
                msg("(${cell.x},${cell.y},${cell.z}) is not a screen with the same facing", true)
                return
            }
        }

        // Dissolve panels overlapping the new rect (their anchors reset to 1×1
        // and free their covered cells) so no cell stays claimed twice.
        val oldAnchors = HashSet<BlockPos>()
        for (cell in cells) {
            val be = level.getBlockEntity(cell) as? dev.nitka.nodewire.block.ScreenBlockEntity ?: continue
            be.coveredBy()?.let { oldAnchors.add(it) }
            if (be.coveredBy() == null && (be.spanCols() > 1 || be.spanRows() > 1)) oldAnchors.add(cell)
        }
        for (a in oldAnchors) {
            (level.getBlockEntity(a) as? dev.nitka.nodewire.block.ScreenBlockEntity)?.let {
                it.releaseSpan(level)
                it.setSpan(1, 1)
            }
        }

        val anchorBe = level.getBlockEntity(rect.anchor) as? dev.nitka.nodewire.block.ScreenBlockEntity ?: return
        for (cell in cells) {
            if (cell == rect.anchor) continue
            (level.getBlockEntity(cell) as? dev.nitka.nodewire.block.ScreenBlockEntity)?.setCoveredBy(rect.anchor)
        }
        anchorBe.setSpan(rect.cols, rect.rows)
        // One-screen semantics: adopt any video already bound to a cell onto
        // the new anchor.
        anchorBe.consolidatePanel(level, cells)
        msg("Screen ${rect.cols}×${rect.rows} ✓ (bind video to ANY block of the panel)")
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
        /** Armed source: `{endpoint, pin, type, label}` — client-side memory
         *  between the two clicks, never round-trips. */
        private const val NBT_LINK_SOURCE = "link_source"
        private const val SRC_ENDPOINT = "endpoint"
        private const val SRC_PIN = "pin"
        private const val SRC_TYPE = "type"
        private const val SRC_LABEL = "label"

        private const val NBT_TOOL_MODE = "toolMode"

        /** Pending first corner of the Screen-resize flow, per player (server
         *  side, transient): player → (corner, gameTime of the click). */
        private val pendingScreenCorner = HashMap<java.util.UUID, Pair<BlockPos, Long>>()

        /** First corner expires after 30 s without the second click. */
        private const val SCREEN_RESIZE_TIMEOUT_TICKS = 600L

        /** Tool modes, cycled with sneak+scroll. */
        enum class Mode(val displayName: String) {
            LINK("Link pins"),
            PANEL("Screen panels"),
        }

        /** The stack's current mode ([Mode.LINK] when unset/unknown). */
        fun readMode(stack: ItemStack): Mode {
            val tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
            return runCatching { Mode.valueOf(tag.getString(NBT_TOOL_MODE)) }.getOrDefault(Mode.LINK)
        }

        /**
         * SERVER (from [dev.nitka.nodewire.net.SetLinkToolModePacket]): step the
         * mode by [direction] (+1/-1), persist it in the stack NBT and confirm
         * on the action bar.
         */
        fun cycleMode(stack: ItemStack, player: net.minecraft.world.entity.player.Player, direction: Int) {
            val modes = Mode.entries
            val cur = readMode(stack)
            val next = modes[Math.floorMod(cur.ordinal + (if (direction >= 0) 1 else -1), modes.size)]
            val tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
            tag.putString(NBT_TOOL_MODE, next.name)
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
            player.displayClientMessage(
                Component.literal("Mode: ${next.displayName}").withStyle(ChatFormatting.AQUA),
                true,
            )
        }
    }

    override fun appendHoverText(
        stack: ItemStack,
        context: Item.TooltipContext,
        tooltip: MutableList<Component>,
        flag: net.minecraft.world.item.TooltipFlag,
    ) {
        tooltip.add(
            Component.literal("Mode: ${readMode(stack).displayName}").withStyle(ChatFormatting.AQUA),
        )
        tooltip.add(
            Component.literal("Sneak+RMB: pick source pin · RMB: pick target pin")
                .withStyle(ChatFormatting.DARK_GRAY),
        )
        tooltip.add(
            Component.literal("Sneak+Scroll to switch mode").withStyle(ChatFormatting.DARK_GRAY),
        )
        super.appendHoverText(stack, context, tooltip, flag)
    }
}
