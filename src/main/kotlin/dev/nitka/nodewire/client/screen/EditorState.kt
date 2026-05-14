package dev.nitka.nodewire.client.screen

import androidx.compose.runtime.compositionLocalOf
import dev.nitka.nodewire.graph.EvalResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.nitka.nodewire.graph.CanvasPos
import dev.nitka.nodewire.graph.Edge
import dev.nitka.nodewire.graph.Node
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.PinRef

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
class EditorState(val graph: NodeGraph) {
    val pinPositions = PinPositions()

    /**
     * Bumped every time the node set changes (add / remove). NodeEditorScreen
     * reads it as a remember key so the rendered card list refreshes; edge
     * mutations don't need this since WireLayer paints from the graph on
     * every frame.
     */
    var nodesVersion: Int by mutableStateOf(0)
        private set

    /**
     * Bumped on any logical change to the graph — nodes, edges, configs.
     * Used as a remember key for downstream consumers that depend on the
     * full graph state (e.g. the live [GraphEvaluator] overlay). Distinct
     * from [nodesVersion] which only tracks node-set membership.
     */
    var graphVersion: Int by mutableStateOf(0)
        private set

    fun bumpGraphVersion() {
        graphVersion++
    }

    fun addNode(node: Node) {
        graph.add(node)
        nodesVersion++
        graphVersion++
    }

    fun removeNode(id: dev.nitka.nodewire.graph.NodeId) {
        graph.removeNode(id)
        nodesVersion++
        graphVersion++
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
        graph.add(copy)
        nodesVersion++
        graphVersion++
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
        graph.connectReplacing(Edge(PinRef(output.node, output.pin), PinRef(input.node, input.pin)))
        graphVersion++
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
            graph.connectReplacing(Edge(PinRef(output.node, output.pin), PinRef(input.node, input.pin)))
            graphVersion++
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
    fun changeChannelType(node: Node, newType: dev.nitka.nodewire.graph.PinType) {
        val pin = (node.inputs + node.outputs).firstOrNull() ?: return
        val rebuilt = pin.copy(type = newType)
        if (node.inputs.isNotEmpty()) node.inputs = listOf(rebuilt) else node.outputs = listOf(rebuilt)
        node.config.putString("type", newType.name)
        disconnectAllEdges(node.id)
        graphVersion++
    }

    /**
     * Convert-to-Redstone has a single input pin whose type follows
     * `config.sourceType`. Rebuild the pin and snip incompatible edges.
     */
    fun changeConverterInput(node: Node, newType: dev.nitka.nodewire.graph.PinType) {
        node.inputs = listOf(node.inputs.first().copy(type = newType))
        disconnectAllEdges(node.id)
        graphVersion++
    }

    private fun disconnectAllEdges(id: dev.nitka.nodewire.graph.NodeId) {
        val before = graph.edges.size
        graph.edges.removeAll { it.from.node == id || it.to.node == id }
        if (graph.edges.size != before) graphVersion++
    }

    fun disconnectPin(key: PinKey) {
        val before = graph.edges.size
        graph.edges.removeAll { edge ->
            (edge.from.node == key.node && edge.from.pin == key.pin && key.side == PinSide.Output) ||
                (edge.to.node == key.node && edge.to.pin == key.pin && key.side == PinSide.Input)
        }
        if (graph.edges.size != before) graphVersion++
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

    companion object {
        /** World-space radius around an input pin that counts as "dropped on it". */
        private const val PIN_HIT_RADIUS = 12f
        private const val DUPLICATE_OFFSET = 20f
    }
}

val LocalEditorState = compositionLocalOf<EditorState?> { null }

/**
 * Latest [GraphEvaluator] result for the open editor. NodeCard's output
 * pins read it to show their live value. Null outside an editor screen.
 */
val LocalEvalResult = compositionLocalOf<EvalResult?> { null }
