package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import dev.nitka.nodewire.graph.EvalResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Edge
import dev.nitka.nodewire.graph.Group
import dev.nitka.nodewire.graph.GroupId
import dev.nitka.nodewire.graph.MemberRef
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.NodeId
import dev.nitka.nodewire.graph.NodeTypeRegistry
import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinRef
import dev.nitka.nodewire.graph.PinType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Aggregate session state for one open [NodeEditorScreen]. Holds:
 *   * the live [NodeGraph] being edited
 *   * the [PinPositions] map that pin handles write into and the wire
 *     renderer reads from
 *   * the in-progress "wire drag" — set when the user is dragging from
 *     an output pin, drives the rubber-band wire and the create-edge
 *     decision on release
 *
 * Exposed via [LocalEditorState] so per-card composables (pin handles
 * inside [NodeCard]) can reach it without ceremonial props.
 */
class EditorState(val graph: NodeGraph, val pos: net.minecraft.core.BlockPos = net.minecraft.core.BlockPos.ZERO) {
    val pinPositions = PinPositions()

    private val undoController = GraphUndoController { net.minecraft.Util.getMillis() }

    /**
     * Single entry point for every graph mutation. Snapshots the
     * pre-mutation state, runs [block], then leaves it to the caller's
     * existing flow-update code to refresh per-node Compose state.
     *
     * @param mergeable true for continuous ops (drag, slider) that should
     *   collapse into one undo step within the controller's merge window.
     */
    fun mutateGraph(mergeable: Boolean = false, block: () -> Unit) {
        undoController.snapshot(graph, mergeable)
        block()
    }

    fun canUndo(): Boolean = undoController.canUndo()
    fun canRedo(): Boolean = undoController.canRedo()

    fun undoGraph() {
        val prev = undoController.undo(graph) ?: return
        restoreFrom(prev)
    }

    fun redoGraph() {
        val next = undoController.redo(graph) ?: return
        restoreFrom(next)
    }

    /**
     * Replace the contents of [graph] with [snapshot] in-place, then resync
     * `nodeFlows` / `_nodes` / `_edges` so existing reactive consumers see
     * the restored state. Selection is cleared because restored ids may
     * not include the currently-selected ones.
     */
    private fun restoreFrom(snapshot: NodeGraph) {
        graph.nodes.clear()
        graph.edges.clear()
        for ((id, n) in snapshot.nodes) graph.nodes[id] = n
        graph.edges.addAll(snapshot.edges)

        val toRemove = nodeFlows.keys - graph.nodes.keys
        for (id in toRemove) nodeFlows.remove(id)
        for ((id, n) in graph.nodes) {
            val flow = nodeFlows[id]
            if (flow == null) nodeFlows[id] = MutableStateFlow(n)
            else flow.value = n
        }
        _nodes.value = graph.nodes.keys.toList()
        _edges.value = graph.edges.toList()
        graph.groups.clear()
        graph.groups.addAll(snapshot.groups)
        syncGroupsFlow()
        clearSelection()
    }

    // ── StateFlow plumbing (additive in this task; counters above are kept) ──
    //
    // Each node has its own MutableStateFlow<Node> so card composables only
    // recompose when their own data changes. The flows are populated from
    // the initial graph here and updated by [updateNode] / [addNode] /
    // [removeNode]. The underlying [graph] is still mutated for now so that
    // pre-flow consumers continue to work; final task drops the mirroring.

    private val nodeFlows: MutableMap<NodeId, MutableStateFlow<Node>> =
        graph.nodes.mapValues { (_, n) -> MutableStateFlow(n) }.toMutableMap()

    private val _nodes: MutableStateFlow<List<NodeId>> =
        MutableStateFlow(graph.nodes.keys.toList())
    val nodes: StateFlow<List<NodeId>> = _nodes.asStateFlow()

    private val _edges: MutableStateFlow<List<Edge>> =
        MutableStateFlow(graph.edges.toList())
    val edges: StateFlow<List<Edge>> = _edges.asStateFlow()

    private val _groups: MutableStateFlow<List<Group>> =
        MutableStateFlow(graph.groups.toList())
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    private fun syncGroupsFlow() { _groups.value = graph.groups.toList() }

    private val _blockName = MutableStateFlow("")
    val blockName: StateFlow<String> = _blockName.asStateFlow()
    fun setBlockName(name: String) { _blockName.value = name }

    /** Per-node flow, or null if [id] is unknown. */
    fun nodeFlow(id: NodeId): StateFlow<Node>? = nodeFlows[id]?.asStateFlow()

    /**
     * Apply [transform] to the current Node value and emit the result.
     * No-op if [id] is gone. Both the per-node flow and the underlying
     * [graph.nodes] map are updated so old `graphVersion`-based callers
     * still see consistent state during the migration.
     */
    fun updateNode(id: NodeId, transform: (Node) -> Node) {
        val flow = nodeFlows[id] ?: return
        mutateGraph(mergeable = true) {
            val updated = transform(flow.value)
            flow.value = updated
            graph.nodes[id] = updated
        }
    }

    /** Non-snapshotting inner body of [updateNode] — for use inside a [mutateGraph] block. */
    private fun _updateNodeInternal(id: NodeId, transform: (Node) -> Node) {
        val flow = nodeFlows[id] ?: return
        val updated = transform(flow.value)
        flow.value = updated
        graph.nodes[id] = updated
    }

    fun addNode(node: Node) {
        mutateGraph(mergeable = false) {
            graph.add(node)
            nodeFlows[node.id] = MutableStateFlow(node)
            _nodes.value = _nodes.value + node.id
        }
    }

    fun removeNode(id: dev.nitka.nodewire.graph.NodeId) {
        mutateGraph(mergeable = false) {
            graph.removeNode(id)
            nodeFlows.remove(id)
            _nodes.value = _nodes.value - id
            _edges.value = _edges.value.filter { it.from.node != id && it.to.node != id }
            syncGroupsFlow()
        }
    }

    /**
     * Duplicate [id]: clone the node with a fresh UUID, deep-copy its
     * config, and offset its position so it doesn't sit exactly on top of
     * the original (which would make it look like nothing happened).
     * Connected edges are NOT cloned — the duplicate starts unwired.
     */
    fun duplicateNode(id: dev.nitka.nodewire.graph.NodeId): Node? {
        val src = graph.nodes[id] ?: return null
        val copy = Node(
            id = Node.newId(),
            typeKey = src.typeKey,
            pos = CanvasPos(src.pos.x + DUPLICATE_OFFSET, src.pos.y + DUPLICATE_OFFSET),
            inputs = src.inputs,
            outputs = src.outputs,
            config = src.config.copy(),
        )
        mutateGraph(mergeable = false) {
            graph.add(copy)
            nodeFlows[copy.id] = MutableStateFlow(copy)
            _nodes.value = _nodes.value + copy.id
        }
        return copy
    }

