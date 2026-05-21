package dev.nitka.nodewire.graph

/**
 * Evaluates a [NodeGraph] for one tick. Walks the DAG once: for each node
 * (in any order that respects edges → dependencies-first), gathers the
 * current values on its input pins (propagated from upstream outputs or
 * the type default), calls [NodeType.evaluate], and stores the resulting
 * output values.
 *
 * Inputs from external block ports (e.g. `block_input`) are supplied via
 * [externalOutputs] — keyed by `(nodeId, pinId)`. They override whatever
 * the type's own evaluator would produce, which is the easiest way to
 * inject world state without making the evaluator world-aware.
 *
 * Cycles abort the walk and leave any not-yet-resolved nodes empty —
 * `SaveGraphPacket` rejects cyclic graphs server-side, so this is mostly
 * a safety net.
 *
 * Output: a map of `(nodeId, outputPinId)` → [PinValue]. Callers read
 * what they need (e.g. each `block_output`'s pin values to drive the
 * world).
 */
class EvalResult(val outputs: Map<Pair<NodeId, String>, PinValue>) {
    /** Convenience: value at one specific output pin, or `null` if unset. */
    fun valueAt(node: NodeId, pin: String): PinValue? = outputs[node to pin]
}

object GraphEvaluator {

    fun eval(
        graph: NodeGraph,
        externalOutputs: Map<Pair<NodeId, String>, PinValue> = emptyMap(),
    ): EvalResult {
        val order = topoSortOrNull(graph) ?: return EvalResult(emptyMap())
        val outputs = HashMap<Pair<NodeId, String>, PinValue>(externalOutputs)

        // Index edges by destination pin so input lookup is O(1).
        val incoming = HashMap<Pair<NodeId, String>, PinRef>()
        for (e in graph.edges) incoming[e.to.node to e.to.pin] = e.from

        for (nodeId in order) {
            val node = graph.nodes[nodeId] ?: continue
            val type = NodeTypeRegistry.get(node.typeKey)
            val evalFn = type?.evaluate ?: continue
            // Skip if already supplied externally (block_input case).
            if (node.outputs.all { (nodeId to it.id) in outputs }) continue

            val inputs = HashMap<String, PinValue>()
            for (pin in node.inputs) {
                val src = incoming[nodeId to pin.id]
                val value = if (src != null) {
                    outputs[src.node to src.pin]
                } else null
                inputs[pin.id] = when {
                    value == null -> PinValue.default(pin.type)
                    pin.type == PinType.ANY -> value
                    else -> PinValueConversion.convert(value, pin.type)
                }
            }

            val produced = evalFn(node.config, inputs)
            for ((pinId, value) in produced) {
                val key = nodeId to pinId
                // Don't overwrite externally-supplied values.
                if (key !in externalOutputs) outputs[key] = value
            }
        }
        return EvalResult(outputs)
    }

    /**
     * Kahn-style topological sort. Returns `null` if a cycle is present
     * (caller should treat that as an empty result; cycles shouldn't
     * occur in saved graphs because [SaveGraphPacket] rejects them).
     */
    private fun topoSortOrNull(graph: NodeGraph): List<NodeId>? {
        val indegree = HashMap<NodeId, Int>()
        val outAdj = HashMap<NodeId, MutableList<NodeId>>()
        for (id in graph.nodes.keys) indegree[id] = 0
        for (e in graph.edges) {
            outAdj.getOrPut(e.from.node) { mutableListOf() }.add(e.to.node)
            indegree[e.to.node] = (indegree[e.to.node] ?: 0) + 1
        }
        val queue: ArrayDeque<NodeId> = ArrayDeque()
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
