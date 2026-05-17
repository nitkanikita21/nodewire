package dev.nitka.nodewire.client.screen

import dev.nitka.nodewire.graph.Group
import dev.nitka.nodewire.graph.GroupId
import dev.nitka.nodewire.graph.MemberRef
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.NodeId
import dev.nitka.nodewire.graph.PinType

/**
 * One auto-derived pin on a collapsed group's tile. The proxy is purely
 * a render-time artefact — it carries enough info for the renderer to
 * draw the row, and for `WireLayer` to detour wires that touch
 * [innerNode] through this pin's position.
 */
data class GroupProxyPin(
    val side: PinSide,
    val innerNode: NodeId,
    val innerPin: String,
    val type: PinType,
    val label: String,
)

object GroupProxyPins {

    /**
     * Compute the proxy pin set for a group's collapsed tile. Caller
     * supplies the recursive member-node closure so we don't have to
     * traverse `MemberRef.Sub` here.
     */
    fun compute(graph: NodeGraph, group: Group, memberClosure: Set<NodeId>): List<GroupProxyPin> {
        val result = mutableListOf<GroupProxyPin>()
        val seen = HashSet<Triple<NodeId, String, PinSide>>()
        for (e in graph.edges) {
            val fromInside = e.from.node in memberClosure
            val toInside = e.to.node in memberClosure
            if (fromInside == toInside) continue
            if (fromInside) {
                val node = graph.nodes[e.from.node] ?: continue
                val pin = node.outputs.firstOrNull { it.id == e.from.pin } ?: continue
                val key = Triple(node.id, pin.id, PinSide.Output)
                if (seen.add(key)) {
                    val override = group.pinLabelOverrides["${node.id}.${pin.id}"]
                    result.add(GroupProxyPin(
                        side = PinSide.Output,
                        innerNode = node.id,
                        innerPin = pin.id,
                        type = pin.type,
                        label = override ?: "${node.id.toString().take(4)}.${pin.name}",
                    ))
                }
            } else {
                val node = graph.nodes[e.to.node] ?: continue
                val pin = node.inputs.firstOrNull { it.id == e.to.pin } ?: continue
                val key = Triple(node.id, pin.id, PinSide.Input)
                if (seen.add(key)) {
                    val override = group.pinLabelOverrides["${node.id}.${pin.id}"]
                    result.add(GroupProxyPin(
                        side = PinSide.Input,
                        innerNode = node.id,
                        innerPin = pin.id,
                        type = pin.type,
                        label = override ?: "${node.id.toString().take(4)}.${pin.name}",
                    ))
                }
            }
        }
        return result
    }

    /**
     * Recursively expand `group.members` into the flat set of node ids.
     * Sub-group entries are resolved by id lookup in [allGroups].
     */
    fun memberClosure(group: Group, allGroups: Map<GroupId, Group>): Set<NodeId> {
        val out = HashSet<NodeId>()
        val stack = ArrayDeque<Group>()
        stack.addLast(group)
        val seen = HashSet<GroupId>()
        while (stack.isNotEmpty()) {
            val g = stack.removeLast()
            if (!seen.add(g.id)) continue
            for (m in g.members) {
                when (m) {
                    is MemberRef.Node -> out.add(m.id)
                    is MemberRef.Sub -> allGroups[m.id]?.let(stack::addLast)
                }
            }
        }
        return out
    }
}