    /** What context menu (if any) is currently open. Null = closed. */
    var contextMenu: ContextMenuTarget? by mutableStateOf(null)
        private set

    fun openCreateMenu(screenX: Int, screenY: Int, world: CanvasPos) {
        contextMenu = ContextMenuTarget.Create(screenX, screenY, world)
    }

    fun openNodeMenu(screenX: Int, screenY: Int, nodeId: dev.nitka.nodewire.graph.NodeId) {
        contextMenu = ContextMenuTarget.Node(screenX, screenY, nodeId)
    }

    fun openGroupMenu(screenX: Int, screenY: Int, groupId: GroupId) {
        contextMenu = ContextMenuTarget.Group(screenX, screenY, groupId)
    }

    /** Tracks a group whose Save-as-template dialog should be shown. */
    var pendingSaveTemplateForGroup: GroupId? by mutableStateOf(null)

    fun closeContextMenu() {
        contextMenu = null
    }

    /** Output pin currently being dragged from. Null when no drag in progress. */
    var wireDragSource: PinKey? by mutableStateOf(null)
        private set

    /**
     * Sticky drag: when true, releasing the mouse over a compatible input
     * commits the edge AND keeps the wire attached for more connections.
     * Activated by holding Shift while pressing on the output pin.
     */
    var wireDragSticky: Boolean by mutableStateOf(false)
        private set

    /** Cursor position in canvas-world coordinates while [wireDragSource] is set. */
    var wireDragCursorX: Float by mutableStateOf(0f)
        private set
    var wireDragCursorY: Float by mutableStateOf(0f)
        private set

    /**
     * Begin a wire drag from [source]. Either side is valid — dragging from
     * an output looks for a compatible input under the cursor, and vice
     * versa.
     *
     * [sticky] controls the post-commit behavior (output sources only —
     * inputs can only have one source so chaining wouldn't apply):
     *   * `false` (default) — release on a compatible pin commits and ends
     *     the drag. Release elsewhere cancels.
     *   * `true` — release on a compatible input commits and the wire stays
     *     attached for more connections. Click empty / right-click cancels.
     */
    fun beginWireDrag(source: PinKey, sticky: Boolean = false): Boolean {
        val p = pinPositions.get(source) ?: return false
        wireDragSource = source
        // Sticky chaining only makes sense for output sources — an input
        // can have at most one incoming edge so "chain inputs" is a no-op.
        wireDragSticky = sticky && source.side == PinSide.Output
        wireDragCursorX = p.first
        wireDragCursorY = p.second
        return true
    }

    /** Advance the cursor by a world-space delta (used when dragging with button held). */
    fun updateWireDrag(dxWorld: Float, dyWorld: Float) {
        wireDragCursorX += dxWorld
        wireDragCursorY += dyWorld
    }

    /** Set cursor to an absolute world position (used by Move-event tracking). */
    fun setCursor(xWorld: Float, yWorld: Float) {
        wireDragCursorX = xWorld
        wireDragCursorY = yWorld
    }

    /**
     * Commit to an explicit [target] pin (must be the opposite side of
     * [wireDragSource]). Used when the user clicks directly on a pin —
     * bypasses the radius search since we already know the target.
     * Returns true if an edge was created.
     */
    fun commitWireTo(target: PinKey): Boolean {
        val src = wireDragSource ?: return false
        if (target.side == src.side) return false  // need opposite sides
        if (target.node == src.node) return false
        val (output, input) = orderOutputInput(src, target)
        if (pinType(output) != pinType(input)) return false
        val edge = Edge(PinRef(output.node, output.pin), PinRef(input.node, input.pin))
        mutateGraph(mergeable = false) {
            graph.connectReplacing(edge)
            _edges.value = graph.edges.toList()
        }
        return true
    }

    /**
     * Called on mouse Release at the end of a press-and-drag. Searches for
     * a compatible pin (opposite side, matching type) under the cursor and
     * commits the edge if found. Clears the source unless [wireDragSticky].
     */
    fun finishWireDragOnRelease(): Boolean {
        val src = wireDragSource ?: return false
        val target = findCompatibleOppositeAt(src, wireDragCursorX, wireDragCursorY)
        if (target != null) {
            val (output, input) = orderOutputInput(src, target)
            val edge = Edge(PinRef(output.node, output.pin), PinRef(input.node, input.pin))
            graph.connectReplacing(edge)
            _edges.value = graph.edges.toList()
        }
        if (!wireDragSticky) wireDragSource = null
        return target != null
    }

    fun cancelWireDrag() {
        wireDragSource = null
        wireDragSticky = false
    }

    /**
     * Remove every edge touching the given pin (either as `from` or `to`).
     * Bound to right-click in [NodeCard] — gives the user one-action
     * disconnect on both inputs and outputs.
     */
    /**
     * Rebuilds [node]'s ChannelInput or ChannelOutput pin to the given
     * type. Any edges touching the changed pin are disconnected — caller
     * confirmed the auto-disconnect-on-change UX (option 1a in the round-3
     * design Q&A).
     */
    fun changeChannelType(id: dev.nitka.nodewire.graph.NodeId, newType: dev.nitka.nodewire.graph.PinType) {
        mutateGraph(mergeable = false) {
            _updateNodeInternal(id) { n ->
                val pin = (n.inputs + n.outputs).firstOrNull() ?: return@_updateNodeInternal n
                val rebuilt = pin.copy(type = newType)
                val newConfig = n.config.copy().apply { putString("type", newType.name) }
                if (n.inputs.isNotEmpty()) n.copy(inputs = listOf(rebuilt), config = newConfig)
                else n.copy(outputs = listOf(rebuilt), config = newConfig)
            }
            _disconnectAllEdgesInternal(id)
        }
    }

