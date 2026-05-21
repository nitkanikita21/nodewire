package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag

/**
 * Evaluator that maintains per-node state between [tick] calls. Use this
 * for runtime semantics where some nodes (Timer, debounce, edge-detect)
 * need to remember previous ticks. The owning game loop is expected to
 * call [tick] once per game tick.
 *
 * Stateless nodes still use their [NodeType.evaluate]; a node's
 * [NodeType.tickEvaluator] takes precedence when present.
 *
 * State is stored in a private map keyed by [NodeId]. Each entry is a
 * mutable [CompoundTag] that the tick evaluator mutates in-place — same
 * NBT shape as on-disk Node.config, so persistence is one map write away
 * if/when we add it.
 *
 * Cycles through state-bearing nodes are allowed (and intended). A state
 * node logically reads its inputs *as of the previous tick* — that's the
 * defining property of "stateful": Counter.reset can depend on
 * Counter.out via Compare etc. The evaluator breaks such cycles by
 * removing edges *into* state nodes from the topo-sort graph, then
 * feeding those inputs from a snapshot of the previous tick's outputs.
 */
class StatefulGraphEvaluator(val graph: NodeGraph) {

    private val nodeStates: MutableMap<NodeId, CompoundTag> = HashMap()

    /** Outputs produced by the previous tick — read for state-node inputs. */
    private var lastOutputs: Map<Pair<NodeId, String>, PinValue> = emptyMap()

    /**
     * Drop state for removed nodes. Cheap to call every tick — usually a
     * no-op since most ticks don't add/remove nodes.
     */
    private fun pruneStates() {
        if (nodeStates.keys.size > graph.nodes.size) {
            val gone = nodeStates.keys - graph.nodes.keys
            for (id in gone) nodeStates.remove(id)
        }
    }

    /** True if the node at [id] has a tick evaluator — i.e. is stateful. */
    private fun isStateNode(id: NodeId): Boolean {
        val type = graph.nodes[id]?.let { NodeTypeRegistry.get(it.typeKey) }
        return type?.tickEvaluator != null
    }

    fun tick(externalOutputs: Map<Pair<NodeId, String>, PinValue> = emptyMap()): EvalResult {
        pruneStates()
        val order = topoSort(graph) ?: return EvalResult(emptyMap())
        val outputs = HashMap<Pair<NodeId, String>, PinValue>(externalOutputs)

        val incoming = HashMap<Pair<NodeId, String>, PinRef>()
        for (e in graph.edges) incoming[e.to.node to e.to.pin] = e.from

        for (nodeId in order) {
            val node = graph.nodes[nodeId] ?: continue
            val type = NodeTypeRegistry.get(node.typeKey) ?: continue
            if (node.outputs.all { (nodeId to it.id) in outputs }) continue
            val stateful = type.tickEvaluator != null

            val inputs = HashMap<String, PinValue>()
            for (pin in node.inputs) {
                val src = incoming[nodeId to pin.id]
                // State-node inputs read from the previous-tick snapshot so
                // feedback edges (e.g. Counter → Compare → Counter.reset)
                // work without forming a forward cycle.
                val value = if (src != null) {
                    if (stateful) lastOutputs[src.node to src.pin]
                    else outputs[src.node to src.pin]
                } else null
                inputs[pin.id] = when {
                    value == null -> {
                        val pinDefault = node.getPinDefault(pin.id) ?: PinValue.default(pin.type)
                        if (pin.type == PinType.ANY) pinDefault
                        else PinValueConversion.convert(pinDefault, pin.type)
                    }
                    pin.type == PinType.ANY -> value
                    else -> PinValueConversion.convert(value, pin.type)
                }
            }

            val produced: Map<String, PinValue> = when {
                type.tickEvaluator != null -> {
                    val state = nodeStates.getOrPut(nodeId) { CompoundTag() }
                    type.tickEvaluator.invoke(state, node.config, inputs)
                }
                type.evaluate != null -> type.evaluate.invoke(node.config, inputs)
                else -> emptyMap()
            }
            for ((pinId, value) in produced) {
                val key = nodeId to pinId
                if (key !in externalOutputs) outputs[key] = value
            }
        }
        // Snapshot for the next tick. Copy so external mutations don't leak.
        lastOutputs = HashMap(outputs)
        return EvalResult(outputs)
    }

    /**
     * Kahn's topo-sort, but ignoring edges that land on a state node —
     * those represent a temporal "previous tick" dep and don't constrain
     * within-tick ordering. Result is null only if a cycle exists among
     * stateless nodes (a real logical error).
     */
    private fun topoSort(graph: NodeGraph): List<NodeId>? {
        val indegree = HashMap<NodeId, Int>()
        val outAdj = HashMap<NodeId, MutableList<NodeId>>()
        for (id in graph.nodes.keys) indegree[id] = 0
        for (e in graph.edges) {
            // Edges into state nodes are temporal — drop them from the
            // forward DAG so cycles closed through state break here.
            if (isStateNode(e.to.node)) continue
            outAdj.getOrPut(e.from.node) { mutableListOf() }.add(e.to.node)
            indegree[e.to.node] = (indegree[e.to.node] ?: 0) + 1
        }
        val queue = ArrayDeque<NodeId>()
        for ((id, deg) in indegree) if (deg == 0) queue.add(id)
        val order = ArrayList<NodeId>(graph.nodes.size)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            order.add(n)
            for (next in outAdj[n].orEmpty()) {
                val d = (indegree[next] ?: 0) - 1
                indegree[next] = d
                if (d == 0) queue.add(next)
            }
        }
        return if (order.size == graph.nodes.size) order else null
    }
}
