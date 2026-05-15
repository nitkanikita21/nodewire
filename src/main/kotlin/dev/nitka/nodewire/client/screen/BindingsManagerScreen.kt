package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.net.NodewireNetwork
import dev.nitka.nodewire.net.RemoveBindingPacket
import dev.nitka.nodewire.net.SetSideBindingNamePacket
import dev.nitka.nodewire.ui.components.Button
import dev.nitka.nodewire.ui.components.ButtonDefaults
import dev.nitka.nodewire.ui.components.Surface
import dev.nitka.nodewire.ui.components.SurfaceStyle
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.components.TextInput
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraftforge.network.PacketDistributor

/**
 * Modal opened by Shift + right-click on a logic block with the Channel
 * Link Tool. One screen, one list: each `channel_output` node on this BE
 * becomes a group whose header doubles as the source-pick affordance.
 * Click a header → arm the tool with that channel + close. Click `×` on
 * an indented target row → send [RemoveBindingPacket] to drop that
 * specific binding; the screen stays open and recomposes on the next
 * BE update.
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

        // Bump on every successful remove so snapshots re-read after the
        // server pushes the BE update.
        var version by remember { mutableStateOf(0) }

        val outputs = remember(version) {
            sourceBe.graph.nodes.values
                .filter { it.typeKey.path == "channel_output" }
                .mapNotNull { node ->
                    val name = node.config.getString("name")
                    if (name.isEmpty()) null
                    else name to PinType.fromName(node.config.getString("type"))
                }
        }
        val bindings = remember(version) { sourceBe.bindingsSnapshot() }
        val sideBindings = remember(version) { sourceBe.sideBindingsSnapshot() }
        val totalBindings = bindings.size + sideBindings.size
        // Pre-group by source channel name once so the per-channel rendering
        // loop is O(1) lookup instead of O(N) filter per output.
        val bindingsByChannel = remember(version) { bindings.groupBy { it.sourceChannelName } }
        val sideBindingsByChannel = remember(version) { sideBindings.groupBy { it.sourceChannelName } }

        Box(
            modifier = Modifier
                .padding(start = ((w - PANEL_WIDTH) / 2), top = ((h - 260).coerceAtLeast(20) / 2)),
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
                    PanelHeader(channelCount = outputs.size, bindingCount = totalBindings)

                    if (outputs.isEmpty()) {
                        MutedLine("Add a Channel Output node to this block first.")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4)) {
                            for ((name, type) in outputs) {
                                val myChannelBindings = bindingsByChannel[name].orEmpty()
                                val mySideBindings = sideBindingsByChannel[name].orEmpty()
                                val count = myChannelBindings.size + mySideBindings.size

                                Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2)) {
                                    GroupHeader(name = name, type = type, bindingCount = count) {
                                        onPickSource(name)
                                        Minecraft.getInstance().setScreen(null)
                                    }
                                    for (b in myChannelBindings) {
                                        TargetRow(
                                            description = "(${b.targetPos.toShortString()}) ${b.targetChannelName}",
                                            kindChip = "ch",
                                            targetPos = b.targetPos,
                                            onRemove = {
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
                                            },
                                        )
                                    }
                                    for (sb in mySideBindings) {
                                        TargetRow(
                                            description = "(${sb.targetPos.toShortString()}) ${sideGlyph(sb.targetSide)}",
                                            kindChip = "side",
                                            targetPos = sb.targetPos,
                                            bindingName = sb.name,
                                            onRename = { newName ->
                                                NodewireNetwork.CHANNEL.send(
                                                    PacketDistributor.SERVER.noArg(),
                                                    SetSideBindingNamePacket(
                                                        sourcePos = sourceBe.blockPos,
                                                        sourceChannelName = sb.sourceChannelName,
                                                        targetPos = sb.targetPos,
                                                        targetSide = sb.targetSide,
                                                        name = newName,
                                                    ),
                                                )
                                                version++
                                            },
                                            onRemove = {
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
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
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
            val displayName = sourceBe.getBlockName().ifBlank { "(${pos.toShortString()})" }
            Text(
                "Block $displayName · click a channel to arm tool, ✕ to disconnect",
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
        }
    }

}

/**
 * One indented row under a [GroupHeader]. Always single-line. The `×` is a
 * real [Button] with the danger preset, so hover/pressed states match the
 * rest of the UI.
 */
