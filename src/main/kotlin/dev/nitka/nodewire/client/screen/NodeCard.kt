package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.NodeCategory
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeTypeRegistry
import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.ui.layout.Layout
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
import dev.nitka.nodewire.ui.modifier.layout.weight
import dev.nitka.nodewire.ui.modifier.layout.width
import dev.nitka.nodewire.ui.modifier.style.background
import dev.nitka.nodewire.ui.modifier.style.border
import dev.nitka.nodewire.ui.render.BorderStroke
import dev.nitka.nodewire.ui.render.Color
import dev.nitka.nodewire.ui.render.TextAlign
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
    val canvas = LocalCanvasState.current
    val editor = LocalEditorState.current
    // Read graphVersion so any mutation that bumps it (pin-type rebuilds,
    // group drags via moveSelected, etc.) triggers a recomposition here.
    // node.pos / inputs / outputs are plain `var`s — Compose can't observe
    // them on its own.
    editor?.graphVersion
    val pos = node.pos
    val selected = editor?.isSelected(node.id) == true

    Surface(
        modifier = modifier
            .absolutePosition(pos.x.toInt(), pos.y.toInt())
            .width(CARD_WIDTH)
            // Card-wide right-click opens the node context menu. Pin handles
            // are deeper in the tree so their own RMB-disconnect still wins
            // when the click lands on a pin; only "empty card area" reaches
            // this handler.
            .pointerInput { ev, x, y ->
                if (ev is PointerEvent.Press && ev.button == RIGHT_BUTTON) {
                    if (editor != null && canvas != null) {
                        val worldX = pos.x + x
                        val worldY = pos.y + y
                        val screenX = ((worldX + canvas.panX) * canvas.zoom).toInt()
                        val screenY = ((worldY + canvas.panY) * canvas.zoom).toInt()
                        editor.openNodeMenu(screenX, screenY, node.id)
                    }
                    true
                } else false
            },
        style = cardStyle(selected),
    ) {
        Column {
            TitleBar(
                node = node,
                onPress = {
                    if (editor == null) return@TitleBar
                    val shift = net.minecraft.client.gui.screens.Screen.hasShiftDown()
                    when {
                        shift -> editor.toggleSelection(node.id)
                        // Single click without shift on a non-selected node
                        // replaces the selection. If already selected, leave
                        // the group intact so the subsequent drag moves all.
                        !editor.isSelected(node.id) -> editor.selectOnly(node.id)
                    }
                },
                onDragDelta = { dx, dy ->
                    val zoom = canvas?.zoom ?: 1f
                    val dxWorld = dx / zoom
                    val dyWorld = dy / zoom
                    // Group drag: any drag on a selected card moves every
                    // selected card by the same world delta. Else fall back
                    // to single-node move; bump graphVersion so the card
                    // recomposes with the new pos (we no longer cache pos
                    // in a local mutableState).
                    if (editor != null && editor.isSelected(node.id) && editor.selectedNodes.size > 1) {
                        editor.moveSelected(dxWorld, dyWorld)
                    } else {
                        node.pos = CanvasPos(node.pos.x + dxWorld, node.pos.y + dyWorld)
                        editor?.bumpGraphVersion()
                    }
                },
            )
            ConfigSection(node)
            CardBody(node.id, node)
        }
    }
}

@Composable
private fun cardStyle(selected: Boolean): SurfaceStyle = SurfaceStyle(
    color = NwTheme.colors.surface,
    shape = NwTheme.shapes.medium,
    // Normally no border (pin handles straddling a stroke look ugly), but
    // a selected node needs a clear affordance. Accept the artefact when
    // selected since the user is actively manipulating these nodes.
    border = if (selected) dev.nitka.nodewire.ui.render.BorderStroke(2, NwTheme.colors.accent) else null,
    padding = PaddingValues.Zero,
)

