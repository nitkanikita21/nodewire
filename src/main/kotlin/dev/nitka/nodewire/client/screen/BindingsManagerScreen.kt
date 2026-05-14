package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.block.ChannelBinding
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.block.SideBinding
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.net.NodewireNetwork
import dev.nitka.nodewire.net.RemoveBindingPacket
import dev.nitka.nodewire.ui.components.Surface
import dev.nitka.nodewire.ui.components.SurfaceStyle
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.core.NwComposeScreen
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.PaddingValues
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.input.onHover
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.layout.fillMaxSize
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.size
import dev.nitka.nodewire.ui.modifier.layout.weight
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.theme.NwTheme
import dev.nitka.nodewire.ui.theme.NwThemeProvider
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

/**
 * Two-section modal opened by Shift + right-click on a logic block with
 * the Channel Link Tool. Replaces the bare "pick channel" picker so the
 * user can also see what's already wired out of this block and remove
 * any link they don't want.
 *
 * Top section — "Source channel": pick one of this BE's named channel
 * outputs to arm the tool with. Same behaviour as the old source picker:
 * selection writes the source into the stack NBT and closes.
 *
 * Bottom section — "Existing bindings": every [ChannelBinding] and
 * [SideBinding] going out of this BE. Each row shows the source channel,
 * an arrow, the target description, and a `×` button. Click removes the
 * binding via [RemoveBindingPacket]; the row vanishes once the server
 * round-trips the chunk update.
 */
class BindingsManagerScreen(
    private val sourceBe: LogicBlockEntity,
    private val onPickSource: (channelName: String) -> Unit,
) : NwComposeScreen(Component.literal("Link Manager")) {

    @Composable
    override fun Content() {
        NwThemeProvider {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput { ev, _, _ ->
                        if (ev is PointerEvent.Press) {
                            Minecraft.getInstance().setScreen(null)
                            true
                        } else false
                    },
            ) {
                Panel()
            }
        }
    }

    @Composable
    private fun Panel() {
        val mc = Minecraft.getInstance()
        val w = mc.window.guiScaledWidth
        val h = mc.window.guiScaledHeight
        Box(
            modifier = Modifier
                .padding(start = ((w - PANEL_WIDTH) / 2), top = ((h - 240).coerceAtLeast(20) / 2)),
        ) {
            Surface(
                modifier = Modifier
                    .width(PANEL_WIDTH)
                    .pointerInput { ev, _, _ -> ev is PointerEvent.Press },
                style = SurfaceStyle(
                    color = NwTheme.colors.surface,
                    shape = NwTheme.shapes.medium,
                    border = BorderStroke(1, NwTheme.colors.border),
                    padding = PaddingValues(NwTheme.dimens.space8),
                ),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space6)) {
                    PanelHeader(channelCount = 0, bindingCount = 0)
                    SourceList()
                    ExistingList()
                }
            }
        }
    }

    @Composable
    private fun PanelHeader(channelCount: Int, bindingCount: Int) {
        val pos = sourceBe.blockPos
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            Row(verticalAlignment = Alignment.Center) {
                Text("Link Manager", style = NwTheme.typography.subtitle)
                Box(modifier = Modifier.weight(1f))
                Text(
                    "$channelCount channels · $bindingCount bindings",
                    style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
                )
            }
            Text(
                "Block (${pos.toShortString()}) · click a channel to arm tool, ✕ to disconnect",
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
        }
    }

    @Composable
    private fun SourceList() {
        val outputs = sourceBe.graph.nodes.values
            .filter { it.typeKey.path == "channel_output" }
            .mapNotNull { node ->
                val name = node.config.getString("name")
                if (name.isEmpty()) null else name to PinType.fromName(node.config.getString("type"))
            }
        if (outputs.isEmpty()) {
            MutedLine("(no channel outputs on this block)")
            return
        }
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            for ((name, type) in outputs) {
                SourceRow(name, type) {
                    onPickSource(name)
                    Minecraft.getInstance().setScreen(null)
                }
            }
        }
    }

    @Composable
    private fun ExistingList() {
        // Refresh trigger — bump on remove so the recomposition re-reads
        // the snapshots after the server pushes the BE update.
        var version by remember { mutableStateOf(0) }
        val bindings = remember(version) { sourceBe.bindingsSnapshot() }
        val sideBindings = remember(version) { sourceBe.sideBindingsSnapshot() }

        if (bindings.isEmpty() && sideBindings.isEmpty()) {
            MutedLine("(no outgoing links yet)")
            return
        }
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            for (b in bindings) {
                ChannelBindingRow(b) {
                    NodewireNetwork.CHANNEL.sendToServer(
                        RemoveBindingPacket(
                            sourcePos = sourceBe.blockPos,
                            sourceChannelName = b.sourceChannelName,
                            targetPos = b.targetPos,
                            kind = RemoveBindingPacket.Kind.CHANNEL,
                            extra = b.targetChannelName,
                        ),
                    )
                    version++
                }
            }
            for (sb in sideBindings) {
                SideBindingRow(sb) {
                    NodewireNetwork.CHANNEL.sendToServer(
                        RemoveBindingPacket(
                            sourcePos = sourceBe.blockPos,
                            sourceChannelName = sb.sourceChannelName,
                            targetPos = sb.targetPos,
                            kind = RemoveBindingPacket.Kind.SIDE,
                            extra = sb.targetSide.name,
                        ),
                    )
                    version++
                }
            }
        }
    }

    private fun totalBindings(): Int =
        sourceBe.bindingsSnapshot().size + sourceBe.sideBindingsSnapshot().size
}