    /**
     * LogicGate: write the new op, rebuild input pins (NOT → single "in";
     * any other op → two pins "a" and "b"), and disconnect all edges because
     * the pin IDs may have changed.
     */
    fun changeLogicGateOp(id: NodeId, newOp: String) {
        mutateGraph(mergeable = false) {
            _updateNodeInternal(id) { n ->
                val inputs = if (newOp == "NOT") {
                    listOf(dev.nitka.nodewire.graph.Pin("in", "In", dev.nitka.nodewire.graph.PinType.BOOL))
                } else {
                    listOf(
                        dev.nitka.nodewire.graph.Pin("a", "A", dev.nitka.nodewire.graph.PinType.BOOL),
                        dev.nitka.nodewire.graph.Pin("b", "B", dev.nitka.nodewire.graph.PinType.BOOL),
                    )
                }
                val newConfig = n.config.copy().apply { putString("op", newOp) }
                n.copy(inputs = inputs, config = newConfig)
            }
            _disconnectAllEdgesInternal(id)
        }
    }

    /**
     * Math node: rebuild all input + output pins to [newType] and store
     * both [newOp] and [newType] in config. Disconnects all edges because
     * pin types may have changed.
     */
    fun changeMathConfig(
        id: NodeId,
        newOp: String,
        newType: dev.nitka.nodewire.graph.PinType,
    ) {
        mutateGraph(mergeable = false) {
            _updateNodeInternal(id) { n ->
                val inputs = listOf(
                    dev.nitka.nodewire.graph.Pin("a", "A", newType),
                    dev.nitka.nodewire.graph.Pin("b", "B", newType),
                )
                val outputs = listOf(dev.nitka.nodewire.graph.Pin("out", "Out", newType))
                val newConfig = n.config.copy().apply {
                    putString("op", newOp)
                    putString("type", newType.name)
                }
                n.copy(inputs = inputs, outputs = outputs, config = newConfig)
            }
            _disconnectAllEdgesInternal(id)
        }
    }

    /**
     * Compare node: rebuild both input pins to [newType] and write it into
     * `config.type`. Outputs (gt/eq/lt) are always BOOL and don't change.
     * All edges are disconnected for uniformity with the other mutators.
     */
    fun changeCompareType(
        id: NodeId,
        newType: dev.nitka.nodewire.graph.PinType,
    ) {
        mutateGraph(mergeable = false) {
            _updateNodeInternal(id) { n ->
                val inputs = listOf(
                    dev.nitka.nodewire.graph.Pin("a", "A", newType),
                    dev.nitka.nodewire.graph.Pin("b", "B", newType),
                )
                val newConfig = n.config.copy().apply { putString("type", newType.name) }
                n.copy(inputs = inputs, config = newConfig)
            }
            _disconnectAllEdgesInternal(id)
        }
    }

    /**
     * Constant node: rebuild the single output pin to the given type and
     * record it in `config.type`. Any outgoing edges are disconnected because
     * downstream pins may no longer match the new type.
     */
    fun changeConstantType(
        id: dev.nitka.nodewire.graph.NodeId,
        newType: dev.nitka.nodewire.graph.PinType,
    ) {
        mutateGraph(mergeable = false) {
            _updateNodeInternal(id) { n ->
                val rebuilt = n.outputs.first().copy(type = newType)
                val newConfig = n.config.copy().apply { putString("type", newType.name) }
                n.copy(outputs = listOf(rebuilt), config = newConfig)
            }
            _disconnectAllEdgesInternal(id)
        }
    }

    /**
     * Convert node: rebuild input pin to [source] type and output pin to
     * [target] type, write both config keys (+ default mode for REDSTONE pairs),
     * and disconnect all edges because both pin types may have changed.
     */
    fun changeConvertTypes(
        id: NodeId,
        source: dev.nitka.nodewire.graph.PinType,
        target: dev.nitka.nodewire.graph.PinType,
    ) {
        mutateGraph(mergeable = false) {
            _updateNodeInternal(id) { n ->
                val inputs  = listOf(dev.nitka.nodewire.graph.Pin("in",  "In",  source))
                val outputs = listOf(dev.nitka.nodewire.graph.Pin("out", "Out", target))
                val mode = defaultConvertModeFor(source, target)
                val newConfig = n.config.copy().apply {
                    putString("sourceType", source.name)
                    putString("targetType", target.name)
                    putString("mode", mode)
                }
                n.copy(inputs = inputs, outputs = outputs, config = newConfig)
            }
            _disconnectAllEdgesInternal(id)
        }
    }

    fun changeConvertMode(id: NodeId, mode: String) {
        mutateGraph(mergeable = false) {
            _updateNodeInternal(id) { n ->
                n.copy(config = n.config.copy().apply { putString("mode", mode) })
            }
        }
    }

    /**
     * VecMake / VecSplit node: rebuild input/output pins for the new
     * dimension and write `config.dim`. VecMake has scalar inputs +
     * vector output; VecSplit is the inverse. Identify by node typeKey.
     */
    fun changeVecMakeSplitDim(
        id: dev.nitka.nodewire.graph.NodeId,
        newDim: String,  // "VEC2" or "VEC3"
    ) {
        mutateGraph(mergeable = false) {
            _updateNodeInternal(id) { n ->
                val isVec2 = newDim == "VEC2"
                val vecType = if (isVec2) dev.nitka.nodewire.graph.PinType.VEC2 else dev.nitka.nodewire.graph.PinType.VEC3
                val isMake = n.typeKey.path == "vec_make"
                val newInputs: List<dev.nitka.nodewire.graph.Pin>
                val newOutputs: List<dev.nitka.nodewire.graph.Pin>
                if (isMake) {
                    val xs = mutableListOf(
                        dev.nitka.nodewire.graph.Pin("x", "X", dev.nitka.nodewire.graph.PinType.FLOAT),
                        dev.nitka.nodewire.graph.Pin("y", "Y", dev.nitka.nodewire.graph.PinType.FLOAT),
                    )
                    if (!isVec2) xs.add(
                        dev.nitka.nodewire.graph.Pin("z", "Z", dev.nitka.nodewire.graph.PinType.FLOAT),
                    )
                    newInputs = xs
                    newOutputs = listOf(
                        dev.nitka.nodewire.graph.Pin("out", "Out", vecType),
                    )
                } else {
                    // vec_split
                    newInputs = listOf(
                        dev.nitka.nodewire.graph.Pin("in", "In", vecType),
                    )
                    val outs = mutableListOf(
                        dev.nitka.nodewire.graph.Pin("x", "X", dev.nitka.nodewire.graph.PinType.FLOAT),
                        dev.nitka.nodewire.graph.Pin("y", "Y", dev.nitka.nodewire.graph.PinType.FLOAT),
                    )
                    if (!isVec2) outs.add(
                        dev.nitka.nodewire.graph.Pin("z", "Z", dev.nitka.nodewire.graph.PinType.FLOAT),
                    )
                    newOutputs = outs
                }
                val newConfig = n.config.copy().apply { putString("dim", newDim) }
                n.copy(inputs = newInputs, outputs = newOutputs, config = newConfig)
            }
            _disconnectAllEdgesInternal(id)
        }
    }

