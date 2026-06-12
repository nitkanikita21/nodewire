package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import dev.nitka.nodewire.graph.getPinDefault
import dev.nitka.nodewire.ui.layout.Layout
import dev.nitka.nodewire.ui.canvas.LocalCanvasState
import dev.nitka.nodewire.ui.modifier.input.onPositioned
import dev.nitka.nodewire.ui.modifier.input.onSizeChanged
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
import dev.nitka.nodewire.ui.modifier.layout.flex
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
    nodeId: dev.nitka.nodewire.graph.NodeId,
    modifier: Modifier = Modifier,
) {
    val canvas = LocalCanvasState.current
    val editor = LocalEditorState.current
    // Subscribe to the per-node flow so only this card recomposes when its
    // own Node changes. If the editor isn't present (shouldn't happen in
    // production paths but keeps the type system happy) we render nothing.
    val flow = editor?.nodeFlow(nodeId) ?: return
    val node by flow.collectAsState()
    val pos = node.pos
    val selected = editor.isSelected(nodeId)


    // Pin-heavy nodes (a fire-control script declares 12 inputs + 9 outputs)
    // can't fit the fixed card width — long names, inline editors and value
    // chips from both half-width columns collided in the middle. Width is
    // measured from the widest input row + widest output row instead;
    // CARD_WIDTH stays the floor so small nodes keep their compact look.
    val cardWidth = remember(node.typeKey, node.inputs, node.outputs) {
        desiredCardWidth(node)
    }

    Surface(
        modifier = modifier
            .absolutePosition(pos.x.toInt(), pos.y.toInt())
            .width(cardWidth)
            // Report measured size to the editor so rubber-band hit-tests
            // use the real card bounds, not a constant guess.
            .onSizeChanged { size ->
                editor?.setCardSize(nodeId, size.width, size.height)
                editor?.nodeBounds?.set(node.id, NodeBounds(size.width, size.height))
            }
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
                        editor.openNodeMenu(screenX, screenY, nodeId)
                    }
                    true
                } else false
            },
        style = cardStyle(selected),
    ) {
        Column {
            TitleBar(
                nodeId = nodeId,
                node = node,
                onPress = {
                    if (editor == null) return@TitleBar
                    val shift = net.minecraft.client.gui.screens.Screen.hasShiftDown()
                    when {
                        shift -> editor.toggleSelection(nodeId)
                        // Single click without shift on a non-selected node
                        // replaces the selection. If already selected, leave
                        // the group intact so the subsequent drag moves all.
                        !editor.isSelected(nodeId) -> editor.selectOnly(nodeId)
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
                    if (editor != null && editor.isSelected(nodeId)) {
                        editor.moveSelected(dxWorld, dyWorld)
                    } else {
                        editor?.updateNode(nodeId) {
                            it.copy(pos = CanvasPos(it.pos.x + dxWorld, it.pos.y + dyWorld))
                        }
                    }
                },
            )
            ConfigSection(node)
            CardBody(nodeId, node)
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
    nodeId: dev.nitka.nodewire.graph.NodeId,
    node: Node,
    onPress: () -> Unit = {},
    onDragDelta: (Float, Float) -> Unit,
) {
    val editor = LocalEditorState.current
    var lastPressMillis by remember { mutableStateOf(0L) }
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
                            val now = System.currentTimeMillis()
                            if (now - lastPressMillis < DOUBLE_CLICK_MS) {
                                editor?.renamingNode = nodeId
                                lastPressMillis = 0L
                            } else {
                                lastPressMillis = now
                                onPress()
                            }
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
        // While this node is being renamed, the transparent rename overlay
        // (NodeLabelOverlay) sits over this strip. Suppress our own title so
        // the two don't paint on top of each other (the overlay keeps the
        // header's accent fill behind it via the headerRenderer). Reading
        // editor.renamingNode here makes the title vanish the instant rename
        // opens and reappear when it clears.
        val renaming = editor?.renamingNode == nodeId
        // takeUnless { blank } so an empty-but-non-null label (e.g. legacy
        // graph loaded from before setNodeLabel sanitised) renders as just
        // the title instead of a hollow caption row.
        val label = node.label?.takeUnless { it.isBlank() }
        if (renaming) {
            // Nothing — overlay owns this strip while editing.
        } else if (label != null) {
            Column {
                Text(
                    label,
                    style = NwTheme.typography.caption.copy(color = NwTheme.colors.onAccent),
                )
                Text(
                    displayTitleOf(node),
                    style = NwTheme.typography.caption.copy(
                        color = NwTheme.colors.onAccent.copy(alpha = 0.6f),
                    ),
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.Center,
                horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2),
            ) {
                Text(
                    displayTitleOf(node),
                    style = NwTheme.typography.caption.copy(color = NwTheme.colors.onAccent),
                )
                ScriptStatusBadge(node)
            }
        }
    }
}

/**
 * Component G badge — a tiny compile-status glyph shown only on `script`
 * nodes, fed by the server-synced [dev.nitka.nodewire.script.ScriptDiagnostics]
 * token in the node config: `…` compiling / `✅` ok / `❌` error. Hidden when
 * the script is empty (no source) or the node isn't a script node. Pure
 * presentation — reads the last-synced value, never compiles on the client.
 */
@Composable
private fun ScriptStatusBadge(node: Node) {
    if (node.typeKey.path != "script") return
    val glyph = when (dev.nitka.nodewire.script.ScriptDiagnostics.readStatus(node.config)) {
        dev.nitka.nodewire.script.ScriptDiagnostics.STATUS_COMPILING -> "…"
        dev.nitka.nodewire.script.ScriptDiagnostics.STATUS_OK -> "✅"
        dev.nitka.nodewire.script.ScriptDiagnostics.STATUS_ERROR -> "❌"
        else -> return
    }
    Text(
        glyph,
        style = NwTheme.typography.caption.copy(color = NwTheme.colors.onAccent),
    )
}

private const val LEFT_BUTTON = 0
private const val RIGHT_BUTTON = 1
private const val DOUBLE_CLICK_MS = 350L

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
    val editor = LocalEditorState.current
    // CardBody is only invoked from the NodeCard surface block, which early-
    // returns when editor is null, so editor is guaranteed non-null here.
    // Defensive gate: skip pin-position writes for nodes hidden by a collapsed
    // group. In practice, hidden NodeCards are never composed (NodeEditorScreen
    // filters them out before calling NodeCard), so the gate is a safety net
    // for any future path that composes a NodeCard while it is hidden.
    val groupsValue by editor!!.groups.collectAsState()
    val hidden = remember(groupsValue) {
        if (groupsValue.isEmpty()) emptySet() else hiddenNodesFor(editor)
    }
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
            // key(pin.id): tie each row's composition identity to the pin id,
            // not its positional slot. After a reshape that removes a pin and
            // slides another into its slot, this disposes the old row's
            // remembered editor state instead of leaking it onto the new pin.
            for (pin in node.inputs) androidx.compose.runtime.key(pin.id) {
                InputPinRow(nodeId, node, pin, hidden)
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2),
            horizontalAlignment = Alignment.End,
        ) {
            for (pin in node.outputs) androidx.compose.runtime.key(pin.id) {
                OutputPinRow(nodeId, pin, hidden)
            }
        }
    }
}

