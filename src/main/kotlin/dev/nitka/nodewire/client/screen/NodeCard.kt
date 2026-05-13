package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeTypeRegistry
import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.ui.canvas.LocalCanvasState
import dev.nitka.nodewire.ui.modifier.input.onPositioned
import dev.nitka.nodewire.ui.components.Surface
import dev.nitka.nodewire.ui.components.SurfaceStyle
import dev.nitka.nodewire.ui.components.Text
import dev.nitka.nodewire.ui.core.Modifier
import dev.nitka.nodewire.ui.input.PointerEvent
import dev.nitka.nodewire.ui.layout.Alignment
import dev.nitka.nodewire.ui.layout.Arrangement
import dev.nitka.nodewire.ui.layout.Box
import dev.nitka.nodewire.ui.layout.Column
import dev.nitka.nodewire.ui.layout.PaddingValues
import dev.nitka.nodewire.ui.layout.Row
import dev.nitka.nodewire.ui.modifier.input.pointerInput
import dev.nitka.nodewire.ui.modifier.layout.absolutePosition
import dev.nitka.nodewire.ui.modifier.layout.fillMaxWidth
import dev.nitka.nodewire.ui.modifier.layout.offset
import dev.nitka.nodewire.ui.modifier.layout.padding
import dev.nitka.nodewire.ui.modifier.layout.size
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.modifier.style.border
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.theme.NwTheme
import net.minecraft.client.gui.screens.Screen

/**
 * Visual representation of one [Node] on the canvas. Layout:
 *
 *   ╔═══════════════════════════╗  ← title bar (accent bg, type display name)
 *   ║         Title             ║
 *   ╠═══════════════════════════╣
 *   ║                           ║
 *   ║  [config widgets]         ║  ← only when NodeType.configContent != null
 *   ║                           ║
 *   ●  A  bool       int  A  ●  ← pin row: handle straddles card border
 *   ●  B  bool       int  B  ●
 *   ╚═══════════════════════════╝
 *
 * Handles are shifted half-outside the card (negative offset for inputs,
 * positive for outputs) so the connection point sits visually centered
 * on the card edge — matches what node editors in UE5/Blender do.
 *
 * Each pin row shows the pin name plus a small muted type label so users
 * can tell BOOL from INT without memorising the color palette.
 *
 * Positioning: the card uses [Node.pos] with `.absolutePosition` so it
 * lives in world-space inside a `NodeCanvas`.
 */
@Composable
fun NodeCard(
    node: Node,
    modifier: Modifier = Modifier,
) {
    // Local state tracks the live position during a drag so the card moves
    // every frame without going through Compose's data-class equality
    // (Node is a data class but its `pos` is `var` — direct mutation
    // wouldn't trigger recomposition). We mirror the value back into
    // `node.pos` so the underlying graph stays in sync for save/load.
    var pos by remember(node) { mutableStateOf(node.pos) }
    val canvas = LocalCanvasState.current

    Surface(
        modifier = modifier
            .absolutePosition(pos.x.toInt(), pos.y.toInt())
            .width(CARD_WIDTH),
        style = cardStyle(),
    ) {
        Column {
            TitleBar(
                node = node,
                onDragDelta = { dx, dy ->
                    val zoom = canvas?.zoom ?: 1f
                    val updated = CanvasPos(pos.x + dx / zoom, pos.y + dy / zoom)
                    pos = updated
                    node.pos = updated
                },
            )
            ConfigSection(node)
            CardBody(node.id, node)
        }
    }
}

@Composable
private fun cardStyle(): SurfaceStyle = SurfaceStyle(
    color = NwTheme.colors.surface,
    shape = NwTheme.shapes.medium,
    // No border — pin handles straddle the card edge and a stroke under
    // them creates the ugly "pin overlapping a line" artefact. Surface
    // colour gives enough separation against the canvas grid.
    border = null,
    padding = PaddingValues.Zero,
)

@Composable
private fun TitleBar(node: Node, onDragDelta: (Float, Float) -> Unit) {
    val editor = LocalEditorState.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(NwTheme.colors.accent)
            .padding(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space2)
            .pointerInput { ev, _, _ ->
                when (ev) {
                    is PointerEvent.Press -> when (ev.button) {
                        LEFT_BUTTON -> true
                        // Right-click the title to delete this node (and
                        // every edge that referenced it).
                        RIGHT_BUTTON -> {
                            editor?.removeNode(node.id)
                            true
                        }
                        else -> false
                    }
                    is PointerEvent.Drag -> {
                        if (ev.button == LEFT_BUTTON) {
                            onDragDelta(ev.deltaX, ev.deltaY)
                            true
                        } else false
                    }
                    is PointerEvent.Release -> ev.button == LEFT_BUTTON
                    else -> false
                }
            },
    ) {
        Text(
            displayTitleOf(node),
            style = NwTheme.typography.caption.copy(color = NwTheme.colors.onAccent),
        )
    }
}

private const val LEFT_BUTTON = 0
private const val RIGHT_BUTTON = 1

@Composable
private fun ConfigSection(node: Node) {
    val type = NodeTypeRegistry.get(node.typeKey)
    val content = type?.configContent ?: return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NwTheme.dimens.space6, vertical = NwTheme.dimens.space4),
    ) {
        content(node)
    }
}