    private fun defaultConvertModeFor(
        s: dev.nitka.nodewire.graph.PinType,
        t: dev.nitka.nodewire.graph.PinType,
    ): String = when (s to t) {
        dev.nitka.nodewire.graph.PinType.INT      to dev.nitka.nodewire.graph.PinType.REDSTONE -> "clamp"
        dev.nitka.nodewire.graph.PinType.FLOAT    to dev.nitka.nodewire.graph.PinType.REDSTONE -> "scaled"
        dev.nitka.nodewire.graph.PinType.BOOL     to dev.nitka.nodewire.graph.PinType.REDSTONE -> "hi"
        dev.nitka.nodewire.graph.PinType.REDSTONE to dev.nitka.nodewire.graph.PinType.INT      -> "raw"
        dev.nitka.nodewire.graph.PinType.REDSTONE to dev.nitka.nodewire.graph.PinType.FLOAT    -> "normalized"
        dev.nitka.nodewire.graph.PinType.REDSTONE to dev.nitka.nodewire.graph.PinType.BOOL     -> "any"
        else -> ""
    }

    /**
     * controller_input: write `config.channel` and reshape output pins
     * based on the new channel's category default outputMode. Edges on
     * removed/typed-changed pins drop.
     */
    fun changeControllerChannel(
        id: dev.nitka.nodewire.graph.NodeId,
        channelName: String,
    ) {
        val channel = dev.nitka.nodewire.integration.tweakedcontroller.ControllerChannel.fromName(channelName)
        val modeList = dev.nitka.nodewire.integration.tweakedcontroller.allowedOutputModes(channel.category)
        val newMode = modeList.first()
        mutateGraph(mergeable = false) {
            _updateNodeInternal(id) { n ->
                val outs = dev.nitka.nodewire.integration.tweakedcontroller
                    .pinsForControllerInput(channel, newMode)
                val newConfig = n.config.copy().apply {
                    putString("channel", channel.name)
                    putString("outputMode", newMode.name)
                }
                n.copy(outputs = outs, config = newConfig)
            }
            _disconnectAllEdgesInternal(id)
        }
    }

    /**
     * controller_input: write `config.outputMode` and reshape output
     * pins. Channel stays put.
     */
    fun changeControllerOutputMode(
        id: dev.nitka.nodewire.graph.NodeId,
        modeName: String,
    ) {
        mutateGraph(mergeable = false) {
            _updateNodeInternal(id) { n ->
                val channel = dev.nitka.nodewire.integration.tweakedcontroller
                    .ControllerChannel.fromName(n.config.getString("channel"))
                val mode = dev.nitka.nodewire.integration.tweakedcontroller
                    .ControllerOutputMode.entries.firstOrNull { it.name == modeName }
                    ?: dev.nitka.nodewire.integration.tweakedcontroller
                        .allowedOutputModes(channel.category).first()
                val outs = dev.nitka.nodewire.integration.tweakedcontroller
                    .pinsForControllerInput(channel, mode)
                val newConfig = n.config.copy().apply {
                    putString("outputMode", mode.name)
                }
                n.copy(outputs = outs, config = newConfig)
            }
            _disconnectAllEdgesInternal(id)
        }
    }

    /**
     * VecOp node: rebuild the input/output pin list from (op, dim) and
     * write both into config. Dim-locked ops (CROSS=VEC3, ROTATE2D=VEC2,
     * TO_VEC3 inputs VEC2→VEC3, TO_VEC2 inputs VEC3→VEC2) overwrite
     * caller's [dim].
     */
    fun changeVecOp(
        id: dev.nitka.nodewire.graph.NodeId,
        op: String,
        dim: String,  // "VEC2" / "VEC3"; ignored for locked ops
    ) {
        mutateGraph(mergeable = false) {
            _updateNodeInternal(id) { n ->
                val (effectiveDim, ins, outs) = pinsForVecOp(op, dim)
                val newConfig = n.config.copy().apply {
                    putString("op", op)
                    putString("dim", effectiveDim)
                }
                n.copy(inputs = ins, outputs = outs, config = newConfig)
            }
            _disconnectAllEdgesInternal(id)
        }
    }