@Composable
private fun InputPinRow(
    nodeId: java.util.UUID,
    node: Node,
    pin: Pin,
    hidden: Set<dev.nitka.nodewire.graph.NodeId>,
) {
    val chip = pinChipText(pin, valueFlowingInto(nodeId, pin.id))
    val editor = LocalEditorState.current
    // An incoming edge to this pin suppresses the inline editor — the
    // wire's value wins and the field would just be misleading.
    val hasIncomingEdge = remember(editor, nodeId, pin.id) {
        editor?.graph?.edges?.any { it.to.node == nodeId && it.to.pin == pin.id } == true
    }
    // Look up the editor spec from the canonical NodeType; the saved
    // pin's type is the fallback for reshape pins (e.g. Switch.case_N)
    // not present in the canonical inputs list.
    val editorSpec = remember(node.typeKey, pin.id, pin.type) {
        NodeTypeRegistry.get(node.typeKey)?.editorFor(pin.id, pin.type)
            ?: dev.nitka.nodewire.graph.defaultEditorFor(pin.type)
    }
    val showEditor = editor != null &&
        !hasIncomingEdge &&
        editorSpec !is dev.nitka.nodewire.graph.PinEditor.None
    // Plain LTR: [●] Name chip [editor?]. fillMaxWidth is LOAD-BEARING:
    // it caps the row at its half-card column so Yoga's flex-shrink can
    // squeeze the chip (which then ellipsizes) — a content-sized row just
    // overflowed into the neighbouring output column instead.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Center,
        horizontalArrangement = Arrangement.spacedBy(NwTheme.dimens.space2),
    ) {
        PinHandle(
            nodeId = nodeId,
            pin = pin,
            side = PinSide.Input,
            hidden = hidden,
            modifier = Modifier.offset(-PIN_HANDLE_HALF, 0),
        )
        Text(pin.name, style = NwTheme.typography.caption)
        ChipLabel(chip)
        if (showEditor) {
            val current = node.getPinDefault(pin.id) ?: PinValue.default(pin.type)
            PinDefaultEditor(
                editor = editorSpec,
                pinType = pin.type,
                current = current,
                onChange = { newValue -> editor!!.setPinDefault(nodeId, pin.id, newValue) },
            )
        }
    }
}