@Composable
private fun SourceRow(name: String, type: PinType, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    val bg = if (hovered) NwTheme.colors.surfaceHover else NwTheme.colors.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, NwTheme.shapes.medium)
            .padding(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space4)
            .onHover { hovered = it }
            .pointerInput { ev, _, _ ->
                if (ev is PointerEvent.Press) { onClick(); true } else false
            },
        verticalAlignment = Alignment.Center,
        horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space6),
    ) {
        Box(modifier = Modifier.size(6).background(pinColor(type), NwTheme.shapes.medium))
        // Name takes natural width; type label sits at the row's natural end.
        // weight()-on-Text doesn't work reliably with the Yoga-backed text
        // measure, so we lay out compactly and rely on PANEL_WIDTH for room.
        Text(name, style = NwTheme.typography.caption)
        Box(modifier = Modifier.weight(1f))
        Text(
            type.name.lowercase(),
            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
        )
    }
}

@Composable
private fun ChannelBindingRow(binding: ChannelBinding, onRemove: () -> Unit) {
    BindingRow(
        dotType = PinType.BOOL, // channel — neutral pin colour; type lives on the source node config
        sourceText = binding.sourceChannelName,
        targetText = "${binding.targetPos.toShortString()}  ${binding.targetChannelName}",
        kindLabel = "ch",
        onRemove = onRemove,
    )
}

@Composable
private fun SideBindingRow(binding: SideBinding, onRemove: () -> Unit) {
    BindingRow(
        dotType = PinType.REDSTONE,
        sourceText = binding.sourceChannelName,
        targetText = "${binding.targetPos.toShortString()}  ${binding.targetSide.name.lowercase()}",
        kindLabel = "side",
        onRemove = onRemove,
    )
}

/**
 * Two-line row: top = source channel name with a coloured dot, bottom =
 * the target description (block coords + channel-name or side). Layout is
 * Row { Column(weight 1) { ... }; kindLabel; RemoveButton } so the target
 * text wraps within the inner column instead of pushing the × off the row.
 */
@Composable
private fun BindingRow(
    dotType: PinType,
    sourceText: String,
    targetText: String,
    kindLabel: String,
    onRemove: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val bg = if (hovered) NwTheme.colors.surfaceHover else NwTheme.colors.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, NwTheme.shapes.medium)
            .padding(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space4)
            .onHover { hovered = it },
        verticalAlignment = Alignment.Center,
        horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space6),
    ) {
        Box(modifier = Modifier.size(6).background(pinColor(dotType), NwTheme.shapes.medium))
        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
            Text(sourceText, style = NwTheme.typography.caption)
            Text(
                "→ $targetText",
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
        }
        // Push the kind chip + delete button to the right edge of the row.
        Box(modifier = Modifier.weight(1f))
        Text(
            kindLabel,
            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
        )
        RemoveButton(onRemove)
    }
}

@Composable
private fun RemoveButton(onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    val bg = if (hovered) NwTheme.colors.danger else NwTheme.colors.surfacePressed
    Row(
        modifier = Modifier
            .size(18)
            .background(bg, NwTheme.shapes.medium)
            .onHover { hovered = it }
            .pointerInput { ev, _, _ ->
                if (ev is PointerEvent.Press) { onClick(); true } else false
            },
        verticalAlignment = Alignment.Center,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text("×", style = NwTheme.typography.caption)
    }
}

@Composable
private fun MutedLine(text: String) {
    Text(
        text,
        style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
    )
}

@Composable
private fun pinColor(type: PinType): Color = when (type) {
    PinType.BOOL -> NwTheme.colors.pinBool
    PinType.INT -> NwTheme.colors.pinInt
    PinType.FLOAT -> NwTheme.colors.pinFloat
    PinType.REDSTONE -> NwTheme.colors.pinRedstone
    PinType.STRING -> NwTheme.colors.pinString
    PinType.VEC2 -> NwTheme.colors.pinVec2
    PinType.VEC3 -> NwTheme.colors.pinVec3
    PinType.QUAT -> NwTheme.colors.pinQuat
}

/**
 * Single-glyph label for a [Direction] when rendered inside a side-binding
 * target row. UP/DOWN get unicode arrows; cardinal directions use single
 * letters because horizontal arrows on a 2D screen are ambiguous without a
 * world-axis legend.
 */
internal fun sideGlyph(face: net.minecraft.core.Direction): String = when (face) {
    net.minecraft.core.Direction.UP    -> "↑"
    net.minecraft.core.Direction.DOWN  -> "↓"
    net.minecraft.core.Direction.NORTH -> "N"
    net.minecraft.core.Direction.SOUTH -> "S"
    net.minecraft.core.Direction.WEST  -> "W"
    net.minecraft.core.Direction.EAST  -> "E"
}

private const val PANEL_WIDTH = 380