    /**
     * For a (op, dim), return the canonical (effectiveDim, inputs, outputs)
     * triple. Op categories:
     *   * Vec→Vec binary: a, b ∈ V → out:V
     *   * Vec→Vec unary: v ∈ V → out:V
     *   * Scalar-mixed: SCALE(v, s), CLAMP_MAG(v, max), LERP(a, b, t)
     *   * Vec→Vec mixed: PROJECT(a, b), REFLECT(v, n)
     *   * Reductions: DOT/DISTANCE/ANGLE binary; LENGTH/LENGTH_SQ unary
     *   * Dim-locked: CROSS=VEC3, ROTATE2D=VEC2
     *   * Conversions: TO_VEC3 (v:VEC2, z:FLOAT → VEC3), TO_VEC2 (v:VEC3 → VEC2)
     */
    private fun pinsForVecOp(
        op: String,
        dim: String,
    ): Triple<String, List<Pin>, List<Pin>> {
        val effectiveDim = when (op) {
            "CROSS" -> "VEC3"
            "ROTATE2D" -> "VEC2"
            "TO_VEC3", "TO_VEC2" -> dim  // unused at evaluator level
            else -> if (dim == "VEC3") "VEC3" else "VEC2"
        }
        val V = if (effectiveDim == "VEC3") PinType.VEC3 else PinType.VEC2
        return when (op) {
            // Binary vec→vec
            "ADD", "SUB", "MUL_COMPONENT", "MIN", "MAX" -> Triple(
                effectiveDim,
                listOf(Pin("a", "A", V), Pin("b", "B", V)),
                listOf(Pin("out", "Out", V)),
            )
            // Unary vec→vec
            "NEGATE", "NORMALIZE", "ABS" -> Triple(
                effectiveDim,
                listOf(Pin("v", "V", V)),
                listOf(Pin("out", "Out", V)),
            )
            "SCALE" -> Triple(
                effectiveDim,
                listOf(Pin("v", "V", V), Pin("s", "S", PinType.FLOAT)),
                listOf(Pin("out", "Out", V)),
            )
            "CLAMP_MAG" -> Triple(
                effectiveDim,
                listOf(Pin("v", "V", V), Pin("max", "Max", PinType.FLOAT)),
                listOf(Pin("out", "Out", V)),
            )
            "LERP" -> Triple(
                effectiveDim,
                listOf(Pin("a", "A", V), Pin("b", "B", V), Pin("t", "T", PinType.FLOAT)),
                listOf(Pin("out", "Out", V)),
            )
            "PROJECT" -> Triple(
                effectiveDim,
                listOf(Pin("a", "A", V), Pin("b", "B", V)),
                listOf(Pin("out", "Out", V)),
            )
            "REFLECT" -> Triple(
                effectiveDim,
                listOf(Pin("v", "V", V), Pin("n", "N", V)),
                listOf(Pin("out", "Out", V)),
            )
            // Reductions → FLOAT out
            "DOT", "DISTANCE", "ANGLE" -> Triple(
                effectiveDim,
                listOf(Pin("a", "A", V), Pin("b", "B", V)),
                listOf(Pin("out", "Out", PinType.FLOAT)),
            )
            "LENGTH", "LENGTH_SQ" -> Triple(
                effectiveDim,
                listOf(Pin("v", "V", V)),
                listOf(Pin("out", "Out", PinType.FLOAT)),
            )
            // Dim-locked
            "CROSS" -> Triple(
                "VEC3",
                listOf(Pin("a", "A", PinType.VEC3), Pin("b", "B", PinType.VEC3)),
                listOf(Pin("out", "Out", PinType.VEC3)),
            )
            "ROTATE2D" -> Triple(
                "VEC2",
                listOf(Pin("v", "V", PinType.VEC2), Pin("angle", "Angle", PinType.FLOAT)),
                listOf(Pin("out", "Out", PinType.VEC2)),
            )
            // Conversions
            "TO_VEC3" -> Triple(
                effectiveDim,
                listOf(Pin("v", "V", PinType.VEC2), Pin("z", "Z", PinType.FLOAT)),
                listOf(Pin("out", "Out", PinType.VEC3)),
            )
            "TO_VEC2" -> Triple(
                effectiveDim,
                listOf(Pin("v", "V", PinType.VEC3)),
                listOf(Pin("out", "Out", PinType.VEC2)),
            )
            // Unknown op → fallback to ADD shape
            else -> Triple(
                effectiveDim,
                listOf(Pin("a", "A", V), Pin("b", "B", V)),
                listOf(Pin("out", "Out", V)),
            )
        }
    }

    /** Non-snapshotting inner body — for use inside a [mutateGraph] block. */
    private fun _disconnectAllEdgesInternal(id: dev.nitka.nodewire.graph.NodeId) {
        val before = graph.edges.size
        graph.edges.removeAll { it.from.node == id || it.to.node == id }
        if (graph.edges.size != before) {
            _edges.value = graph.edges.toList()
        }
    }

    fun disconnectAllEdges(id: dev.nitka.nodewire.graph.NodeId) {
        mutateGraph(mergeable = false) { _disconnectAllEdgesInternal(id) }
    }

    fun disconnectPin(key: PinKey) {
        mutateGraph(mergeable = false) {
            val before = graph.edges.size
            graph.edges.removeAll { edge ->
                (edge.from.node == key.node && edge.from.pin == key.pin && key.side == PinSide.Output) ||
                    (edge.to.node == key.node && edge.to.pin == key.pin && key.side == PinSide.Input)
            }
            if (graph.edges.size != before) {
                _edges.value = graph.edges.toList()
            }
        }
    }

    /** Look for the nearest opposite-side pin within hit radius whose type matches [src]. */
    private fun findCompatibleOppositeAt(src: PinKey, x: Float, y: Float): PinKey? {
        val srcType = pinType(src) ?: return null
        val wantSide = if (src.side == PinSide.Output) PinSide.Input else PinSide.Output
        var best: PinKey? = null
        var bestSq = PIN_HIT_RADIUS * PIN_HIT_RADIUS
        for ((key, pos) in pinPositions.all()) {
            if (key.side != wantSide) continue
            if (key.node == src.node) continue
            if (pinType(key) != srcType) continue
            val dx = pos.first - x
            val dy = pos.second - y
            val sq = dx * dx + dy * dy
            if (sq < bestSq) {
                bestSq = sq
                best = key
            }
        }
        return best
    }

    private fun pinType(key: PinKey): dev.nitka.nodewire.graph.PinType? {
        val node = graph.nodes[key.node] ?: return null
        return if (key.side == PinSide.Input) {
            node.inputs.firstOrNull { it.id == key.pin }?.type
        } else {
            node.outputs.firstOrNull { it.id == key.pin }?.type
        }
    }

    private fun orderOutputInput(a: PinKey, b: PinKey): Pair<PinKey, PinKey> =
        if (a.side == PinSide.Output) a to b else b to a

    // ── Multi-selection + rubber-band ─────────────────────────────────
    //
    // The selection is a Compose state value so NodeCard recomposes when
    // membership changes. We hold the *contents* in a mutableStateOf<Set>
    // (immutable snapshots) rather than a MutableSet — that's the pattern
    // that Compose actually observes.

    var selectedNodes: Set<dev.nitka.nodewire.graph.NodeId> by mutableStateOf(emptySet())
        private set

    /**
     * Measured card sizes in world units (pre-zoom). Updated by NodeCard
     * via onSizeChanged so rubber-band hit tests use real bounds — using a
     * constant height guess made the AABB extend below the card and
     * select nodes whose wires (not bodies) crossed the rect.
     */
    private val cardSizes: MutableMap<dev.nitka.nodewire.graph.NodeId, Pair<Int, Int>> = HashMap()

    fun setCardSize(id: dev.nitka.nodewire.graph.NodeId, width: Int, height: Int) {
        cardSizes[id] = width to height
    }

    fun cardSize(id: dev.nitka.nodewire.graph.NodeId): Pair<Int, Int>? = cardSizes[id]

    fun isSelected(id: dev.nitka.nodewire.graph.NodeId): Boolean = id in selectedNodes

    fun clearSelection() { if (selectedNodes.isNotEmpty()) selectedNodes = emptySet() }