@Composable
private fun CardBody(nodeId: java.util.UUID, node: Node) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = NwTheme.dimens.space4),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2),
            horizontalAlignment = Alignment.Start,
        ) {
            for (pin in node.inputs) InputPinRow(nodeId, pin)
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2),
            horizontalAlignment = Alignment.End,
        ) {
            for (pin in node.outputs) OutputPinRow(nodeId, pin)
        }
    }
}

@Composable
private fun InputPinRow(nodeId: java.util.UUID, pin: Pin) {
    Row(
        verticalAlignment = Alignment.Center,
        horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2),
    ) {
        // Negative x offset moves the handle half its width outside the card,
        // so its centre lines up exactly with the card's left border.
        PinHandle(
            nodeId = nodeId,
            pin = pin,
            side = PinSide.Input,
            modifier = Modifier.offset(-PIN_HANDLE_HALF, 0),
        )
        Text(pin.name, style = NwTheme.typography.caption)
        TypeLabel(pin.type)
    }
}

@Composable
private fun OutputPinRow(nodeId: java.util.UUID, pin: Pin) {
    Row(
        verticalAlignment = Alignment.Center,
        horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2),
    ) {
        TypeLabel(pin.type)
        Text(pin.name, style = NwTheme.typography.caption)
        PinHandle(
            nodeId = nodeId,
            pin = pin,
            side = PinSide.Output,
            modifier = Modifier.offset(PIN_HANDLE_HALF, 0),
        )
    }
}

@Composable
private fun PinHandle(
    nodeId: java.util.UUID,
    pin: Pin,
    side: PinSide,
    modifier: Modifier = Modifier,
) {
    val editor = LocalEditorState.current
    val canvas = LocalCanvasState.current
    val key = PinKey(nodeId, pin.id, side)
    Box(
        modifier = modifier
            .size(PIN_HANDLE_SIZE)
            .background(pinColor(pin.type), NwTheme.shapes.medium)
            .border(BorderStroke(1, NwTheme.colors.borderStrong), NwTheme.shapes.medium)
            // onPositioned fires every layout pass — coords are in
            // world space inside a NodeCanvas (postLayoutWalk sums
            // `layoutX/layoutY` which Yoga keeps pan/zoom-free).
            .onPositioned { coords ->
                editor?.pinPositions?.set(key, coords.centerX.toFloat(), coords.centerY.toFloat())
            }
            .pointerInput { ev, _, _ ->
                if (editor == null) return@pointerInput false
                when (ev) {
                    is PointerEvent.Press -> when (ev.button) {
                        LEFT_BUTTON -> {
                            // In sticky chain mode, clicking the opposite-
                            // side pin commits without needing a release.
                            if (editor.wireDragSource != null
                                && editor.wireDragSticky
                                && editor.wireDragSource!!.side != side
                            ) {
                                editor.commitWireTo(key)
                                true
                            } else {
                                // Start a new drag. Shift on output = sticky
                                // chain; inputs are always non-sticky (an
                                // input can only have one source so chaining
                                // doesn't apply).
                                editor.beginWireDrag(key, sticky = Screen.hasShiftDown())
                            }
                        }
                        RIGHT_BUTTON -> {
                            // Disconnect everything touching this pin.
                            editor.disconnectPin(key)
                            true
                        }
                        else -> false
                    }
                    is PointerEvent.Drag -> {
                        // Route drag deltas to the source pin so the
                        // press-and-drag wire follows the cursor.
                        if (editor.wireDragSource == key) {
                            val zoom = canvas?.zoom ?: 1f
                            editor.updateWireDrag(ev.deltaX / zoom, ev.deltaY / zoom)
                            true
                        } else false
                    }
                    is PointerEvent.Release -> {
                        if (editor.wireDragSource == key) {
                            editor.finishWireDragOnRelease()
                            true
                        } else false
                    }
                    else -> false
                }
            },
    )
}

@Composable
private fun TypeLabel(type: PinType) {
    Text(
        type.name.lowercase(),
        style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceDisabled),
    )
}

@Composable
private fun pinColor(type: PinType): Color = when (type) {
    PinType.BOOL -> NwTheme.colors.pinBool
    PinType.INT -> NwTheme.colors.pinInt
    PinType.FLOAT -> NwTheme.colors.pinFloat
    PinType.STRING -> NwTheme.colors.pinString
    PinType.VEC2 -> NwTheme.colors.pinVec2
    PinType.VEC3 -> NwTheme.colors.pinVec3
    PinType.QUAT -> NwTheme.colors.pinQuat
}

/**
 * Display title: look up [NodeType.displayName] via the registry. If the
 * type isn't registered (e.g. forward-compat load of an unknown type),
 * fall back to a humanised registry id.
 */
private fun displayTitleOf(node: Node): String {
    NodeTypeRegistry.get(node.typeKey)?.let { return it.displayName }
    val segment = node.typeKey.path.substringAfterLast('/')
    return segment.split('_').joinToString(" ") { part ->
        part.replaceFirstChar { it.titlecase() }
    }
}

private const val CARD_WIDTH = 130
private const val PIN_HANDLE_SIZE = 8
private const val PIN_HANDLE_HALF = PIN_HANDLE_SIZE / 2
