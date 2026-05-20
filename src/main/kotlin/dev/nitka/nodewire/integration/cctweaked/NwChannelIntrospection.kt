package dev.nitka.nodewire.integration.cctweaked

import dev.nitka.nodewire.graph.EvalResult
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue

/**
 * Maps of channel names exposed by a graph. Inputs are written FROM CC
 * into the graph; outputs are read FROM the graph by CC.
 *
 * Duplicates: first-by-creation-order wins. Blank names are skipped
 * silently — the editor permits empty names while the user is mid-edit.
 */
object NwChannelIntrospection {

    fun inputs(graph: NodeGraph): Map<String, PinType> =
        collect(graph, "channel_input")

    fun outputs(graph: NodeGraph): Map<String, PinType> =
        collect(graph, "channel_output")

    /**
     * Per-name current value of each `channel_output` node, taken from
     * the value flowing into its "in" pin in [result]. Channels with no
     * incoming edge are absent from the result (so CC sees `nil`).
     */
    fun outputSnapshot(graph: NodeGraph, result: EvalResult): Map<String, PinValue> {
        val out = LinkedHashMap<String, PinValue>()
        for (node in graph.nodes.values) {
            if (node.typeKey.path != "channel_output") continue
            val name = node.config.getString("name")
            if (name.isBlank()) continue
            if (name in out) continue
            val edge = graph.edges.firstOrNull {
                it.to.node == node.id && it.to.pin == "in"
            } ?: continue
            val value = result.valueAt(edge.from.node, edge.from.pin) ?: continue
            out[name] = value
        }
        return out
    }

    private fun collect(graph: NodeGraph, typePath: String): Map<String, PinType> {
        val out = LinkedHashMap<String, PinType>()
        for (node in graph.nodes.values) {
            if (node.typeKey.path != typePath) continue
            val name = node.config.getString("name")
            if (name.isBlank()) continue
            if (name in out) continue
            val type = PinType.fromName(node.config.getString("type"))
            out[name] = type
        }
        return out
    }
}