@Composable
private fun TitleBar(
    node: Node,
    onPress: () -> Unit = {},
    onDragDelta: (Float, Float) -> Unit,
) {
    val category = NodeTypeRegistry.get(node.typeKey)?.category ?: NodeCategory.LOGIC
    val baseColor = headerColorFor(category)
    val targetColor = NwTheme.colors.surface
    // Re-instantiate when either endpoint of the gradient changes (theme
    // swap or category mutation — the latter shouldn't happen but the
    // cost is one allocation per change so we're not picky).
    val headerRenderer = remember(baseColor.argb, targetColor.argb) {
        PixelDotHeaderRenderer(baseColor, targetColor)
    }
    Layout(
        renderer = headerRenderer,
        modifier = Modifier
            .fillMaxWidth()
            // Tight padding — title is purely a drag handle / label strip,
            // matches Blender's narrow header.
            .padding(horizontal = NwTheme.dimens.space4, vertical = 1)
            // LMB-only handler — RMB falls through to the card-level handler
            // (which opens the context menu). LMB starts drag-to-move.
            .pointerInput { ev, _, _ ->
                when (ev) {
                    is PointerEvent.Press -> {
                        if (ev.button == LEFT_BUTTON) {
                            onPress()
                            true
                        } else false
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
            .padding(horizontal = NwTheme.dimens.space4, vertical = NwTheme.dimens.space2),
    ) {
        content(node)
    }
}

@Composable
private fun CardBody(nodeId: java.util.UUID, node: Node) {
    // Each column gets half the body width. Inputs left-align inside
    // their column (cross-axis = Alignment.Start); outputs right-align
    // (Alignment.End). Combined with content-sized rows, this anchors
    // input rows to the card's left edge and output rows to its right,
    // while keeping a normal LTR gap-separated order within each row.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = NwTheme.dimens.space2),
        horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2),
            horizontalAlignment = Alignment.Start,
        ) {
            for (pin in node.inputs) InputPinRow(nodeId, pin)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2),
            horizontalAlignment = Alignment.End,
        ) {
            for (pin in node.outputs) OutputPinRow(nodeId, pin)
        }
    }
}

@Composable
private fun InputPinRow(nodeId: java.util.UUID, pin: Pin) {
    val chip = pinChipText(pin, valueFlowingInto(nodeId, pin.id))
    // Plain LTR: [●] Name chip. Content-sized row left-aligned by the
    // surrounding column.
    Row(
        verticalAlignment = Alignment.Center,
        horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2),
    ) {
        PinHandle(
            nodeId = nodeId,
            pin = pin,
            side = PinSide.Input,
            modifier = Modifier.offset(-PIN_HANDLE_HALF, 0),
        )
        Text(pin.name, style = NwTheme.typography.caption)
        ChipLabel(chip)
    }
}

@Composable
private fun OutputPinRow(nodeId: java.util.UUID, pin: Pin) {
    val evalResult = LocalEvalResult.current
    val chip = pinChipText(pin, evalResult?.valueAt(nodeId, pin.id))
    // Mirror layout: chip, Name, [●]. Content-sized row right-aligned by
    // the surrounding column (cross-axis Alignment.End), so the handle
    // sits at the card's right edge and chip+name pack in LTR order
    // immediately to its left.
    Row(
        verticalAlignment = Alignment.Center,
        horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2),
    ) {
        ChipLabel(chip)
        Text(pin.name, style = NwTheme.typography.caption)
        PinHandle(
            nodeId = nodeId,
            pin = pin,
            side = PinSide.Output,
            modifier = Modifier.offset(PIN_HANDLE_HALF, 0),
        )
    }
}

/**
 * For an input pin: looks up the edge that feeds it and returns the
 * upstream output's currently-evaluated value. Null if the pin is
 * unconnected or no eval result is available yet.
 */
@Composable
private fun valueFlowingInto(nodeId: java.util.UUID, pinId: String): PinValue? {
    val editor = LocalEditorState.current ?: return null
    val result = LocalEvalResult.current ?: return null
    val edge = editor.graph.edges.firstOrNull { it.to.node == nodeId && it.to.pin == pinId }
        ?: return null
    return result.valueAt(edge.from.node, edge.from.pin)
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
private fun ChipLabel(text: String) {
    Text(
        text,
        style = NwTheme.typography.caption.copy(color = NwTheme.colors.onSurfaceMuted),
    )
}

/**
 * The "value or type" chip that sits on a pin row. When [value] is null
 * (input is unconnected, or output hasn't been evaluated yet) we show the
 * pin's static type name. Otherwise the formatted value with a short
 * type-suffix tag so the reader can tell `15i` (int) from `15r` (redstone)
 * at a glance.
 */
private fun pinChipText(pin: Pin, value: PinValue?): String = when (value) {
    null -> pin.type.name.lowercase()
    is PinValue.Bool -> if (value.value) "true" else "false"
    is PinValue.Int -> "${value.value}i"
    is PinValue.Float -> "%.2ff".format(value.value)
    is PinValue.Redstone -> "${value.value}r"
    is PinValue.Str -> {
        val s = value.value
        "\"${s.take(10)}${if (s.length > 10) "…" else ""}\""
    }
    is PinValue.Vec2 -> "(%.1f; %.1f)".format(value.x, value.y)
    is PinValue.Vec3 -> "(%.1f; %.1f; %.1f)".format(value.x, value.y, value.z)
    is PinValue.Quat -> "q(%.1f; %.1f; %.1f; %.1f)".format(value.x, value.y, value.z, value.w)
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