    /** Replace the selection with this one node. */
    fun selectOnly(id: dev.nitka.nodewire.graph.NodeId) { selectedNodes = setOf(id) }

    /** Toggle an id in the current selection (used for Shift+click). */
    fun toggleSelection(id: dev.nitka.nodewire.graph.NodeId) {
        selectedNodes = if (id in selectedNodes) selectedNodes - id else selectedNodes + id
    }

    fun selectAll(ids: Collection<dev.nitka.nodewire.graph.NodeId>) {
        selectedNodes = ids.toSet()
    }

    /**
     * Move every selected node by the given world delta. Called from a
     * card's title-bar drag when that card is part of the selection — all
     * cards shift together visually because their `pos` is read from
     * [Node.pos] which we mutate here.
     */
    fun moveSelected(dxWorld: Float, dyWorld: Float) {
        if (selectedNodes.isEmpty()) return
        mutateGraph(mergeable = true) {
            for (id in selectedNodes) {
                _updateNodeInternal(id) { n -> n.copy(pos = CanvasPos(n.pos.x + dxWorld, n.pos.y + dyWorld)) }
            }
        }
    }

    /**
     * Rubber-band drag in canvas-world coordinates. Non-null only while
     * the user is dragging an empty-area selection rectangle. The screen
     * renders the box and `commitSelectionDrag` finalises which nodes are
     * inside.
     */
    var selectionDragStart: CanvasPos? by mutableStateOf(null)
        private set
    var selectionDragCurrent: CanvasPos? by mutableStateOf(null)
        private set

    fun beginSelectionDrag(worldX: Float, worldY: Float) {
        selectionDragStart = CanvasPos(worldX, worldY)
        selectionDragCurrent = CanvasPos(worldX, worldY)
    }

    fun updateSelectionDrag(worldX: Float, worldY: Float) {
        selectionDragCurrent = CanvasPos(worldX, worldY)
    }

    /**
     * Finalise: pick up every node whose card AABB intersects the
     * rectangle. Card width is fixed at [NODE_CARD_WIDTH]; for height we
     * use a conservative constant since pin rows vary — overshooting a
     * bit is friendlier than missing a node by a pixel. Press-without-
     * drag (start ≈ current) is treated as "clear selection".
     */
    fun commitSelectionDrag(additive: Boolean) {
        val s = selectionDragStart
        val e = selectionDragCurrent
        selectionDragStart = null
        selectionDragCurrent = null
        if (s == null || e == null) return
        val dx = e.x - s.x
        val dy = e.y - s.y
        val isClick = dx * dx + dy * dy < CLICK_THRESHOLD * CLICK_THRESHOLD
        if (isClick) {
            if (!additive) clearSelection()
            return
        }
        val minX = minOf(s.x, e.x)
        val maxX = maxOf(s.x, e.x)
        val minY = minOf(s.y, e.y)
        val maxY = maxOf(s.y, e.y)
        val hits = graph.nodes.values.filter { n ->
            // Use measured size if we have one; fall back to a conservative
            // small guess so a card whose onSizeChanged hasn't fired yet
            // doesn't over-include.
            val size = cardSizes[n.id]
            val w = size?.first?.toFloat() ?: NODE_CARD_WIDTH
            val h = size?.second?.toFloat() ?: NODE_CARD_HEIGHT_FALLBACK
            val left = n.pos.x
            val top = n.pos.y
            val right = left + w
            val bottom = top + h
            !(right < minX || left > maxX || bottom < minY || top > maxY)
        }.map { it.id }
        selectedNodes = if (additive) selectedNodes + hits else hits.toSet()
    }

    fun cancelSelectionDrag() {
        selectionDragStart = null
        selectionDragCurrent = null
    }

    // ── nodeBounds + selection ops + clipboard ────────────────────────────

    /** Per-card bounds, written by `NodeCard.onPositioned`, read by frame/fit. */
    val nodeBounds: SnapshotStateMap<NodeId, NodeBounds> = mutableStateMapOf()

    /** Select all nodes in the graph. */
    fun selectAll() {
        selectedNodes = graph.nodes.keys.toSet()
    }

    /** Replace selection with [ids]. */
    fun selectMany(ids: Collection<NodeId>) {
        selectedNodes = ids.toSet()
    }

    fun deleteSelected() {
        if (selectedNodes.isEmpty()) return
        val ids = selectedNodes.toList()
        mutateGraph {
            for (id in ids) {
                graph.nodes.remove(id)
                nodeFlows.remove(id)
            }
            graph.edges.removeAll { it.from.node in ids || it.to.node in ids }
            _nodes.value = graph.nodes.keys.toList()
            _edges.value = graph.edges.toList()
        }
        clearSelection()
    }

    fun duplicateSelected() {
        if (selectedNodes.isEmpty()) return
        val sources = selectedNodes.mapNotNull { graph.nodes[it] }
        if (sources.isEmpty()) return
        val newIds = mutableListOf<NodeId>()
        mutateGraph {
            for (src in sources) {
                val copy = Node(
                    id = Node.newId(),
                    typeKey = src.typeKey,
                    pos = CanvasPos(src.pos.x + DUPLICATE_OFFSET, src.pos.y + DUPLICATE_OFFSET),
                    inputs = src.inputs,
                    outputs = src.outputs,
                    config = src.config.copy(),
                )
                graph.add(copy)
                nodeFlows[copy.id] = MutableStateFlow(copy)
                newIds.add(copy.id)
            }
            _nodes.value = graph.nodes.keys.toList()
        }
        selectMany(newIds)
    }

    fun copySelectedToClipboard() {
        if (selectedNodes.isEmpty()) return
        val sub = NodeGraph().apply {
            for (n in graph.nodes.values) if (n.id in selectedNodes) add(n)
            for (e in graph.edges) {
                if (e.from.node in selectedNodes && e.to.node in selectedNodes) edges.add(e)
            }
        }
        net.minecraft.client.Minecraft.getInstance().keyboardHandler.clipboard =
            GraphClipboard.encode(sub)
    }

    fun cutSelectedToClipboard() {
        if (selectedNodes.isEmpty()) return
        copySelectedToClipboard()
        deleteSelected()
    }

