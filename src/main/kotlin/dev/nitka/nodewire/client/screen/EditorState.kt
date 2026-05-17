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
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.NodeId
import dev.nitka.nodewire.graph.NodeTypeRegistry
import dev.nitka.nodewire.graph.PinRef
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
        return g
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