@Composable
private fun TargetRow(
    description: String,
    kindChip: String,
    targetPos: net.minecraft.core.BlockPos,
    bindingName: String = "",
    onRename: ((String) -> Unit)? = null,
    onRemove: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val debouncer = remember { SideBindingNameDebouncer(scope) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = NwTheme.dimens.space16,
                end = NwTheme.dimens.space2,
                top = NwTheme.dimens.space2,
                bottom = NwTheme.dimens.space2,
            ),
        verticalAlignment = Alignment.Center,
        horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space6),
    ) {
        Text(
            "→",
            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
        )
        if (onRename != null) {
            TextInput(
                modifier = Modifier.width(BINDING_NAME_INPUT_WIDTH),
                value = bindingName,
                placeholder = description,
                onValueChange = { next ->
                    debouncer.schedule(next) { onRename(next) }
                },
            )
        }
        if (onRename == null || bindingName.isEmpty()) {
            Text(description, style = NwTheme.typography.caption)
        } else {
            Text(description, style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted))
        }
        Box(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .background(NwTheme.colors.surfacePressed, NwTheme.shapes.medium)
                .padding(horizontal = NwTheme.dimens.space4, vertical = NwTheme.dimens.space2),
        ) {
            Text(
                kindChip,
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
        }
        Button(
            onClick = {
                dev.nitka.nodewire.client.highlight.BlockHighlightRenderer.highlight(targetPos)
                postHighlightChatMessage(targetPos)
            },
            style = ButtonDefaults.outlined().copy(
                padding = PaddingValues(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space2),
            ),
        ) {
            Text("◎", style = NwTheme.typography.caption)
        }
        Button(
            onClick = onRemove,
            style = ButtonDefaults.danger().copy(
                padding = PaddingValues(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space2),
            ),
        ) {
            Text("×", style = NwTheme.typography.caption)
        }
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
 * Clickable channel-output header. Whole row is the click target — picks
 * this channel as source and closes the screen. Idle channels (no bindings)
 * use the surface background; wired channels use surfaceHover so they read
 * as "active". Hovering deepens to surfacePressed in both cases.
 */
@Composable
private fun GroupHeader(
    name: String,
    type: PinType,
    bindingCount: Int,
    onPick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val idle = bindingCount == 0
    val bg = when {
        hovered -> NwTheme.colors.surfacePressed
        idle    -> NwTheme.colors.surface
        else    -> NwTheme.colors.surfaceHover
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, NwTheme.shapes.medium)
            .padding(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space4)
            .onHover { hovered = it }
            .pointerInput { ev, _, _ ->
                if (ev is PointerEvent.Press) { onPick(); true } else false
            },
        verticalAlignment = Alignment.Center,
        horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space6),
    ) {
        Box(modifier = Modifier.size(7).background(pinColor(type), NwTheme.shapes.medium))
        Text(name, style = NwTheme.typography.caption)
        Text(
            type.name.lowercase(),
            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
        )
        Box(modifier = Modifier.weight(1f))
        Text(
            if (bindingCount == 0) "no bindings"
            else "$bindingCount binding${if (bindingCount == 1) "" else "s"}",
            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
        )
    }
}

/**
 * Single-glyph label for a [Direction] when rendered inside a side-binding
 * target row. UP/DOWN get unicode arrows; cardinal directions use single
 * letters because horizontal arrows on a 2D screen are ambiguous without a
 * world-axis legend.
 */
private fun postHighlightChatMessage(pos: net.minecraft.core.BlockPos) {
    val command = "/nodewire highlight ${pos.x} ${pos.y} ${pos.z}"
    val component = net.minecraft.network.chat.Component
        .literal("Highlight (${pos.x}, ${pos.y}, ${pos.z}) again")
        .withStyle { style ->
            style
                .withColor(net.minecraft.ChatFormatting.YELLOW)
                .withUnderlined(true)
                .withClickEvent(
                    net.minecraft.network.chat.ClickEvent(
                        net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                        command,
                    ),
                )
                .withHoverEvent(
                    net.minecraft.network.chat.HoverEvent(
                        net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                        net.minecraft.network.chat.Component.literal("Click to re-highlight"),
                    ),
                )
        }
    net.minecraft.client.Minecraft.getInstance().player?.displayClientMessage(component, false)
}

internal fun sideGlyph(face: net.minecraft.core.Direction): String = when (face) {
    net.minecraft.core.Direction.UP    -> "↑"
    net.minecraft.core.Direction.DOWN  -> "↓"
    net.minecraft.core.Direction.NORTH -> "N"
    net.minecraft.core.Direction.SOUTH -> "S"
    net.minecraft.core.Direction.WEST  -> "W"
    net.minecraft.core.Direction.EAST  -> "E"
}

private const val PANEL_WIDTH = 400
private const val BINDING_NAME_INPUT_WIDTH = 100

private class SideBindingNameDebouncer(private val scope: kotlinx.coroutines.CoroutineScope) {
    private var pending: Job? = null
    fun schedule(name: String, onFire: () -> Unit) {
        pending?.cancel()
        pending = scope.launch {
            delay(DEBOUNCE_MS)
            onFire()
        }
    }
    companion object {
        private const val DEBOUNCE_MS = 300L
    }
}