    /**
     * Decode clipboard SNBT and paste at world ([cursorWorldX], [cursorWorldY]).
     * Regenerates all UUIDs, drops nodes whose typeKey is no longer registered,
     * drops edges that reference dropped nodes. Selects pasted nodes.
     * Silent no-op if clipboard isn't our format.
     */
    fun pasteFromClipboard(cursorWorldX: Float, cursorWorldY: Float) {
        val raw = net.minecraft.client.Minecraft.getInstance().keyboardHandler.clipboard ?: return
        val sub = GraphClipboard.decode(raw) ?: return

        val idMap = HashMap<NodeId, NodeId>()
        val newNodes = sub.nodes.values.mapNotNull { old ->
            if (NodeTypeRegistry.get(old.typeKey) == null) return@mapNotNull null
            val newId = Node.newId()
            idMap[old.id] = newId
            old.copy(id = newId)
        }
        if (newNodes.isEmpty()) return

        val cx = newNodes.map { it.pos.x }.average().toFloat()
        val cy = newNodes.map { it.pos.y }.average().toFloat()
        val dx = cursorWorldX - cx
        val dy = cursorWorldY - cy
        val translated = newNodes.map {
            it.copy(pos = CanvasPos(it.pos.x + dx, it.pos.y + dy))
        }

        val newEdges = sub.edges.mapNotNull { e ->
            val newFromId = idMap[e.from.node] ?: return@mapNotNull null
            val newToId = idMap[e.to.node] ?: return@mapNotNull null
            e.copy(
                from = e.from.copy(node = newFromId),
                to = e.to.copy(node = newToId),
            )
        }

        mutateGraph {
            for (n in translated) {
                graph.add(n)
                nodeFlows[n.id] = MutableStateFlow(n)
            }
            graph.edges.addAll(newEdges)
            _nodes.value = graph.nodes.keys.toList()
            _edges.value = graph.edges.toList()
        }
        selectMany(translated.map { it.id })
    }

    /** Hooked up from `NodeEditorScreen.Content` so frame methods can adjust pan/zoom. */
    var canvasState: dev.nitka.nodewire.ui.canvas.CanvasState? = null

    fun frameSelectedOrAll() {
        val ids = if (selectedNodes.isNotEmpty()) selectedNodes else graph.nodes.keys
        frameNodes(ids)
    }

    fun frameAll() = frameNodes(graph.nodes.keys)

    private fun frameNodes(ids: Set<NodeId>) {
        if (ids.isEmpty()) return
        val canvas = canvasState ?: return
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (id in ids) {
            val n = graph.nodes[id] ?: continue
            val b = nodeBounds[id] ?: NodeBounds(120, 60)
            minX = minOf(minX, n.pos.x); minY = minOf(minY, n.pos.y)
            maxX = maxOf(maxX, n.pos.x + b.width); maxY = maxOf(maxY, n.pos.y + b.height)
        }
        canvas.frameRect(minX, minY, maxX, maxY)
    }

    /**
     * Replace the entire graph contents with [other] (deep enough — we
     * reuse the existing [restoreFrom] path so per-node flows stay live
     * and downstream readers don't blow up on missing entries). Pushed
     * through [mutateGraph] so the swap itself is one undo step.
     */
    fun replaceGraph(other: NodeGraph) {
        mutateGraph(mergeable = false) {
            restoreFrom(other)
        }
    }

    /**
     * Build a fresh [NodeGraph] from the current per-node + edge flows.
     * Called on screen close to ship the latest state to the server via
     * [dev.nitka.nodewire.net.SaveGraphPacket].
     */
    fun snapshotGraph(): NodeGraph {
        val g = NodeGraph()
        for (id in _nodes.value) {
            val n = nodeFlows[id]?.value ?: continue
            g.add(n)
        }
        for (e in _edges.value) g.addEdge(e)
        for (group in _groups.value) g.groups.add(group)
        return g
    }

    /** Create an inline group containing the current selection. */
    fun createGroupFromSelection(name: String): GroupId? {
        if (selectedNodes.isEmpty()) return null
        val ids = selectedNodes.toList()
        val xs = ids.mapNotNull { graph.nodes[it]?.pos?.x }
        val ys = ids.mapNotNull { graph.nodes[it]?.pos?.y }
        val anchor = if (xs.isEmpty()) CanvasPos.Zero
        else CanvasPos(xs.min(), ys.min())
        val g = Group(
            id = Group.newId(),
            name = name,
            members = ids.map { MemberRef.Node(it) },
            templateFile = null,
            templateIdMap = null,
            collapsed = false,
            pos = anchor,
            collapsedSize = null,
        )
        mutateGraph {
            graph.groups.add(g)
            syncGroupsFlow()
        }
        return g.id
    }

    /** Dissolve a group; members survive at their current positions. */
    fun ungroup(id: GroupId) {
        mutateGraph {
            graph.groups.removeAll { it.id == id }
            val rebuilt = graph.groups.map { g ->
                g.copy(members = g.members.filter { m -> m !is MemberRef.Sub || m.id != id })
            }
            graph.groups.clear()
            graph.groups.addAll(rebuilt)
            syncGroupsFlow()
        }
    }

    fun toggleCollapsed(id: GroupId) {
        mutateGraph {
            val i = graph.groups.indexOfFirst { it.id == id }
            if (i < 0) return@mutateGraph
            graph.groups[i] = graph.groups[i].copy(collapsed = !graph.groups[i].collapsed)
            syncGroupsFlow()
        }
    }

    fun unlinkGroup(id: GroupId) {
        mutateGraph {
            val i = graph.groups.indexOfFirst { it.id == id }
            if (i < 0) return@mutateGraph
            graph.groups[i] = graph.groups[i].copy(templateFile = null, templateIdMap = null)
            syncGroupsFlow()
        }
    }

    fun addMemberToGroup(groupId: GroupId, nodeId: NodeId) {
        mutateGraph {
            val i = graph.groups.indexOfFirst { it.id == groupId }
            if (i < 0) return@mutateGraph
            val cur = graph.groups[i]
            if (cur.members.any { it is MemberRef.Node && it.id == nodeId }) return@mutateGraph
            graph.groups[i] = cur.copy(members = cur.members + MemberRef.Node(nodeId))
            syncGroupsFlow()
        }
    }

    fun removeMemberFromGroup(groupId: GroupId, nodeId: NodeId) {
        mutateGraph {
            val i = graph.groups.indexOfFirst { it.id == groupId }
            if (i < 0) return@mutateGraph
            val cur = graph.groups[i]
            graph.groups[i] = cur.copy(
                members = cur.members.filter { it !is MemberRef.Node || it.id != nodeId }
            )
            syncGroupsFlow()
        }
    }