@Composable
private fun OutputPinRow(
    nodeId: java.util.UUID,
    pin: Pin,
    hidden: Set<dev.nitka.nodewire.graph.NodeId>,
) {
    val evalResult = LocalEvalResult.current
    val chip = pinChipText(pin, evalResult?.valueAt(nodeId, pin.id))
    // Mirror layout: chip, Name, [●]. fillMaxWidth + spacedByEnd: the row
    // is capped at its half-card column (so the chip can flex-shrink +
    // ellipsize instead of overflowing into the input column) while the
    // content still packs against the card's right edge.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Center,
        horizontalArrangement = Arrangement.spacedByEnd(NwTheme.dimens.space2),
    ) {
        ChipLabel(chip)
        Text(pin.name, style = NwTheme.typography.caption)
        PinHandle(
            nodeId = nodeId,
            pin = pin,
            side = PinSide.Output,
            hidden = hidden,
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
    hidden: Set<dev.nitka.nodewire.graph.NodeId>,
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
            // onPositioned fires every layout pass — coords arrive in
            // root-relative screen-space (postLayoutWalk sums `layoutX/Y`
            // from the UI root). Subtract the canvas's own origin so the
            // value lives in canvas-local "world" coords, the space the
            // WireLayer pose expects.
            // Gate: do not overwrite proxy-pin positions registered by
            // GroupCollapsedTile for nodes currently hidden inside a
            // collapsed group.
            .onPositioned { coords ->
                if (nodeId !in hidden) {
                    val ox = canvas?.originX ?: 0
                    val oy = canvas?.originY ?: 0
                    editor?.pinPositions?.set(
                        key,
                        (coords.centerX - ox).toFloat(),
                        (coords.centerY - oy).toFloat(),
                    )
                }
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
        // shrink=1: the chip is the row's flexible part — when name+chip+editor
        // exceed the half-card column, Yoga shrinks the chip below intrinsic
        // and TextRenderer ellipsizes, instead of overpainting the neighbour
        // column (long VIDEO handles smeared across the output pin's label).
        modifier = Modifier.flex(shrink = 1f),
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
    // Just the handle prefix — the pin colour + name already say "video",
    // and "vid:" + 8 hex chars didn't fit the half-card pin column.
    is PinValue.Video -> value.handle.toString().take(8)
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
    PinType.VIDEO -> NwTheme.colors.pinVideo
    // ANY pins render in a neutral muted color — they have no canonical
    // type-color since they accept anything; see Task 4 for the dedicated
    // ANY-pin rendering treatment.
    PinType.ANY -> NwTheme.colors.pinAny
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

internal const val CARD_WIDTH = 130

/** Width cap so a pathological pin set can't produce a screen-wide card. */
private const val CARD_WIDTH_MAX = 320

/**
 * Content-measured card width: widest input row + widest output row, with
 * [CARD_WIDTH] as the floor. Editor widths come from the pin's [PinEditor]
 * SPEC (not its current connect state) so the card doesn't resize when a
 * wire connects/disconnects. Pure estimate — the rows' flex-shrink +
 * ellipsis still guard any residual overflow.
 */
private fun desiredCardWidth(node: Node): Int {
    val font = net.minecraft.client.Minecraft.getInstance().font
    val captionScale = 0.85f
    fun tw(s: String): Int = kotlin.math.ceil(font.width(s) * captionScale).toInt()

    val gap = 2
    val handle = 4   // visible half of the edge-centred pin handle
    val chipEst = 30 // short value chip ("0.00f" / "false" / 8-char id)

    fun editorWidth(pin: Pin): Int {
        val spec = NodeTypeRegistry.get(node.typeKey)?.editorFor(pin.id, pin.type)
            ?: dev.nitka.nodewire.graph.defaultEditorFor(pin.type)
        return when (spec) {
            is dev.nitka.nodewire.graph.PinEditor.None -> 0
            is dev.nitka.nodewire.graph.PinEditor.Checkbox -> 14
            is dev.nitka.nodewire.graph.PinEditor.Numeric -> 50
            is dev.nitka.nodewire.graph.PinEditor.Text -> 100
            is dev.nitka.nodewire.graph.PinEditor.Vector -> when (pin.type) {
                dev.nitka.nodewire.graph.PinType.VEC2 -> 62
                dev.nitka.nodewire.graph.PinType.QUAT -> 126
                else -> 94
            }
            is dev.nitka.nodewire.graph.PinEditor.Enum,
            is dev.nitka.nodewire.graph.PinEditor.Slider -> 60
        }
    }

    var maxIn = 0
    for (pin in node.inputs) {
        val e = editorWidth(pin)
        val w = handle + gap + tw(pin.name) + gap + chipEst + (if (e > 0) gap + e else 0)
        if (w > maxIn) maxIn = w
    }
    var maxOut = 0
    for (pin in node.outputs) {
        val w = chipEst + gap + tw(pin.name) + gap + handle
        if (w > maxOut) maxOut = w
    }
    if (maxIn == 0 && maxOut == 0) return CARD_WIDTH
    // Column gap + card body padding + safety slack.
    return (maxIn + maxOut + 20).coerceIn(CARD_WIDTH, CARD_WIDTH_MAX)
}
private const val PIN_HANDLE_SIZE = 8
private const val PIN_HANDLE_HALF = PIN_HANDLE_SIZE / 2
