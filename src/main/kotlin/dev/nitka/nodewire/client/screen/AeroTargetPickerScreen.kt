package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeId
import dev.nitka.nodewire.graph.PinType
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
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.theme.NwTheme
import dev.nitka.nodewire.ui.theme.NwThemeProvider
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

// ---------------------------------------------------------------------------
// File-private entry type — mirrors ChannelPickerScreen.Option pattern.
// ---------------------------------------------------------------------------

private data class AeroNodeEntry(val node: Node, val compatible: Boolean)

/**
 * Modal node picker for the target Logic Block. Shown by the Channel Link
 * Tool when the user selects a target block so they can choose which
 * `aeronautics_input` node to wire the source channel into.
 *
 * Lists all [Node]s in [target]'s graph whose [Node.typeKey] path is
 * `"aeronautics_input"`. Each row:
 *   - Compatible (first output type matches [sourcePinType]): clickable;
 *     click invokes [onPick] with the node's [NodeId] and closes.
 *   - Incompatible: displayed but grayed — the output pin type is shown
 *     so the user understands the mismatch.
 *
 * ESC or click outside the panel cancels.
 */
class AeroTargetPickerScreen(
    private val target: LogicBlockEntity,
    private val sourcePinType: PinType,
    private val onPick: (NodeId) -> Unit,
) : NwComposeScreen(Component.literal("Select aeronautics_input node")) {

    private val entries: List<AeroNodeEntry> = target.graph.nodes.values
        .filter { it.typeKey.path == "aeronautics_input" }
        .map { node ->
            val outType = node.outputs.firstOrNull()?.type
            AeroNodeEntry(node, compatible = outType == sourcePinType)
        }

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
                .padding(start = ((w - PANEL_WIDTH) / 2), top = ((h - 160) / 2)),
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
                Column(verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space4)) {
                    Text("Select input node", style = NwTheme.typography.subtitle)
                    if (entries.isEmpty()) {
                        Text(
                            "(no aeronautics_input nodes)",
                            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
                        )
                    }
                    for (entry in entries) {
                        TargetNodeRow(entry, sourcePinType) {
                            onPick(entry.node.id)
                            Minecraft.getInstance().setScreen(null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetNodeRow(entry: AeroNodeEntry, sourcePinType: PinType, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    val compatible = entry.compatible
    val bg = if (compatible && hovered) NwTheme.colors.surfaceHover else NwTheme.colors.surface
    val outType = entry.node.outputs.firstOrNull()?.type

    // Prefer the node's user label; fall back to typeKey.path.
    val label = entry.node.label?.takeIf { it.isNotBlank() } ?: "aeronautics_input"

    val rowModifier = if (compatible) {
        Modifier
            .fillMaxWidth()
            .background(bg, NwTheme.shapes.medium)
            .padding(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space2)
            .onHover { hovered = it }
            .pointerInput { ev, _, _ ->
                if (ev is PointerEvent.Press) { onClick(); true } else false
            }
    } else {
        Modifier
            .fillMaxWidth()
            .background(bg, NwTheme.shapes.medium)
            .padding(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space2)
    }

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.Center,
        horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space6),
    ) {
        val dotType = outType ?: sourcePinType
        Box(
            modifier = Modifier
                .size(6)
                .background(
                    if (compatible) targetPinColor(dotType) else NwTheme.colors.onSurfaceMuted,
                    NwTheme.shapes.medium,
                ),
        )
        Text(
            label,
            style = if (compatible) {
                NwTheme.typography.caption
            } else {
                NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted)
            },
        )
        if (outType != null && !compatible) {
            // Show the actual vs expected pin types so the user understands the mismatch.
            Text(
                "${outType.name.lowercase()} ≠ ${sourcePinType.name.lowercase()}",
                style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
            )
        }
    }
}

@Composable
private fun targetPinColor(type: PinType): Color = when (type) {
    PinType.BOOL -> NwTheme.colors.pinBool
    PinType.INT -> NwTheme.colors.pinInt
    PinType.FLOAT -> NwTheme.colors.pinFloat
    PinType.REDSTONE -> NwTheme.colors.pinRedstone
    PinType.STRING -> NwTheme.colors.pinString
    PinType.VEC2 -> NwTheme.colors.pinVec2
    PinType.VEC3 -> NwTheme.colors.pinVec3
    PinType.QUAT -> NwTheme.colors.pinQuat
}

private const val PANEL_WIDTH = 280