    /** Move every node in [groupId]'s closure by `(dx, dy)`. Used by frame header drag. */
    fun moveGroup(groupId: GroupId, dxWorld: Float, dyWorld: Float) {
        val g = graph.groups.firstOrNull { it.id == groupId } ?: return
        val allById = graph.groups.associateBy { it.id }
        val closure = GroupProxyPins.memberClosure(g, allById)
        if (closure.isEmpty() && g.templateFile == null) return
        mutateGraph(mergeable = true) {
            for (id in closure) {
                _updateNodeInternal(id) { n ->
                    n.copy(pos = CanvasPos(n.pos.x + dxWorld, n.pos.y + dyWorld))
                }
            }
            // Also shift the group anchor + nested group anchors so sub-tiles follow.
            val anchorMap = graph.groups.associate { it.id to it.pos }.toMutableMap()
            val touched = HashSet<GroupId>()
            val stack = ArrayDeque<Group>(); stack.addLast(g)
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                if (!touched.add(cur.id)) continue
                anchorMap[cur.id] = CanvasPos(
                    cur.pos.x + dxWorld, cur.pos.y + dyWorld
                )
                for (m in cur.members) if (m is MemberRef.Sub) allById[m.id]?.let(stack::addLast)
            }
            val rebuilt = graph.groups.map { gg -> gg.copy(pos = anchorMap[gg.id] ?: gg.pos) }
            graph.groups.clear()
            graph.groups.addAll(rebuilt)
            syncGroupsFlow()
        }
    }

    /**
     * Wired by [NodeEditorScreen] once it has a Compose scope. Null
     * before that point — `EditorState` is also used in headless tests
     * where sync is not needed.
     */
    var templateSync: GroupTemplateSync? = null

    /**
     * Persist a (possibly inline) group to a file under that name. The
     * group becomes linked; its existing runtime ids are mapped to fresh
     * [TemplateNodeId]s recorded in `templateIdMap`. Returns the
     * template that was written, or null on bad input.
     */
    fun saveAsTemplate(groupId: GroupId, fileName: String): dev.nitka.nodewire.graph.GroupTemplate? {
        val safe = GroupFiles.sanitize(fileName)
        if (safe.isEmpty()) return null
        val idx = graph.groups.indexOfFirst { it.id == groupId }
        if (idx < 0) return null
        val g = graph.groups[idx]
        val allById = graph.groups.associateBy { it.id }
        val closure = GroupProxyPins.memberClosure(g, allById)

        val templateIdMap = HashMap<dev.nitka.nodewire.graph.TemplateNodeId, dev.nitka.nodewire.graph.NodeId>()
        for (rid in closure) templateIdMap[java.util.UUID.randomUUID()] = rid

        val runtimeToTemplate = templateIdMap.entries.associate { (t, r) -> r to t }

        val templateNodes = closure.mapNotNull { rid ->
            val n = graph.nodes[rid] ?: return@mapNotNull null
            val tid = runtimeToTemplate[rid]!!
            tid to n.copy(id = tid)
        }.toMap()
        val templateEdges = graph.edges.filter {
            it.from.node in closure && it.to.node in closure
        }.map { e ->
            dev.nitka.nodewire.graph.Edge(
                dev.nitka.nodewire.graph.PinRef(runtimeToTemplate[e.from.node]!!, e.from.pin),
                dev.nitka.nodewire.graph.PinRef(runtimeToTemplate[e.to.node]!!, e.to.pin),
            )
        }
        val template = dev.nitka.nodewire.graph.GroupTemplate(templateNodes, templateEdges, emptyList())

        mutateGraph {
            graph.groups[idx] = g.copy(templateFile = safe, templateIdMap = templateIdMap)
            syncGroupsFlow()
        }
        templateSync?.publishLocalEdit(safe, template)
            ?: GroupFiles.save(safe, template)
        templateSync?.observeFile(safe)
        return template
    }

    /**
     * Insert a saved template into the host graph at the given world
     * position. Returns the new group's id, or null on missing file /
     * detected cycle.
     */
    fun insertTemplate(fileName: String, atWorld: dev.nitka.nodewire.graph.CanvasPos): GroupId? {
        val safe = GroupFiles.sanitize(fileName)
        if (safe.isEmpty()) return null
        val template = GroupFiles.load(safe) ?: return null

        // Cycle guard: refuse if any group on this graph that's linked to
        // a file is reachable from `safe`.
        val rootFiles = graph.groups.mapNotNull { it.templateFile }.toSet()
        val resolveFor: (String) -> dev.nitka.nodewire.graph.GroupTemplate? = { name -> GroupFiles.load(name) }
        for (root in rootFiles) {
            if (dev.nitka.nodewire.graph.GroupMembership.wouldCycle(root, safe, resolveFor)) return null
        }

        var gid: GroupId? = null
        mutateGraph {
            val res = dev.nitka.nodewire.graph.GroupTemplateResolver.instantiate(
                host = graph,
                template = template,
                templateFile = safe,
                anchor = atWorld,
                resolve = resolveFor,
            )
            gid = res.groupId
            for (n in graph.nodes.values) {
                if (nodeFlows[n.id] == null) nodeFlows[n.id] = kotlinx.coroutines.flow.MutableStateFlow(n)
            }
            _nodes.value = graph.nodes.keys.toList()
            _edges.value = graph.edges.toList()
            syncGroupsFlow()
        }
        templateSync?.observeFile(safe)
        return gid
    }

    companion object {
        /** World-space radius around an input pin that counts as "dropped on it". */
        private const val PIN_HIT_RADIUS = 12f
        private const val DUPLICATE_OFFSET = 20f
        /** Match `NodeCard.CARD_WIDTH`; duplicated as a constant rather than imported to avoid a cyclic dep. */
        private const val NODE_CARD_WIDTH = 200f
        /** Used only until a card's onSizeChanged fires once. Small so unmeasured cards don't over-select. */
        private const val NODE_CARD_HEIGHT_FALLBACK = 24f
        /** World-space distance below which a press+release is treated as a click, not a drag. */
        private const val CLICK_THRESHOLD = 4f
    }
}

val LocalEditorState = compositionLocalOf<EditorState?> { null }

/**
 * Latest [GraphEvaluator] result for the open editor. NodeCard's output
 * pins read it to show their live value. Null outside an editor screen.
 */
val LocalEvalResult = compositionLocalOf<EvalResult?> { null }
